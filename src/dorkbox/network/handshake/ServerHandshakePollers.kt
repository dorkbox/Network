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
import dorkbox.network.aeron.mediaDriver.ServerHandshakeDriver
import dorkbox.network.connection.Connection
import dorkbox.network.connection.ConnectionParams
import dorkbox.network.connection.EventDispatcher
import dorkbox.network.connection.IpInfo
import dorkbox.network.exceptions.ClientTimedOutException
import io.aeron.FragmentAssembler
import io.aeron.Image
import io.aeron.logbuffer.Header
import kotlinx.coroutines.runBlocking
import mu.KLogger
import org.agrona.DirectBuffer
import java.net.Inet4Address

internal object ServerHandshakePollers {
    // session IDs are unique for a entire driver, and there are errors with IPC when creating/closing IPC publications too quickly.
//    val sessionIdMap = LockFreeIntMap<Publication>()

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

        private val timeout = server.config.connectionCloseTimeoutInSeconds
        private val isReliable = server.config.isReliable
        private val handshaker = server.handshaker

        suspend fun process(header: Header, buffer: DirectBuffer, offset: Int, length: Int) {
            // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!

            // The sessionId is unique within a Subscription and unique across all Publication's from a sourceIdentity.
            // for the handshake, the sessionId IS NOT GLOBALLY UNIQUE
            val sessionId = header.sessionId()
            val streamId = header.streamId()
            val aeronLogInfo = "$streamId/$sessionId : IPC" // Server is the "source", client mirrors the server

            val message = handshaker.readMessage(buffer, offset, length, aeronLogInfo)

            // VALIDATE:: a Registration object is the only acceptable message during the connection phase
            if (message !is HandshakeMessage) {
                logger.error { "[$aeronLogInfo] Connection not allowed! Invalid connection request" }
                return
            }

            logger.error { "RECEIVED IPC HANDSHAKE MESSAGE" }

            // we have read all the data, now dispatch it.
            EventDispatcher.launch(EventDispatcher.Companion.EVENT.HANDSHAKE) {
                // we create a NEW publication for the handshake, which connects directly to the client handshake subscription
                val publicationUri = uriHandshake("ipc", isReliable)

                val publication = try {
                    // we actually have to wait for it to connect before we continue
                    driver.addPublicationWithTimeout(publicationUri, timeout, message.streamId, aeronLogInfo) { cause ->
                        ClientTimedOutException("$aeronLogInfo publication cannot connect with client!", cause)
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Cannot create IPC publication back to client remote process" }
                    return@launch
                }

                logger.error { "CREATED IPC PUB: ${publication.registrationId()}" }

                // Manage the Handshake state. When done with a connection, this returns false
                if (!handshake.validateMessageTypeAndDoPending(
                        server = server,
                        handshaker = handshaker,
                        handshakePublication = publication,
                        message = message,
                        logger = logger)) {

                    logger.error { "DELETED IPC PUB: ${publication.registrationId()}" }
//                        sessionIdMap.remove(message.sessionId)
                    driver.closeAndDeletePublication(publication, "HANDSHAKE-IPC")
                    return@launch
                }

                handshake.processIpcHandshakeMessageServer(
                    server = server,
                    handshaker = handshaker,
                    aeronDriver = driver,
                    handshakePublication = publication,
                    message = message,
                    aeronLogInfo = aeronLogInfo,
                    connectionFunc = connectionFunc,
                    logger = logger
                )

                logger.error { "DELETED IPC PUB: ${publication.registrationId()}" }
                driver.closeAndDeletePublication(publication, "HANDSHAKE-IPC")
            }
        }
    }

