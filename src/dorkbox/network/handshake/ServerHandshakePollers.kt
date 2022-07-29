@file:Suppress("MemberVisibilityCanBePrivate", "DuplicatedCode")

package dorkbox.network.handshake

import dorkbox.network.Server
import dorkbox.network.ServerConfiguration
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.AeronPoller
import dorkbox.network.aeron.MediaDriverConnection
import dorkbox.network.aeron.ServerIpc_MediaDriver
import dorkbox.network.aeron.ServerUdp_MediaDriver
import dorkbox.network.connection.Connection
import dorkbox.network.connection.ConnectionParams
import io.aeron.FragmentAssembler
import io.aeron.Image
import io.aeron.logbuffer.Header
import mu.KLogger
import org.agrona.DirectBuffer

internal object ServerHandshakePollers {
    fun disabled(serverInfo: String): AeronPoller {
        return object : AeronPoller {
            override fun poll(): Int { return 0 }
            override fun close() {}
            override val info = serverInfo
        }
    }

    private fun <CONNECTION : Connection> ipcProcessing(
        logger: KLogger,
        server: Server<CONNECTION>, aeronDriver: AeronDriver,
        header: Header, buffer: DirectBuffer, offset: Int, length: Int,
        handshake: ServerHandshake<CONNECTION>, connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION
    ) {
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
            val publicationUri = MediaDriverConnection.uri("ipc", message.sessionId)
            val publication = aeronDriver.addPublication(publicationUri, message.subscriptionPort)

            handshake.processIpcHandshakeMessageServer(
                server, publication, message,
                aeronDriver, aeronLogInfo,
                connectionFunc, logger
            )

            publication.close()
        }
    }

    private fun <CONNECTION : Connection> ipProcessing(
        logger: KLogger,
        server: Server<CONNECTION>, isReliable: Boolean, aeronDriver: AeronDriver, isIpv6Wildcard: Boolean,
        header: Header, buffer: DirectBuffer, offset: Int, length: Int,
        handshake: ServerHandshake<CONNECTION>, connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION
    ) {
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
        val clientAddressString = remoteIpAndPort.substring(0, splitPoint)

        val message = server.readHandshakeMessage(buffer, offset, length, header, aeronLogInfo)

        // VALIDATE:: a Registration object is the only acceptable message during the connection phase
        if (message !is HandshakeMessage) {
            logger.error { "[$aeronLogInfo] Connection from $clientAddressString not allowed! Invalid connection request" }
        } else {
            // we create a NEW publication for the handshake, which connects directly to the client handshake subscription
            val publicationUri = MediaDriverConnection.uriEndpoint("udp", message.sessionId, isReliable, "$clientAddressString:${message.subscriptionPort}")
            val publication = aeronDriver.addPublication(publicationUri, message.streamId)

            handshake.processUdpHandshakeMessageServer(
                server, publication, remoteIpAndPort, isReliable, message,
                aeronDriver, aeronLogInfo, isIpv6Wildcard,
                connectionFunc, logger
            )

            publication.close()
        }
    }




    fun <CONNECTION : Connection> ipc(
        aeronDriver: AeronDriver, config: ServerConfiguration, server: Server<CONNECTION>, handshake: ServerHandshake<CONNECTION>
    ): AeronPoller
    {
        val logger = server.logger
        val connectionFunc = server.connectionFunc

        val poller = if (config.enableIpc) {
            val driver = ServerIpc_MediaDriver(
                streamIdSubscription = config.ipcSubscriptionId,
                streamId = config.ipcPublicationId,
                sessionId = AeronDriver.RESERVED_SESSION_ID_INVALID
            )
            driver.build(aeronDriver, logger)

            val subscription = driver.subscription

            object : AeronPoller {
                val handler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
                    ipcProcessing(logger, server, aeronDriver, header, buffer, offset, length, handshake, connectionFunc)
                }

                override fun poll(): Int {
                    return subscription.poll(handler, 1)
                }

                override fun close() {
                    subscription.close()
                }

                override val info = driver.info
            }
        } else {
            disabled("IPC Disabled")
        }

        logger.info { poller.info }
        return poller
    }



    fun <CONNECTION : Connection> ip4(
        aeronDriver: AeronDriver, config: ServerConfiguration, server: Server<CONNECTION>, handshake: ServerHandshake<CONNECTION>
    ): AeronPoller
    {
        val logger = server.logger
        val connectionFunc = server.connectionFunc
        val isReliable = config.isReliable

        val poller = if (server.canUseIPv4) {
            val driver = ServerUdp_MediaDriver(
                listenAddress = server.listenIPv4Address!!,
                subscriptionPort = config.subscriptionPort,
                streamId = AeronDriver.UDP_HANDSHAKE_STREAM_ID,
                sessionId = AeronDriver.RESERVED_SESSION_ID_INVALID,
                connectionTimeoutSec = config.connectionCloseTimeoutInSeconds,
                isReliable = isReliable
            )

            driver.build(aeronDriver, logger)

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
                    ipProcessing(logger, server, isReliable, aeronDriver, false, header, buffer, offset, length, handshake, connectionFunc)
                }

                override fun poll(): Int {
                    return subscription.poll(handler, 1)
                }

                override fun close() {
                    subscription.close()
                }

                override val info = "IPv4 ${driver.info}"
            }
        } else {
            disabled("IPv4 Disabled")
        }

        logger.info { poller.info }
        return poller
    }

    fun <CONNECTION : Connection> ip6(
        aeronDriver: AeronDriver, config: ServerConfiguration, server: Server<CONNECTION>, handshake: ServerHandshake<CONNECTION>
    ): AeronPoller
    {
        val logger = server.logger
        val connectionFunc = server.connectionFunc
        val isReliable = config.isReliable

        val poller = if (server.canUseIPv6) {
            val driver = ServerUdp_MediaDriver(
                listenAddress = server.listenIPv6Address!!,
                subscriptionPort = config.subscriptionPort,
                streamId = AeronDriver.UDP_HANDSHAKE_STREAM_ID,
                sessionId = AeronDriver.RESERVED_SESSION_ID_INVALID,
                connectionTimeoutSec = config.connectionCloseTimeoutInSeconds,
                isReliable = isReliable
            )

            driver.build(aeronDriver, logger)

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
                    ipProcessing(logger, server, isReliable, aeronDriver, false, header, buffer, offset, length, handshake, connectionFunc)
                }

                override fun poll(): Int {
                    return subscription.poll(handler, 1)
                }

                override fun close() {
                    subscription.close()
                }

                override val info = "IPv6 ${driver.info}"
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

        val driver = ServerUdp_MediaDriver(
            listenAddress = server.listenIPv6Address!!,
            subscriptionPort = config.subscriptionPort,
            streamId = AeronDriver.UDP_HANDSHAKE_STREAM_ID,
            sessionId = AeronDriver.RESERVED_SESSION_ID_INVALID,
            connectionTimeoutSec = config.connectionCloseTimeoutInSeconds,
            isReliable = isReliable
        )

        driver.build(aeronDriver, logger)

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
                ipProcessing(logger, server, isReliable, aeronDriver, true, header, buffer, offset, length, handshake, connectionFunc)
            }

            override fun poll(): Int {
                return subscription.poll(handler, 1)
            }

            override fun close() {
                subscription.close()
            }

            override val info = "IPv4+6 ${driver.info}"
        }

        logger.info { poller.info }
        return poller
    }
}
