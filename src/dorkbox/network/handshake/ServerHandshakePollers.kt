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
import dorkbox.network.aeron.AeronPoller
import dorkbox.network.aeron.mediaDriver.MediaDriverConnection.Companion.uri
import dorkbox.network.aeron.mediaDriver.ServerIpcDriver
import dorkbox.network.aeron.mediaDriver.ServerUdpDriver
import dorkbox.network.aeron.mediaDriver.controlEndpoint
import dorkbox.network.connection.Connection
import dorkbox.network.connection.ConnectionParams
import dorkbox.network.connection.EndPoint
import io.aeron.CommonContext
import io.aeron.FragmentAssembler
import io.aeron.Image
import io.aeron.logbuffer.Header
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
        val aeronDriver: AeronDriver,
        val handshake: ServerHandshake<CONNECTION>,
        val connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION
    ) {
        suspend fun process(header: Header, buffer: DirectBuffer, offset: Int, length: Int) {
            // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!

            // The sessionId is unique within a Subscription and unique across all Publication's from a sourceIdentity.
            // for the handshake, the sessionId IS NOT GLOBALLY UNIQUE
            val sessionId = header.sessionId()
            val streamId = header.streamId()
            val aeronLogInfo = "$streamId/$sessionId : IPC" // Server is the "source", client mirrors the server

            val message = server.readHandshakeMessage(buffer, offset, length, aeronLogInfo)

            // VALIDATE:: a Registration object is the only acceptable message during the connection phase
            if (message !is HandshakeMessage) {
                logger.error { "[$aeronLogInfo] Connection not allowed! Invalid connection request" }
            } else {
                // we create a NEW publication for the handshake, which connects directly to the client handshake subscription
                val publicationUri = uri("ipc", message.sessionId)

                val publication = try {
                    aeronDriver.addExclusivePublication(publicationUri, "IPC", message.streamId)
                } catch (e: Exception) {
                    logger.error(e) { "Cannot create IPC publication back to remote" }
                    return
                }

                // we actually have to wait for it to connect before we continue
                val timoutInNanos = aeronDriver.getLingerNs()
                val startTime = System.nanoTime()
                var success = false

                while (System.nanoTime() - startTime < timoutInNanos) {
                    if (publication.isConnected) {
                        success = true
                        break
                    }

                    Thread.sleep(10L)
                }

                if (success) {
                    handshake.processIpcHandshakeMessageServer(
                        server, publication, message,
                        aeronDriver, aeronLogInfo,
                        connectionFunc, logger
                    )
                } else {
                    logger.error { "Cannot create IPC publication back to remote process" }
                }


                aeronDriver.closeAndDeletePublication(publication, "ServerIPC")
            }
        }
    }

    class UdpProc<CONNECTION : Connection>(
        val logger: KLogger,
        val server: Server<CONNECTION>,
        val driver: ServerUdpDriver,
        val aeronDriver: AeronDriver,
        val handshake: ServerHandshake<CONNECTION>,
        val connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION,
        val isReliable: Boolean,
        val port: Int
    ) {
        val listenAddress = driver.listenAddress
        val listenAddressString = IP.toString(listenAddress)
        val timoutInNanos = aeronDriver.getLingerNs()

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
            val type: String

            if (isRemoteIpv4) {
                type =  "IPv4"
            } else {
                // this is necessary to clean up the address when adding it to aeron, since different formats mess it up
                clientAddressString = IP.toString(clientAddress)
                type = "IPv6"
            }


            // if we are listening on :: (ipv6), and a connection via ipv4 arrives, aeron MUST publish on the IPv4 version
            val properPubAddress = EndPoint.getWildcard(listenAddress, listenAddressString, isRemoteIpv4)

            // Server is the "source", client mirrors the server
            val aeronLogInfo = "$streamId/$sessionId : $clientAddressString"


            val message = server.readHandshakeMessage(buffer, offset, length, aeronLogInfo)

            // VALIDATE:: a Registration object is the only acceptable message during the connection phase
            if (message !is HandshakeMessage) {
                logger.error { "[$aeronLogInfo] Connection not allowed! Invalid connection request" }
            } else {
                // NOTE: publications are REMOVED from Aeron clients when their linger timeout has expired!!!

                // we create a NEW publication for the handshake, which connects directly to the client handshake subscription CONTROL (which then goes to the proper endpoint)
                val publicationUri = uri("udp", message.sessionId, isReliable)
                    .controlEndpoint(isRemoteIpv4, properPubAddress, port)
                    .controlMode(CommonContext.MDC_CONTROL_MODE_DYNAMIC)

                val publication = try {
                    aeronDriver.addExclusivePublication(publicationUri, type, message.streamId)
                } catch (e: Exception) {
                    logger.error(e) { "Cannot create publication back to $clientAddressString" }
                    return
                }

                // we actually have to wait for it to connect before we continue

                val startTime = System.nanoTime()
                var success = false

                while (System.nanoTime() - startTime < timoutInNanos) {
                    if (publication.isConnected) {
                        success = true
                        break
                    }

                    Thread.sleep(10L)
                }

                if (success) {
                    handshake.processUdpHandshakeMessageServer(
                        server = server,
                        driver = driver,
                        handshakePublication = publication,
                        clientAddress = clientAddress,
                        clientAddressString = clientAddressString,
                        isReliable = isReliable,
                        message = message,
                        aeronLogInfo = aeronLogInfo,
                        connectionFunc = connectionFunc,
                        logger = logger
                    )
                } else {
                    logger.error { "Cannot create publication back to $clientAddressString" }
                }

                // publications are REMOVED from Aeron clients when their linger timeout has expired!!!
                aeronDriver.closeAndDeletePublication(publication, "ServerProc")
            }
        }
    }

    suspend fun <CONNECTION : Connection> ipc(server: Server<CONNECTION>, handshake: ServerHandshake<CONNECTION>): AeronPoller
    {
        val logger = server.logger
        val connectionFunc = server.connectionFunc
        val config = server.config as ServerConfiguration

        val poller = if (config.enableIpc) {
            val driver = ServerIpcDriver(
                aeronDriver = server.aeronDriver,
                streamId = config.ipcId,
                sessionId = AeronDriver.IPC_HANDSHAKE_SESSION_ID
            )
            driver.build(logger)

            val subscription = driver.subscription
            val processor = IpcProc(logger, server, server.aeronDriver, handshake, connectionFunc)

            object : AeronPoller {
                val handler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
                    runBlocking {
                        processor.process(header, buffer, offset, length)
                    }
                }

                override fun poll(): Int {
                    return subscription.poll(handler, 1)
                }

                override suspend fun close() {
                    driver.close(logger)
                }

                override val info = "IPC $driver"
            }
        } else {
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
        val pubPort = config.port + 1

        val poller = if (server.canUseIPv4) {
            val driver = ServerUdpDriver(
                aeronDriver = server.aeronDriver,
                listenAddress = server.listenIPv4Address!!,
                port = config.port,
                streamId = AeronDriver.UDP_HANDSHAKE_STREAM_ID,
                sessionId = AeronDriver.RESERVED_SESSION_ID_INVALID,
                connectionTimeoutSec = config.connectionCloseTimeoutInSeconds,
                isReliable = isReliable,
                "IPv4"
            )

            driver.build(logger)

            val subscription = driver.subscription
            val processor = UdpProc(logger, server, driver, server.aeronDriver, handshake, connectionFunc, isReliable, pubPort)

            object : AeronPoller {
                /**
                 * Note:
                 * Reassembly has been shown to be minimal impact to latency. But not totally negligible. If the lowest latency is
                 * desired, then limiting message sizes to MTU size is a good practice.
                 *
                 * There is a maximum length allowed for messages which is the min of 1/8th a term length or 16MB.
                 * Messages larger than this should chunked using an application level chunking protocol. Chunking has better recovery
                 * properties from failure and streams with mechanical sympathy.
                 */
                val handler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
                    runBlocking {
                        processor.process(header, buffer, offset, length)
                    }
                }

                override fun poll(): Int {
                    return subscription.poll(handler, 1)
                }

                override suspend fun close() {
                    driver.close(logger)
                }

                override val info = "IPv4 $driver"
            }
        } else {
            disabled("IPv4 Disabled")
        }

        logger.info { poller.info }
        return poller
    }

    suspend fun <CONNECTION : Connection> ip6(server: Server<CONNECTION>, handshake: ServerHandshake<CONNECTION>): AeronPoller {
        val logger = server.logger
        val connectionFunc = server.connectionFunc
        val config = server.config
        val isReliable = config.isReliable
        val pubPort = config.port + 1

        val poller = if (server.canUseIPv6) {
            val driver = ServerUdpDriver(
                aeronDriver = server.aeronDriver,
                listenAddress = server.listenIPv6Address!!,
                port = config.port,
                streamId = AeronDriver.UDP_HANDSHAKE_STREAM_ID,
                sessionId = AeronDriver.RESERVED_SESSION_ID_INVALID,
                connectionTimeoutSec = config.connectionCloseTimeoutInSeconds,
                isReliable = isReliable,
                "IPv6"
            )

            driver.build(logger)

            val subscription = driver.subscription
            val processor = UdpProc(logger, server, driver, server.aeronDriver, handshake, connectionFunc, isReliable, pubPort)


            object : AeronPoller {
                /**
                 * Note:
                 * Reassembly has been shown to be minimal impact to latency. But not totally negligible. If the lowest latency is
                 * desired, then limiting message sizes to MTU size is a good practice.
                 *
                 * There is a maximum length allowed for messages which is the min of 1/8th a term length or 16MB.
                 * Messages larger than this should chunked using an application level chunking protocol. Chunking has better recovery
                 * properties from failure and streams with mechanical sympathy.
                 */
                val handler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
                    runBlocking {
                        processor.process(header, buffer, offset, length)
                    }
                }

                override fun poll(): Int {
                    return subscription.poll(handler, 1)
                }

                override suspend fun close() {
                    driver.close(logger)
                }

                override val info = "IPv6 $driver"
            }
        } else {
            disabled("IPv6 Disabled")
        }

        logger.info { poller.info }
        return poller
    }

    suspend fun <CONNECTION : Connection> ip6Wildcard(server: Server<CONNECTION>, handshake: ServerHandshake<CONNECTION>): AeronPoller {
        val logger = server.logger
        val connectionFunc = server.connectionFunc
        val config = server.config
        val isReliable = config.isReliable
        val pubPort = config.port + 1

        val driver = ServerUdpDriver(
            aeronDriver = server.aeronDriver,
            listenAddress = server.listenIPv6Address!!,
            port = config.port,
            streamId = AeronDriver.UDP_HANDSHAKE_STREAM_ID,
            sessionId = AeronDriver.RESERVED_SESSION_ID_INVALID,
            connectionTimeoutSec = config.connectionCloseTimeoutInSeconds,
            isReliable = isReliable,
            "IPv4+6"
        )

        driver.build(logger)

        val subscription = driver.subscription
        val processor = UdpProc(logger, server, driver, server.aeronDriver, handshake, connectionFunc, isReliable, pubPort)


        val poller = object : AeronPoller {
            /**
             * Note:
             * Reassembly has been shown to be minimal impact to latency. But not totally negligible. If the lowest latency is
             * desired, then limiting message sizes to MTU size is a good practice.
             *
             * There is a maximum length allowed for messages which is the min of 1/8th a term length or 16MB.
             * Messages larger than this should chunked using an application level chunking protocol. Chunking has better recovery
             * properties from failure and streams with mechanical sympathy.
             */
            val handler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
                runBlocking {
                    processor.process(header, buffer, offset, length)
                }
            }

            override fun poll(): Int {
                return subscription.poll(handler, 1)
            }

            override suspend fun close() {
                driver.close(logger)
            }

            override val info = "IPv4+6 $driver"
        }

        logger.info { poller.info }
        return poller
    }
}