    class UdpProc<CONNECTION : Connection>(
        val logger: KLogger,
        val server: Server<CONNECTION>,
        val mediaDriver: ServerHandshakeDriver,
        val driver: AeronDriver,
        val handshake: ServerHandshake<CONNECTION>,
        val connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION,
        val isReliable: Boolean
    ) {

        private val ipInfo = server.ipInfo
        private val logInfo = mediaDriver.logInfo
        private val handshaker = server.handshaker

        suspend fun process(header: Header, buffer: DirectBuffer, offset: Int, length: Int) {
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
                logger.error { "[$streamId/$sessionId] Connection from $clientAddressString not allowed! Invalid IP address!" }
                return
            }

            val isRemoteIpv4 = clientAddress is Inet4Address
            if (!isRemoteIpv4) {
                // this is necessary to clean up the address when adding it to aeron, since different formats mess it up
                clientAddressString = IP.toString(clientAddress)

                if (ipInfo.ipType == IpInfo.Companion.IpListenType.IPv4Wildcard) {
                    // we DO NOT want to listen to IPv4 traffic, but we received IPv4 traffic!
                    logger.error { "[$streamId/$sessionId] Connection from $clientAddressString not allowed! IPv4 connections not permitted!" }
                    return
                }
            }

            val aeronLogInfo = "$streamId/$sessionId : $clientAddressString"

            val message = handshaker.readMessage(buffer, offset, length, aeronLogInfo)

            // VALIDATE:: a Registration object is the only acceptable message during the connection phase
            if (message !is HandshakeMessage) {
                logger.error { "[$aeronLogInfo] Connection not allowed! Invalid connection request" }
                return
            }

            logger.error { "RECEIVED UDP HANDSHAKE MESSAGE" }

            EventDispatcher.launch(EventDispatcher.Companion.EVENT.HANDSHAKE) {
                // we create a NEW publication for the handshake, which connects directly to the client handshake subscription CONTROL (which then goes to the proper endpoint)
                val publicationUri = uriHandshake("udp", isReliable)
                    .endpoint(ipInfo.getAeronPubAddress(isRemoteIpv4) + ":" + message.port)

                val publication = try {
                    // we actually have to wait for it to connect before we continue
                    driver.addPublicationWithTimeout(publicationUri, 10, message.streamId, aeronLogInfo) { cause ->
                        ClientTimedOutException("$logInfo publication cannot connect with client!", cause)
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Cannot create publication back to $clientAddressString" }
                    return@launch
                }

                logger.error { "CONNECTED" }
                logger.error { "CONNECTED" }

                // Manage the Handshake state. When done with a connection, this returns
                if (!handshake.validateMessageTypeAndDoPending(
                        server = server,
                        handshaker = handshaker,
                        handshakePublication = publication,
                        message = message,
                        logger = logger)) {

                    driver.closeAndDeletePublication(publication, logInfo)
                    logger.error { "DONE CONNECTED" }
                    logger.error { "DONE CONNECTED" }
                    return@launch
                }

                handshake.processUdpHandshakeMessageServer(
                    server = server,
                    handshaker = handshaker,
                    handshakePublication = publication,
                    clientAddress = clientAddress,
                    clientAddressString = clientAddressString,
                    isReliable = isReliable,
                    message = message,
                    aeronLogInfo = aeronLogInfo,
                    connectionFunc = connectionFunc,
                    logger = logger
                )

                driver.closeAndDeletePublication(publication, logInfo)

                logger.error { "DONE CONNECTED" }
                logger.error { "DONE CONNECTED" }
            }
        }
    }

    suspend fun <CONNECTION : Connection> ipc(server: Server<CONNECTION>, handshake: ServerHandshake<CONNECTION>): AeronPoller {
        val logger = server.logger
        val connectionFunc = server.connectionFunc
        val config = server.config as ServerConfiguration

        val poller = try {
            val driver = ServerHandshakeDriver(
                aeronDriver = server.aeronDriver,
                isIpc = true,
                ipInfo = server.ipInfo,
                streamIdSub = config.ipcId,
                sessionIdSub = AeronDriver.RESERVED_SESSION_ID_INVALID,
                isReliable = true,
                logInfo = "HANDSHAKE-IPC",
                logger = logger
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
                    runBlocking {
                        processor.process(header, buffer, offset, length)
                    }
                }

                override fun poll(): Int {
                    return subscription.poll(handler, 1)
                }

                override suspend fun close() {
                    driver.close()
                }

                override val info = "IPC $driver"
            }
        } catch (e: Exception) {
            logger.error(e) { "Unable to create IPC listener." }
            disabled("IPC Disabled")
        }

