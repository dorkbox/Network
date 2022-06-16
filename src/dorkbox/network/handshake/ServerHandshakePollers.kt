@file:Suppress("MemberVisibilityCanBePrivate", "DuplicatedCode")

package dorkbox.network.handshake

import dorkbox.network.Server
import dorkbox.network.ServerConfiguration
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.AeronPoller
import dorkbox.network.aeron.IpcMediaDriverConnection
import dorkbox.network.aeron.UdpMediaDriverServerConnection
import dorkbox.network.connection.Connection
import io.aeron.FragmentAssembler
import io.aeron.Image
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer

internal object ServerHandshakePollers {
    fun disabled(serverInfo: String): AeronPoller {
        return object : AeronPoller {
            override fun poll(): Int { return 0 }
            override fun close() {}
            override val serverInfo = serverInfo
        }
    }

    fun <CONNECTION : Connection> IPC(aeronDriver: AeronDriver, config: ServerConfiguration, server: Server<CONNECTION>): AeronPoller {
        val logger = server.logger
        val connectionFunc = server.connectionFunc
        val handshake = server.handshake

        val poller = if (config.enableIpc) {
            val driver = IpcMediaDriverConnection(
                streamIdSubscription = config.ipcSubscriptionId,
                streamId = config.ipcPublicationId,
                sessionId = AeronDriver.RESERVED_SESSION_ID_INVALID
            )
            driver.buildServer(aeronDriver, logger)

            val publication = driver.publication
            val subscription = driver.subscription

            object : AeronPoller {
                val handler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
                    // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!

                    // The sessionId is unique within a Subscription and unique across all Publication's from a sourceIdentity.
                    // for the handshake, the sessionId IS NOT GLOBALLY UNIQUE
                    val sessionId = header.sessionId()

                    val message = server.readHandshakeMessage(buffer, offset, length, header)

                    // VALIDATE:: a Registration object is the only acceptable message during the connection phase
                    if (message !is HandshakeMessage) {
                        logger.error { "[$sessionId] Connection from IPC not allowed! Invalid connection request" }

                        try {
                            server.writeHandshakeMessage(publication, HandshakeMessage.error("Invalid connection request"))
                        } catch (e: Exception) {
                            logger.error(e) { "Handshake error!" }
                        }
                        return@FragmentAssembler
                    }

                    handshake.processIpcHandshakeMessageServer(
                        server, publication, message, aeronDriver, connectionFunc, logger
                    )
                }

                override fun poll(): Int {
                    return subscription.poll(handler, 1)
                }

                override fun close() {
                    driver.close()
                }

                override val serverInfo = driver.serverInfo
            }
        } else {
            disabled("IPC Disabled")
        }

