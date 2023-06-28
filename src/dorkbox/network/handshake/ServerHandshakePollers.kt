/*
 * Copyright 2023 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("MemberVisibilityCanBePrivate", "DuplicatedCode")

package dorkbox.network.handshake

import dorkbox.netUtil.IP
import dorkbox.network.Server
import dorkbox.network.ServerConfiguration
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.AeronDriver.Companion.uriHandshake
import dorkbox.network.aeron.AeronPoller
import dorkbox.network.connection.Connection
import dorkbox.network.connection.ConnectionParams
import dorkbox.network.connection.EventDispatcher
import dorkbox.network.connection.IpInfo
import dorkbox.network.exceptions.ServerException
import dorkbox.network.exceptions.ServerHandshakeException
import dorkbox.network.exceptions.ServerTimedoutException
import io.aeron.CommonContext
import io.aeron.FragmentAssembler
import io.aeron.Image
import io.aeron.logbuffer.Header
import mu.KLogger
import org.agrona.DirectBuffer
import java.net.Inet4Address

internal object ServerHandshakePollers {
    fun disabled(serverInfo: String): AeronPoller {
        return object : AeronPoller {
            override fun poll(): Int { return 0 }
            override suspend fun close() {}
            override val info = serverInfo
        }
    }

    class IpcProc<CONNECTION : Connection>(
        val logger: KLogger,
        val server: Server<CONNECTION>,
        val driver: AeronDriver,
        val handshake: ServerHandshake<CONNECTION>,
        val connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION
    ) {

        private val connectionTimeoutSec = server.config.connectionCloseTimeoutInSeconds
        private val isReliable = server.config.isReliable
        private val handshaker = server.handshaker

        fun process(header: Header, buffer: DirectBuffer, offset: Int, length: Int) {
            // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!

            // The sessionId is unique within a Subscription and unique across all Publication's from a sourceIdentity.
            // for the handshake, the sessionId IS NOT GLOBALLY UNIQUE
            val sessionId = header.sessionId()
            val streamId = header.streamId()
            val logInfo = "$sessionId/$streamId : IPC" // Server is the "source", client mirrors the server

            val message = handshaker.readMessage(buffer, offset, length, logInfo)

            // VALIDATE:: a Registration object is the only acceptable message during the connection phase
            if (message !is HandshakeMessage) {
                server.listenerManager.notifyError(ServerHandshakeException("[$logInfo] Connection not allowed! Invalid connection request"))
                return
            }

            // we have read all the data, now dispatch it.
            EventDispatcher.HANDSHAKE.launch {
                // we create a NEW publication for the handshake, which connects directly to the client handshake subscription
                val publicationUri = uriHandshake(CommonContext.IPC_MEDIA, isReliable)

                // this will always connect to the CLIENT handshake subscription!
                val publication = try {
                    driver.addExclusivePublication(publicationUri, message.streamId, logInfo)
                } catch (e: Exception) {
                    server.listenerManager.notifyError(ServerHandshakeException("[$logInfo] Cannot create IPC publication back to client remote process", e))
                    return@launch
                }

                try {
                    // we actually have to wait for it to connect before we continue
                    driver.waitForConnection(publication, connectionTimeoutSec, logInfo) { cause ->
                        ServerTimedoutException("$logInfo publication cannot connect with client!", cause)
                    }
                } catch (e: Exception) {
                    server.listenerManager.notifyError(ServerHandshakeException("[$logInfo] Cannot create IPC publication back to client remote process", e))
                    return@launch
                }

                // HandshakeMessage.HELLO
                // HandshakeMessage.DONE
                val messageState = message.state

                if (messageState == HandshakeMessage.HELLO) {
                    handshake.processIpcHandshakeMessageServer(
                        server = server,
                        handshaker = handshaker,
                        aeronDriver = driver,
                        handshakePublication = publication,
                        clientUuid = HandshakeMessage.uuidReader(message.registrationData!!),
                        message = message,
                        aeronLogInfo = logInfo,
                        connectionFunc = connectionFunc,
                        logger = logger
                    )
                } else {
                    // HandshakeMessage.DONE
                    handshake.validateMessageTypeAndDoPending(
                        server = server,
                        handshaker = handshaker,
                        handshakePublication = publication,
                        message = message,
                        aeronLogInfo = logInfo,
                        logger = logger
                    )
                }

                driver.closeAndDeletePublication(publication, "HANDSHAKE-IPC")
            }
        }
    }

    class UdpProc<CONNECTION : Connection>(
        val logger: KLogger,
        val server: Server<CONNECTION>,
        val driver: AeronDriver,
        val handshake: ServerHandshake<CONNECTION>,
        val connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION,
        val isReliable: Boolean
    ) {

        private val ipInfo = server.ipInfo
        private val handshaker = server.handshaker
        private val connectionTimeoutSec = server.config.connectionCloseTimeoutInSeconds

        fun process(header: Header, buffer: DirectBuffer, offset: Int, length: Int) {
            // The sessionId is unique within a Subscription and unique across all Publication's from a sourceIdentity.
            // for the handshake, the sessionId IS NOT GLOBALLY UNIQUE


            // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!

            // The sessionId is unique within a Subscription and unique across all Publication's from a sourceIdentity.
            // for the handshake, the sessionId IS NOT GLOBALLY UNIQUE
            val sessionId = header.sessionId()
            val streamId = header.streamId()

            // note: this address will ALWAYS be an IP:PORT combo  OR  it will be aeron:ipc  (if IPC, it will be a different handler!)
            val remoteIpAndPort = (header.context() as Image).sourceIdentity()

            // split
            val splitPoint = remoteIpAndPort.lastIndexOf(':')
            var clientAddressString = remoteIpAndPort.substring(0, splitPoint)

            // this should never be null, because we are feeding it a valid IP address from aeron
            val clientAddress = IP.toAddress(clientAddressString)
            if (clientAddress == null) {
                // Server is the "source", client mirrors the server
                server.listenerManager.notifyError(ServerHandshakeException("[$sessionId/$streamId] Connection from $clientAddressString not allowed! Invalid IP address!"))
                return
            }

            val isRemoteIpv4 = clientAddress is Inet4Address
            if (!isRemoteIpv4) {
                // this is necessary to clean up the address when adding it to aeron, since different formats mess it up
                clientAddressString = IP.toString(clientAddress)

                if (ipInfo.ipType == IpInfo.Companion.IpListenType.IPv4Wildcard) {
                    // we DO NOT want to listen to IPv4 traffic, but we received IPv4 traffic!
                    server.listenerManager.notifyError(ServerHandshakeException("[$sessionId/$streamId] Connection from $clientAddressString not allowed! IPv4 connections not permitted!"))
                    return
                }
            }

            val logInfo = "$sessionId/$streamId:$clientAddressString"

            val message = handshaker.readMessage(buffer, offset, length, logInfo)

            // VALIDATE:: a Registration object is the only acceptable message during the connection phase
            if (message !is HandshakeMessage) {
                server.listenerManager.notifyError(ServerHandshakeException("[$logInfo] Connection not allowed! Invalid connection request"))
                return
            }

            EventDispatcher.HANDSHAKE.launch {
                // we create a NEW publication for the handshake, which connects directly to the client handshake subscription

                // A control endpoint for the subscriptions will cause a periodic service management "heartbeat" to be sent to the
                // remote endpoint publication, which permits the remote publication to send us data, thereby getting us around NAT
                val publicationUri = uriHandshake(CommonContext.UDP_MEDIA, isReliable)
                    .controlEndpoint(ipInfo.getAeronPubAddress(isRemoteIpv4) + ":" + message.port)
                    .controlMode(CommonContext.MDC_CONTROL_MODE_DYNAMIC)


                // this will always connect to the CLIENT handshake subscription!
                val publication = try {
                    driver.addExclusivePublication(publicationUri, message.streamId, logInfo)
                } catch (e: Exception) {
                    server.listenerManager.notifyError(ServerHandshakeException("[$logInfo] Cannot create publication back to $clientAddressString", e))
                    return@launch
                }

                try {
                    // we actually have to wait for it to connect before we continue
                    driver.waitForConnection(publication, connectionTimeoutSec, logInfo) { cause ->
                        ServerTimedoutException("$logInfo publication cannot connect with client!", cause)
                    }
                } catch (e: Exception) {
                    server.listenerManager.notifyError(ServerHandshakeException("[$logInfo] Cannot create publication back to $clientAddressString", e))
                    return@launch
                }


                // HandshakeMessage.HELLO
                // HandshakeMessage.DONE
                val messageState = message.state

                if (messageState == HandshakeMessage.HELLO) {
                    handshake.processUdpHandshakeMessageServer(
                        server = server,
                        handshaker = handshaker,
                        handshakePublication = publication,
                        clientUuid = HandshakeMessage.uuidReader(message.registrationData!!),
                        clientAddress = clientAddress,
                        clientAddressString = clientAddressString,
                        isReliable = isReliable,
                        message = message,
                        aeronLogInfo = logInfo,
                        connectionFunc = connectionFunc,
                        logger = logger
                    )
                } else {
                    // HandshakeMessage.DONE
                    handshake.validateMessageTypeAndDoPending(
                        server = server,
                        handshaker = handshaker,
                        handshakePublication = publication,
                        message = message,
                        aeronLogInfo = logInfo,
                        logger = logger
                    )
                }

                driver.closeAndDeletePublication(publication, logInfo)
            }
        }
    }

    suspend fun <CONNECTION : Connection> ipc(server: Server<CONNECTION>, handshake: ServerHandshake<CONNECTION>): AeronPoller {
        val logger = server.logger
        val connectionFunc = server.connectionFunc
        val config = server.config as ServerConfiguration

        val poller = try {
            val driver = ServerHandshakeDriver.build(
                aeronDriver = server.aeronDriver,
                isIpc = true,
                port = 0,
                ipInfo = server.ipInfo,
                streamIdSub = config.ipcId,
                sessionIdSub = AeronDriver.RESERVED_SESSION_ID_INVALID,
                logInfo = "HANDSHAKE-IPC"
            )

            val subscription = driver.subscription
            val processor = IpcProc(logger, server, server.aeronDriver, handshake, connectionFunc)

            object : AeronPoller {
                // NOTE: subscriptions (ie: reading from buffers, etc) are not thread safe!  Because it is ambiguous HOW EXACTLY they are unsafe,
                //  we exclusively read from the DirectBuffer on a single thread.

                // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
                //  publication of any state to other threads and not be:
                //   - long running
                //   - re-entrant with the client
                val handler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
                    processor.process(header, buffer, offset, length)
                }

                override fun poll(): Int {
                    return subscription.poll(handler, 1)
                }

                override suspend fun close() {
                    driver.close()
                }

                override val info = "IPC ${driver.info}"
            }
        } catch (e: Exception) {
            server.listenerManager.notifyError(ServerException("Unable to create IPC listener.", e))
            disabled("IPC Disabled")
        }

        return poller
    }



    suspend fun <CONNECTION : Connection> ip4(server: Server<CONNECTION>, handshake: ServerHandshake<CONNECTION>): AeronPoller {
        val logger = server.logger
        val connectionFunc = server.connectionFunc
        val config = server.config
        val isReliable = config.isReliable

        val poller = try {
            val driver = ServerHandshakeDriver.build(
                aeronDriver = server.aeronDriver,
                isIpc = false,
                ipInfo = server.ipInfo,
                port = server.port,
                streamIdSub = AeronDriver.UDP_HANDSHAKE_STREAM_ID,
                sessionIdSub = 9,
                logInfo = "HANDSHAKE-IPv4"
            )

            val subscription = driver.subscription
            val processor = UdpProc(logger, server, server.aeronDriver, handshake, connectionFunc, isReliable)

            object : AeronPoller {
                // NOTE: subscriptions (ie: reading from buffers, etc) are not thread safe!  Because it is ambiguous HOW EXACTLY they are unsafe,
                //  we exclusively read from the DirectBuffer on a single thread.

                // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
                //  publication of any state to other threads and not be:
                //   - long running
                //   - re-entrant with the client
                val handler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
                    processor.process(header, buffer, offset, length)
                }

                override fun poll(): Int {
                    return subscription.poll(handler, 1)
                }

                override suspend fun close() {
                    driver.close()
                }

                override val info = "IPv4 ${driver.info}"
            }
        } catch (e: Exception) {
            server.listenerManager.notifyError(ServerException("Unable to create IPv4 listener.", e))
            disabled("IPv4 Disabled")
        }

        return poller
    }

    suspend fun <CONNECTION : Connection> ip6(server: Server<CONNECTION>, handshake: ServerHandshake<CONNECTION>): AeronPoller {
        val logger = server.logger
        val connectionFunc = server.connectionFunc
        val config = server.config
        val isReliable = config.isReliable

        val poller =  try {
            val driver = ServerHandshakeDriver.build(
                aeronDriver = server.aeronDriver,
                isIpc = false,
                ipInfo = server.ipInfo,
                port = server.port,
                streamIdSub = AeronDriver.UDP_HANDSHAKE_STREAM_ID,
                sessionIdSub = 0,
                logInfo = "HANDSHAKE-IPv6"
            )

            val subscription = driver.subscription
            val processor = UdpProc(logger, server, server.aeronDriver, handshake, connectionFunc, isReliable)


            object : AeronPoller {
                // NOTE: subscriptions (ie: reading from buffers, etc) are not thread safe!  Because it is ambiguous HOW EXACTLY they are unsafe,
                //  we exclusively read from the DirectBuffer on a single thread.

                // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
                //  publication of any state to other threads and not be:
                //   - long running
                //   - re-entrant with the client
                val handler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
                    processor.process(header, buffer, offset, length)
                }

                override fun poll(): Int {
                    return subscription.poll(handler, 1)
                }

                override suspend fun close() {
                    driver.close()
                }

                override val info = "IPv6 ${driver.info}"
            }
        } catch (e: Exception) {
            server.listenerManager.notifyError(ServerException("Unable to create IPv6 listener."))
            disabled("IPv6 Disabled")
        }

        return poller
    }

    suspend fun <CONNECTION : Connection> ip6Wildcard(server: Server<CONNECTION>, handshake: ServerHandshake<CONNECTION>): AeronPoller {

        val logger = server.logger
        val connectionFunc = server.connectionFunc
        val config = server.config
        val isReliable = config.isReliable

        val poller = try {
            val driver = ServerHandshakeDriver.build(
                aeronDriver = server.aeronDriver,
                isIpc = false,
                ipInfo = server.ipInfo,
                port = server.port,
                streamIdSub = AeronDriver.UDP_HANDSHAKE_STREAM_ID,
                sessionIdSub = 0,
                logInfo = "HANDSHAKE-IPv4+6"
            )


            val subscription = driver.subscription
            val processor = UdpProc(logger, server, server.aeronDriver, handshake, connectionFunc, isReliable)

            object : AeronPoller {
                // NOTE: subscriptions (ie: reading from buffers, etc) are not thread safe!  Because it is ambiguous HOW EXACTLY they are unsafe,
                //  we exclusively read from the DirectBuffer on a single thread.

                // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
                //  publication of any state to other threads and not be:
                //   - long running
                //   - re-entrant with the client
                val handler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
                    processor.process(header, buffer, offset, length)
                }

                override fun poll(): Int {
                    return subscription.poll(handler, 1)
                }

                override suspend fun close() {
                    driver.close()
                }

                override val info = "IPv4+6 ${driver.info}"
            }
        } catch (e: Exception) {
            server.listenerManager.notifyError(ServerException("Unable to create IPv4+6 listeners.", e))
            disabled("IPv4+6 Disabled")
        }

        return poller
    }
}