        logger.info { poller.info }
        return poller
    }



    suspend fun <CONNECTION : Connection> ip4(server: Server<CONNECTION>, handshake: ServerHandshake<CONNECTION>): AeronPoller {
        val logger = server.logger
        val connectionFunc = server.connectionFunc
        val config = server.config
        val isReliable = config.isReliable

        val poller = try {
            val driver = ServerHandshakeDriver(
                aeronDriver = server.aeronDriver,
                isIpc = false,
                ipInfo = server.ipInfo,
                streamIdSub = AeronDriver.UDP_HANDSHAKE_STREAM_ID,
                sessionIdSub = 9,
                isReliable = isReliable,
                logInfo = "HANDSHAKE-IPv4",
                logger = logger
            )

            val subscription = driver.subscription
            val processor = UdpProc(logger, server, driver, server.aeronDriver, handshake, connectionFunc, isReliable)

            object : AeronPoller {
                // NOTE: subscriptions (ie: reading from buffers, etc) are not thread safe!  Because it is ambiguous HOW EXACTLY they are unsafe,
                //  we exclusively read from the DirectBuffer on a single thread.

                // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
                //  publication of any state to other threads and not be:
                //   - long running
                //   - re-entrant with the client
                val handler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
                    runBlocking {
                        processor.process(header, buffer, offset, length)
                    }
                }

                override fun poll(): Int {
                    return subscription.poll(handler, 1)
                }

                override suspend fun close() {
                    driver.close()
                }

                override val info = "IPv4 $driver"
            }
        } catch (e: Exception) {
            logger.error(e) { "Unable to create IPv4 listener." }
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
            val driver = ServerHandshakeDriver(
                isIpc = false,
                aeronDriver = server.aeronDriver,
                ipInfo = server.ipInfo,
                streamIdSub = AeronDriver.UDP_HANDSHAKE_STREAM_ID,
                sessionIdSub = 0,
                isReliable = isReliable,
                logInfo = "HANDSHAKE-IPv6",
                logger = logger
            )

            val subscription = driver.subscription
            val processor = UdpProc(logger, server, driver, server.aeronDriver, handshake, connectionFunc, isReliable)


            object : AeronPoller {
                // NOTE: subscriptions (ie: reading from buffers, etc) are not thread safe!  Because it is ambiguous HOW EXACTLY they are unsafe,
                //  we exclusively read from the DirectBuffer on a single thread.

                // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
                //  publication of any state to other threads and not be:
                //   - long running
                //   - re-entrant with the client
                val handler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
                    runBlocking {
                        processor.process(header, buffer, offset, length)
                    }
                }

                override fun poll(): Int {
                    return subscription.poll(handler, 1)
                }

                override suspend fun close() {
                    driver.close()
                }

                override val info = "IPv6 $driver"
            }
        } catch (e: Exception) {
            logger.error(e) { "Unable to create IPv6 listener." }
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
            val driver = ServerHandshakeDriver(
                isIpc = false,
                aeronDriver = server.aeronDriver,
                ipInfo = server.ipInfo,
                streamIdSub = AeronDriver.UDP_HANDSHAKE_STREAM_ID,
                sessionIdSub = 0,
                isReliable = isReliable,
                logInfo = "HANDSHAKE-IPv4+6",
                logger = logger
            )


            val subscription = driver.subscription
            val processor = UdpProc(logger, server, driver, server.aeronDriver, handshake, connectionFunc, isReliable)

            object : AeronPoller {
                // NOTE: subscriptions (ie: reading from buffers, etc) are not thread safe!  Because it is ambiguous HOW EXACTLY they are unsafe,
                //  we exclusively read from the DirectBuffer on a single thread.

                // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
                //  publication of any state to other threads and not be:
                //   - long running
                //   - re-entrant with the client
                val handler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
                    runBlocking {
                        processor.process(header, buffer, offset, length)
                    }
                }

                override fun poll(): Int {
                    return subscription.poll(handler, 1)
                }

                override suspend fun close() {
                    driver.close()
                }

                override val info = "IPv4+6 $driver"
            }
        } catch (e: Exception) {
            logger.error(e) { "Unable to create IPv4+6 listeners." }
            disabled("IPv4+6 Disabled")
        }

        return poller
    }
}
