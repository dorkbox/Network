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
            override fun close() {}
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
        fun process(header: Header, buffer: DirectBuffer, offset: Int, length: Int) {
            // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!

            // The sessionId is unique within a Subscription and unique across all Publication's from a sourceIdentity.
            // for the handshake, the sessionId IS NOT GLOBALLY UNIQUE
            val sessionId = header.sessionId()
            val streamId = header.streamId()
            val aeronLogInfo = "$sessionId/$streamId"

            val message = server.readHandshakeMessage(buffer, offset, length, header, aeronLogInfo)

            // VALIDATE:: a Registration object is the only acceptable message during the connection phase
            if (message !is HandshakeMessage) {
                logger.error { "[$aeronLogInfo] Connection from IPC not allowed! Invalid connection request" }
            } else {
                // we create a NEW publication for the handshake, which connects directly to the client handshake subscription
                val publicationUri = uri("ipc", message.sessionId)
                logger.trace { "Server IPC connection pub ${publicationUri.build()},stream-id=${message.streamId}" }

                val publication = try {
                    aeronDriver.addExclusivePublication(publicationUri, message.streamId)
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

                publication.close()
            }
        }
    }

    class IpProc<CONNECTION : Connection>(
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

        fun process(header: Header, buffer: DirectBuffer, offset: Int, length: Int) {
            // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!

            // The sessionId is unique within a Subscription and unique across all Publication's from a sourceIdentity.
            // for the handshake, the sessionId IS NOT GLOBALLY UNIQUE
            val sessionId = header.sessionId()
            val streamId = header.streamId()
            val aeronLogInfo = "$sessionId/$streamId"

            // note: this address will ALWAYS be an IP:PORT combo  OR  it will be aeron:ipc  (if IPC, it will be a different handler!)
            val remoteIpAndPort = (header.context() as Image).sourceIdentity()

            // split
            val splitPoint = remoteIpAndPort.lastIndexOf(':')
            var clientAddressString = remoteIpAndPort.substring(0, splitPoint)

            val message = server.readHandshakeMessage(buffer, offset, length, header, aeronLogInfo)

            // VALIDATE:: a Registration object is the only acceptable message during the connection phase
            if (message !is HandshakeMessage) {
                logger.error { "[$aeronLogInfo] Connection from $clientAddressString not allowed! Invalid connection request" }
            } else {
                // this should never be null, because we are feeding it a valid IP address from aeron
                val clientAddress = IP.toAddress(clientAddressString)
                if (clientAddress == null) {
                    logger.error { "[$aeronLogInfo] Connection from $clientAddressString not allowed! Invalid IP address!" }
                    return
                }

                val isRemoteIpv4 = clientAddress is Inet4Address
                if (!isRemoteIpv4) {
                    // this is necessary to clean up the address when adding it to aeron, since different formats mess it up
                    clientAddressString = IP.toString(clientAddress)
                }


                // NOTE: publications are REMOVED from Aeron clients when their linger timeout has expired!!!

                // if we are listening on :: (ipv6), and a connection via ipv4 arrives, aeron MUST publish on the IPv4 version
                val properPubAddress = EndPoint.getWildcard(listenAddress, listenAddressString, isRemoteIpv4)

                // we create a NEW publication for the handshake, which connects directly to the client handshake subscription CONTROL (which then goes to the proper endpoint)
                val publicationUri = uri("udp", message.sessionId, isReliable)
                publicationUri.controlEndpoint(isRemoteIpv4, properPubAddress, port)

                logger.trace { "Server connection pub $publicationUri,stream-id=${message.streamId}" }


                val publication = try {
                    aeronDriver.addExclusivePublication(publicationUri, message.streamId)
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
                        server, driver, publication,
                        clientAddress, clientAddressString, isReliable,
                        message,
                        aeronLogInfo, connectionFunc,
                        logger
                    )
                } else {
                    logger.error { "Cannot create publication back to $clientAddressString" }
                }

                // publications are REMOVED from Aeron clients when their linger timeout has expired!!!
                publication.close()
            }
        }
    }

    fun <CONNECTION : Connection> ipc(
        aeronDriver: AeronDriver, config: ServerConfiguration, server: Server<CONNECTION>, handshake: ServerHandshake<CONNECTION>
    ): AeronPoller
    {
        val logger = server.logger
        val connectionFunc = server.connectionFunc

        val poller = if (config.enableIpc) {
            val driver = ServerIpcDriver(
                streamId = config.ipcId,
                sessionId = AeronDriver.IPC_HANDSHAKE_SESSION_ID
            )
            driver.build(aeronDriver, logger)

            val subscription = driver.subscription
            val processor = IpcProc(logger, server, aeronDriver, handshake, connectionFunc)

            object : AeronPoller {
                val handler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
                    processor.process(header, buffer, offset, length)
                }

                override fun poll(): Int {
                    return subscription.poll(handler, 1)
                }

                override fun close() {
                    subscription.close()
                }

                override val info = "IPC $driver"
            }
        } else {
            disabled("IPC Disabled")
        }

        logger.info { poller.info }
        return poller
    }



    fun <CONNECTION : Connection> ip4(
        aeronDriver: AeronDriver, config: ServerConfiguration, server: Server<CONNECTION>, handshake: ServerHandshake<CONNECTION>
    ): AeronPoller {
        val logger = server.logger
        val connectionFunc = server.connectionFunc
        val isReliable = config.isReliable
        val pubPort = config.port + 1

        val poller = if (server.canUseIPv4) {
            val driver = ServerUdpDriver(
                listenAddress = server.listenIPv4Address!!,
                port = config.port,
                streamId = AeronDriver.UDP_HANDSHAKE_STREAM_ID,
                sessionId = AeronDriver.RESERVED_SESSION_ID_INVALID,
                connectionTimeoutSec = config.connectionCloseTimeoutInSeconds,
                isReliable = isReliable
            )

            driver.build(aeronDriver, logger)

            val subscription = driver.subscription
            val processor = IpProc(logger, server, driver, aeronDriver, handshake, connectionFunc, isReliable, pubPort)

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
                    processor.process(header, buffer, offset, length)
                }

                override fun poll(): Int {
                    return subscription.poll(handler, 1)
                }

                override fun close() {
                    subscription.close()
                }

                override val info = "IPv4 $driver"
            }
        } else {
            disabled("IPv4 Disabled")
        }

        logger.info { poller.info }
        return poller
    }

    fun <CONNECTION : Connection> ip6(
        aeronDriver: AeronDriver, config: ServerConfiguration, server: Server<CONNECTION>, handshake: ServerHandshake<CONNECTION>
    ): AeronPoller {
        val logger = server.logger
        val connectionFunc = server.connectionFunc
        val isReliable = config.isReliable
        val pubPort = config.port + 1

        val poller = if (server.canUseIPv6) {
            val driver = ServerUdpDriver(
                listenAddress = server.listenIPv6Address!!,
                port = config.port,
                streamId = AeronDriver.UDP_HANDSHAKE_STREAM_ID,
                sessionId = AeronDriver.RESERVED_SESSION_ID_INVALID,
                connectionTimeoutSec = config.connectionCloseTimeoutInSeconds,
                isReliable = isReliable
            )

            driver.build(aeronDriver, logger)

            val subscription = driver.subscription
            val processor = IpProc(logger, server, driver, aeronDriver, handshake, connectionFunc, isReliable, pubPort)


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
                    processor.process(header, buffer, offset, length)
                }

                override fun poll(): Int {
                    return subscription.poll(handler, 1)
                }

                override fun close() {
                    subscription.close()
                }

                override val info = "IPv6 $driver"
            }
        } else {
            disabled("IPv6 Disabled")
        }

        logger.info { poller.info }
        return poller
    }

    fun <CONNECTION : Connection> ip6Wildcard(
        aeronDriver: AeronDriver, config: ServerConfiguration, server: Server<CONNECTION>, handshake: ServerHandshake<CONNECTION>
    ): AeronPoller {
        val logger = server.logger
        val connectionFunc = server.connectionFunc
        val isReliable = config.isReliable
        val pubPort = config.port + 1

        val driver = ServerUdpDriver(
            listenAddress = server.listenIPv6Address!!,
            port = config.port,
            streamId = AeronDriver.UDP_HANDSHAKE_STREAM_ID,
            sessionId = AeronDriver.RESERVED_SESSION_ID_INVALID,
            connectionTimeoutSec = config.connectionCloseTimeoutInSeconds,
            isReliable = isReliable
        )

        driver.build(aeronDriver, logger)

        val subscription = driver.subscription
        val processor = IpProc(logger, server, driver, aeronDriver, handshake, connectionFunc, isReliable, pubPort)


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
                processor.process(header, buffer, offset, length)
            }

            override fun poll(): Int {
                return subscription.poll(handler, 1)
            }

            override fun close() {
                subscription.close()
            }

            override val info = "IPv4+6 $driver"
        }

        logger.info { poller.info }
        return poller
    }
}