        logger.info { poller.serverInfo }
        return poller
    }



    fun <CONNECTION : Connection> ip4(aeronDriver: AeronDriver, config: ServerConfiguration, server: Server<CONNECTION>): AeronPoller {
        val logger = server.logger
        val connectionFunc = server.connectionFunc
        val handshake = server.handshake

        val poller = if (server.canUseIPv4) {
            val driver = UdpMediaDriverServerConnection(
                listenAddress = server.listenIPv4Address!!,
                publicationPort = config.publicationPort,
                subscriptionPort = config.subscriptionPort,
                streamId = AeronDriver.UDP_HANDSHAKE_STREAM_ID,
                sessionId = AeronDriver.RESERVED_SESSION_ID_INVALID,
                connectionTimeoutSec = config.connectionCloseTimeoutInSeconds
            )

            driver.buildServer(aeronDriver, logger)

            val publication = driver.publication
            val subscription = driver.subscription

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
                    // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!

                    // The sessionId is unique within a Subscription and unique across all Publication's from a sourceIdentity.
                    // for the handshake, the sessionId IS NOT GLOBALLY UNIQUE
                    val sessionId = header.sessionId()

                    // note: this address will ALWAYS be an IP:PORT combo  OR  it will be aeron:ipc  (if IPC, it will be a different handler!)
                    val remoteIpAndPort = (header.context() as Image).sourceIdentity()

                    val message = server.readHandshakeMessage(buffer, offset, length, header)

                    // VALIDATE:: a Registration object is the only acceptable message during the connection phase
                    if (message !is HandshakeMessage) {
                        logger.error {
                            // split
                            val splitPoint = remoteIpAndPort.lastIndexOf(':')
                            val clientAddressString = remoteIpAndPort.substring(0, splitPoint)

                            "[$sessionId] Connection from $clientAddressString not allowed! Invalid connection request"
                        }

                        try {
                            server.writeHandshakeMessage(publication, HandshakeMessage.error("Invalid connection request"))
                        } catch (e: Exception) {
                            logger.error(e) { "Handshake error!" }
                        }
                        return@FragmentAssembler
                    }

                    handshake.processUdpHandshakeMessageServer(
                        server, publication, remoteIpAndPort, message, aeronDriver, false, connectionFunc, logger
                    )
                }

                override fun poll(): Int {
                    return subscription.poll(handler, 1)
                }

                override fun close() {
                    driver.close()
                }

                override val serverInfo = driver.serverInfo
            }
        } else {
            disabled("IPv4 Disabled")
        }

        logger.info { poller.serverInfo }
        return poller
    }

    fun <CONNECTION : Connection> ip6(aeronDriver: AeronDriver, config: ServerConfiguration, server: Server<CONNECTION>): AeronPoller {
        val logger = server.logger
        val connectionFunc = server.connectionFunc
        val handshake = server.handshake

        val poller = if (server.canUseIPv6) {
            val driver = UdpMediaDriverServerConnection(
                listenAddress = server.listenIPv6Address!!,
                publicationPort = config.publicationPort,
                subscriptionPort = config.subscriptionPort,
                streamId = AeronDriver.UDP_HANDSHAKE_STREAM_ID,
                sessionId = AeronDriver.RESERVED_SESSION_ID_INVALID,
                connectionTimeoutSec = config.connectionCloseTimeoutInSeconds
            )

            driver.buildServer(aeronDriver, logger)

            val publication = driver.publication
            val subscription = driver.subscription

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
                    // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!
                    // The sessionId is unique within a Subscription and unique across all Publication's from a sourceIdentity.
                    // for the handshake, the sessionId IS NOT GLOBALLY UNIQUE
                    val sessionId = header.sessionId()

                    // note: this address will ALWAYS be an IP:PORT combo  OR  it will be aeron:ipc  (if IPC, it will be a different handler!)
                    val remoteIpAndPort = (header.context() as Image).sourceIdentity()

                    val message = server.readHandshakeMessage(buffer, offset, length, header)

                    // VALIDATE:: a Registration object is the only acceptable message during the connection phase
                    if (message !is HandshakeMessage) {
                        logger.error {
                            // split
                            val splitPoint = remoteIpAndPort.lastIndexOf(':')
                            val clientAddressString = remoteIpAndPort.substring(0, splitPoint)
                            "[$sessionId] Connection from $clientAddressString not allowed! Invalid connection request"
                        }

                        try {
                            server.writeHandshakeMessage(publication, HandshakeMessage.error("Invalid connection request"))
                        } catch (e: Exception) {
                            logger.error(e) { "Handshake error!" }
                        }
                        return@FragmentAssembler
                    }

                    handshake.processUdpHandshakeMessageServer(
                        server, publication, remoteIpAndPort, message, aeronDriver, false, connectionFunc, logger
                    )
                }

                override fun poll(): Int {
                    return subscription.poll(handler, 1)
                }

                override fun close() {
                    driver.close()
                }

                override val serverInfo = driver.serverInfo
            }
        } else {
            disabled("IPv6 Disabled")
        }

        logger.info { poller.serverInfo }
        return poller
    }

    fun <CONNECTION : Connection> ip6Wildcard(
        aeronDriver: AeronDriver,
        config: ServerConfiguration,
        server: Server<CONNECTION>
    ): AeronPoller {
        val logger = server.logger
        val connectionFunc = server.connectionFunc
        val handshake = server.handshake

        val driver = UdpMediaDriverServerConnection(
            listenAddress = server.listenIPv6Address!!,
            publicationPort = config.publicationPort,
            subscriptionPort = config.subscriptionPort,
            streamId = AeronDriver.UDP_HANDSHAKE_STREAM_ID,
            sessionId = AeronDriver.RESERVED_SESSION_ID_INVALID,
            connectionTimeoutSec = config.connectionCloseTimeoutInSeconds
        )

        driver.buildServer(aeronDriver, logger)

        val publication = driver.publication
        val subscription = driver.subscription

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
                // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!

                // The sessionId is unique within a Subscription and unique across all Publication's from a sourceIdentity.
                // for the handshake, the sessionId IS NOT GLOBALLY UNIQUE
                val sessionId = header.sessionId()

                // note: this address will ALWAYS be an IP:PORT combo  OR  it will be aeron:ipc  (if IPC, it will be a different handler!)
                val remoteIpAndPort = (header.context() as Image).sourceIdentity()

                val message = server.readHandshakeMessage(buffer, offset, length, header)

                // VALIDATE:: a Registration object is the only acceptable message during the connection phase
                if (message !is HandshakeMessage) {
                    logger.error {
                        // split
                        val splitPoint = remoteIpAndPort.lastIndexOf(':')
                        val clientAddressString = remoteIpAndPort.substring(0, splitPoint)
                        "[$sessionId] Connection from $clientAddressString not allowed! Invalid connection request"
                    }

                    try {
                        server.writeHandshakeMessage(publication, HandshakeMessage.error("Invalid connection request"))
                    } catch (e: Exception) {
                        logger.error(e) { "Handshake error!" }
                    }
                    return@FragmentAssembler
                }

                handshake.processUdpHandshakeMessageServer(
                    server, publication, remoteIpAndPort, message, aeronDriver, true, connectionFunc, logger
                )
            }

            override fun poll(): Int {
                return subscription.poll(handler, 1)
            }

            override fun close() {
                driver.close()
            }

            override val serverInfo = driver.serverInfo
        }

        logger.info { poller.serverInfo }
        return poller
    }
}
