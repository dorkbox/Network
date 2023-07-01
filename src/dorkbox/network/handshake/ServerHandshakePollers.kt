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
import dorkbox.network.Configuration
import dorkbox.network.Server
import dorkbox.network.ServerConfiguration
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.AeronDriver.Companion.uriHandshake
import dorkbox.network.aeron.AeronPoller
import dorkbox.network.connection.*
import dorkbox.network.exceptions.ServerException
import dorkbox.network.exceptions.ServerHandshakeException
import dorkbox.network.exceptions.ServerTimedoutException
import dorkbox.util.NamedThreadFactory
import io.aeron.CommonContext
import io.aeron.FragmentAssembler
import io.aeron.Image
import io.aeron.Publication
import io.aeron.logbuffer.FragmentHandler
import io.aeron.logbuffer.Header
import kotlinx.coroutines.runBlocking
import mu.KLogger
import net.jodah.expiringmap.ExpirationPolicy
import net.jodah.expiringmap.ExpiringMap
import org.agrona.DirectBuffer
import java.net.Inet4Address
import java.util.concurrent.*

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
    ): FragmentHandler {

        private val connectionTimeoutSec = server.config.connectionCloseTimeoutInSeconds
        private val isReliable = server.config.isReliable
        private val handshaker = server.handshaker

        // note: the expire time here is a LITTLE longer than the expire time in the client, this way we can adjust for network lag if it's close
        private val publications = ExpiringMap.builder()
            .apply {
                // connections are extremely difficult to diagnose when the connection timeout is short
                val timeUnit = if (EndPoint.DEBUG_CONNECTIONS) { TimeUnit.HOURS } else { TimeUnit.NANOSECONDS }

                // we MUST include the publication linger timeout, otherwise we might encounter problems that are NOT REALLY problems
                this.expiration(TimeUnit.SECONDS.toNanos(connectionTimeoutSec.toLong() * 2) + driver.lingerNs(), timeUnit)
            }
            .expirationPolicy(ExpirationPolicy.CREATED)
            .expirationListener<Long, Publication> { connectKey, publication ->
                runBlocking {
                    driver.closeAndDeletePublication(publication, "Server IPC Handshake ($connectKey)")
                }
            }
            .build<Long, Publication>()

        override fun onFragment(buffer: DirectBuffer, offset: Int, length: Int, header: Header) {
            // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!

            // The sessionId is unique within a Subscription and unique across all Publication's from a sourceIdentity.
            // for the handshake, the sessionId IS NOT GLOBALLY UNIQUE
            val sessionId = header.sessionId()
            val streamId = header.streamId()
            val logInfo = "$sessionId/$streamId : IPC" // Server is the "source", client mirrors the server

            // ugh, this is verbose -- but necessary
            val message = try {
                val msg = handshaker.readMessage(buffer, offset, length, logInfo)

                // VALIDATE:: a Registration object is the only acceptable message during the connection phase
                if (msg !is HandshakeMessage) {
                    throw ServerHandshakeException("[$logInfo] Connection not allowed! unrecognized message: $msg")
                } else {
                    logger.trace { "[$logInfo] (${msg.connectKey}) received HS: $msg" }
                }
                msg
            } catch (e: Exception) {
                server.listenerManager.notifyError(ServerHandshakeException("[$logInfo] Error de-serializing handshake message!!", e))
                null
            } ?: return



            // we have read all the data, now dispatch it.
            EventDispatcher.HANDSHAKE.launch {
                // HandshakeMessage.HELLO
                // HandshakeMessage.DONE
                val messageState = message.state
                val connectKey = message.connectKey

                if (messageState == HandshakeMessage.HELLO) {
                    // we create a NEW publication for the handshake, which connects directly to the client handshake subscription
                    val publicationUri = uriHandshake(CommonContext.IPC_MEDIA, isReliable)

                    // this will always connect to the CLIENT handshake subscription!
                    val publication = try {
                        driver.addExclusivePublication(publicationUri, message.streamId, logInfo, true)
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

                    val success = handshake.processIpcHandshakeMessageServer(
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

                    if (success) {
                        publications[connectKey] = publication
                    } else {
                        driver.closeAndDeletePublication(publication, "HANDSHAKE-IPC")
                    }
                } else {
                    val publication = publications.remove(connectKey)

                    if (publication == null) {
                        server.listenerManager.notifyError(ServerHandshakeException("[$logInfo] No publication back to IPC"))
                        return@launch
                    }

                    // HandshakeMessage.DONE
                    handshake.validateMessageTypeAndDoPending(
                        server = server,
                        handshaker = handshaker,
                        handshakePublication = publication,
                        message = message,
                        aeronLogInfo = logInfo,
                        logger = logger
                    )

                    driver.closeAndDeletePublication(publication, "HANDSHAKE-IPC")
                }
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
    ): FragmentHandler {
        companion object {
            init {
                ExpiringMap.setThreadFactory(NamedThreadFactory("ExpiringMap-Eviction", Configuration.networkThreadGroup, true))
            }
        }

        private val ipInfo = server.ipInfo
        private val handshaker = server.handshaker
        private val connectionTimeoutSec = server.config.connectionCloseTimeoutInSeconds

        // note: the expire time here is a LITTLE longer than the expire time in the client, this way we can adjust for network lag if it's close
        private val publications = ExpiringMap.builder()
            .apply {
                // connections are extremely difficult to diagnose when the connection timeout is short
                val timeUnit = if (EndPoint.DEBUG_CONNECTIONS) { TimeUnit.HOURS } else { TimeUnit.NANOSECONDS }

                // we MUST include the publication linger timeout, otherwise we might encounter problems that are NOT REALLY problems
                this.expiration(TimeUnit.SECONDS.toNanos(connectionTimeoutSec.toLong() * 2) + driver.lingerNs(), timeUnit)
            }
            .expirationPolicy(ExpirationPolicy.CREATED)
            .expirationListener<Long, Publication> { connectKey, publication ->
                runBlocking {
                    driver.closeAndDeletePublication(publication, "Server UDP Handshake ($connectKey)")
                }
            }
            .build<Long, Publication>()


        override fun onFragment(buffer: DirectBuffer, offset: Int, length: Int, header: Header) {
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


            // ugh, this is verbose -- but necessary
            val message = try {
                val msg = handshaker.readMessage(buffer, offset, length, logInfo)

                // VALIDATE:: a Registration object is the only acceptable message during the connection phase
                if (msg !is HandshakeMessage) {
                    throw ServerHandshakeException("[$logInfo] Connection not allowed! unrecognized message: $msg")
                } else {
                    logger.trace { "[$logInfo] (${msg.connectKey}) received HS: $msg" }
                }
                msg
            } catch (e: Exception) {
                server.listenerManager.notifyError(ServerHandshakeException("[$logInfo] Error de-serializing handshake message!!", e))
                null
            } ?: return



            EventDispatcher.HANDSHAKE.launch {
                // HandshakeMessage.HELLO
                // HandshakeMessage.DONE
                val messageState = message.state
                val connectKey = message.connectKey

                if (messageState == HandshakeMessage.HELLO) {
                    // we create a NEW publication for the handshake, which connects directly to the client handshake subscription

                    // A control endpoint for the subscriptions will cause a periodic service management "heartbeat" to be sent to the
                    // remote endpoint publication, which permits the remote publication to send us data, thereby getting us around NAT
                    val publicationUri = uriHandshake(CommonContext.UDP_MEDIA, isReliable)
                        .controlEndpoint(ipInfo.getAeronPubAddress(isRemoteIpv4) + ":" + message.port)
                        .controlMode(CommonContext.MDC_CONTROL_MODE_DYNAMIC)


                    // this will always connect to the CLIENT handshake subscription!
                    val publication = try {
                        driver.addExclusivePublication(publicationUri, message.streamId, logInfo, false)
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

                    val success = handshake.processUdpHandshakeMessageServer(
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

                    if (success) {
                        publications[connectKey] = publication
                    } else {
                        driver.closeAndDeletePublication(publication, logInfo)
                    }

                } else {
                    // HandshakeMessage.DONE

                    val publication = publications.remove(connectKey)

                    if (publication == null) {
                        server.listenerManager.notifyError(ServerHandshakeException("[$logInfo] No publication back to $clientAddressString"))
                        return@launch
                    }


                    handshake.validateMessageTypeAndDoPending(
                        server = server,
                        handshaker = handshaker,
                        handshakePublication = publication,
                        message = message,
                        aeronLogInfo = logInfo,
                        logger = logger
                    )

                    driver.closeAndDeletePublication(publication, logInfo)
                }
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

            object : AeronPoller {
                // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
                //  publication of any state to other threads and not be:
                //   - long running
                //   - re-entrant with the client
                val handler = FragmentAssembler(IpcProc(logger, server, server.aeronDriver, handshake, connectionFunc))

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
                streamIdSub = config.udpId,
                sessionIdSub = 9,
                logInfo = "HANDSHAKE-IPv4"
            )

            val subscription = driver.subscription

            object : AeronPoller {
                // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
                //  publication of any state to other threads and not be:
                //   - long running
                //   - re-entrant with the client
                val handler = FragmentAssembler(UdpProc(logger, server, server.aeronDriver, handshake, connectionFunc, isReliable))

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
                streamIdSub = config.udpId,
                sessionIdSub = 0,
                logInfo = "HANDSHAKE-IPv6"
            )

            val subscription = driver.subscription

            object : AeronPoller {
                // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
                //  publication of any state to other threads and not be:
                //   - long running
                //   - re-entrant with the client
                val handler = FragmentAssembler(UdpProc(logger, server, server.aeronDriver, handshake, connectionFunc, isReliable))

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
                streamIdSub = config.udpId,
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
                val handler = FragmentAssembler(processor)

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
