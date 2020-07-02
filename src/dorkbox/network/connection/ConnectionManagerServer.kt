package dorkbox.network.connection

import dorkbox.network.NetworkUtil
import dorkbox.network.ServerConfiguration
import dorkbox.network.aeron.client.ClientRejectedException
import dorkbox.network.aeron.server.AllocationException
import dorkbox.network.aeron.server.PortAllocator
import dorkbox.network.aeron.server.RandomIdAllocator
import dorkbox.network.aeron.server.ServerException
import dorkbox.network.connection.registration.Registration
import io.aeron.Image
import io.aeron.Publication
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.collections.Int2IntCounterMap
import org.slf4j.Logger

/**
 * TODO: when adding a "custom" connection, it's super important to not have to worry about the sessionID (which is what we key off of)
 *
 * @throws IllegalArgumentException If the port range is not valid
 */
class ConnectionManagerServer<C : Connection>(logger: Logger,
                                              config: ServerConfiguration) : ConnectionManager<C>(logger, config) {

    companion object {
        // this is the number of ports used per client. Depending on how a client is configured, this number can change
        const val portsPerClient = 2
    }

    private val portAllocator: PortAllocator
    private val connectionsPerIpCounts = Int2IntCounterMap(0)

    // guarantee that session/stream ID's will ALWAYS be unique! (there can NEVER be a collision!)
    private val sessionIdAllocator = RandomIdAllocator(EndPoint.RESERVED_SESSION_ID_LOW, EndPoint.RESERVED_SESSION_ID_HIGH)
    private val streamIdAllocator = RandomIdAllocator(1, Integer.MAX_VALUE)

    init {
        val minPort = config.clientStartPort
        val maxPortCount = portsPerClient * config.maxClientCount
        portAllocator = PortAllocator(minPort, maxPortCount)
    }


    @Throws(ServerException::class)
    suspend fun receiveHandshakeMessageServer(handshakePublication: Publication,
                                              buffer: DirectBuffer, offset: Int, length: Int, header: Header,
                                              endPoint: EndPoint<C>) {

        // The sessionId is unique within a Subscription and unique across all Publication's from a sourceIdentity.
        val sessionId = header.sessionId()

        // note: this address will ALWAYS be an IP:PORT combo
        val remoteIpAndPort = (header.context() as Image).sourceIdentity()

        try {
            // split
            val splitPoint = remoteIpAndPort.lastIndexOf(':')
            val clientAddressString = remoteIpAndPort.substring(0, splitPoint)
//                    val port = remoteIpAndPort.substring(splitPoint+1)

            val clientAddress = NetworkUtil.IP.toInt(clientAddressString)

            // TODO: notify error if there is an exceptoin!
            val message = endPoint.readHandshakeMessage(buffer, offset, length, header)
            logger.debug("[{}] received: {}", sessionId, message)

            config as ServerConfiguration

            // VALIDATE:: a Registration object is the only acceptable message during the connection phase
            if (message !is Registration) {
                endPoint.writeMessage(handshakePublication, Registration.error("Invalid connection request"))
                return
            }

            // VALIDATE:: Check to see if there are already too many clients connected.
            if (connectionCount() >= config.maxClientCount) {
                logger.debug("server is full")
                endPoint.writeMessage(handshakePublication, Registration.error("server full. Max allowed is ${config.maxClientCount}"))
                return
            }

            // VALIDATE:: check to see if the remote connection's public key has changed!
            if (!endPoint.validateRemoteAddress(clientAddress, message.publicKey)) {
                // TODO: this should provide info to a callback
                println("connection not allowed! public key mismatch")
                return
            }

            // VALIDATE TODO: make sure the serialization matches between the client/server!


            // VALIDATE:: we are now connected to the client and are going to create a new connection.
            val currentCountForIp = connectionsPerIpCounts.getAndIncrement(clientAddress)
            if (currentCountForIp >= config.maxConnectionsPerIpAddress) {
                // decrement it now, since we aren't going to permit this connection (take the hit on failure, instead
                connectionsPerIpCounts.getAndDecrement(clientAddress)

                logger.debug("too many connections for IP address")
                endPoint.writeMessage(handshakePublication, Registration.error("too many connections for IP address. Max allowed is ${config.maxConnectionsPerIpAddress}"))
                return
            }


            // VALIDATE::  TODO: ?? check to see if this session is ALREADY connected??. It should not be!


            /////
            /////
            ///// DONE WITH VALIDATION
            /////
            /////


            // allocate ports for the client
            val connectionPorts: IntArray

            try {
                // throws exception if this is not possible
                connectionPorts = portAllocator.allocate(portsPerClient)
            } catch (e: IllegalArgumentException) {
                // have to unwind actions!
                connectionsPerIpCounts.getAndDecrement(clientAddress)

                logger.error("Unable to allocate $portsPerClient ports for client connection!")
                return
            }

            // allocate session/stream id's
            val connectionSessionId: Int
            try {
                connectionSessionId = sessionIdAllocator.allocate()
            } catch (e: AllocationException) {
                // have to unwind actions!
                connectionsPerIpCounts.getAndDecrement(clientAddress)
                portAllocator.free(connectionPorts)

                logger.error("Unable to allocate a session ID for the client connection!")
                return
            }


            val connectionStreamId: Int
            try {
                connectionStreamId = streamIdAllocator.allocate()
            } catch (e: AllocationException) {
                // have to unwind actions!
                connectionsPerIpCounts.getAndDecrement(clientAddress)
                portAllocator.free(connectionPorts)
                sessionIdAllocator.free(connectionSessionId)

                logger.error("Unable to allocate a stream ID for the client connection!")
                return
            }

            val serverAddress = config.listenIpAddress // TODO :: my IP address?? this should be the IP of the box?
            val subscriptionPort = connectionPorts[0]
            val publicationPort = connectionPorts[1]


            // create a new connection. The session ID is encrypted.
            try {
                // connection timeout of 0 doesn't matter. it is not used by the server
                val clientConnection = UdpMediaDriverConnection(
                        serverAddress, subscriptionPort, publicationPort,
                        connectionStreamId, connectionSessionId, 0, message.isReliable)

                val connection: Connection = endPoint.newConnection(endPoint, clientConnection)

                // VALIDATE:: are we allowed to connect to this server (now that we have the initial server information)
                @Suppress("UNCHECKED_CAST")
                val permitConnection = notifyFilter(connection as C)
                if (!permitConnection) {
                    // have to unwind actions!
                    connectionsPerIpCounts.getAndDecrement(clientAddress)
                    portAllocator.free(connectionPorts)
                    sessionIdAllocator.free(connectionSessionId)
                    streamIdAllocator.free(connectionStreamId)

                    logger.error("Error creating new duologue")

                    notifyError(connection, ClientRejectedException("Connection was not permitted!"))
                    return
                }

                logger.info("Client connected [$clientAddressString:$subscriptionPort|$publicationPort] (session: $sessionId")
                logger.debug("[{}] created new client connection", connectionSessionId)

                // The one-time pad is used to encrypt the session ID, so that ONLY the correct client knows what it is!
                val successMessage = Registration.helloAck(message.oneTimePad xor connectionSessionId)
                successMessage.sessionId = sessionId // has to be the same as before (the client expects this)
                successMessage.streamId = message.oneTimePad xor connectionStreamId

                successMessage.subscriptionPort = subscriptionPort
                successMessage.publicationPort = publicationPort
                successMessage.publicKey = config.settingsStore.getPublicKey()

                endPoint.writeMessage(handshakePublication, successMessage)

                addConnection(connection)
                notifyConnect(connection)
            } catch (e: Exception) {
                // have to unwind actions!
                connectionsPerIpCounts.getAndDecrement(clientAddress)
                portAllocator.free(connectionPorts)
                sessionIdAllocator.free(connectionSessionId)
                streamIdAllocator.free(connectionStreamId)

                logger.error("Error creating new duologue")

                logger.error("could not process client message: $message")
                notifyError(e)
            }
        } catch (e: Exception) {
            logger.error("could not process client message: ", e)
        }
    }


    suspend fun poll(): Int {
        // Get the current time, used to cleanup connections
        val now = System.currentTimeMillis()
        var pollCount = 0

        forEachConnectionCleanup({ connection ->
            // If the connection has either been closed, or has expired, it needs to be cleaned-up/deleted.
            var shouldCleanupConnection = false

            if (connection.isExpired(now)) {
                logger.debug("[{}] connection expired", connection.sessionId)
                shouldCleanupConnection = true
            }

            if (connection.isClosed()) {
                logger.debug("[{}] connection closed", connection.sessionId)
                shouldCleanupConnection = true
            }
            if (shouldCleanupConnection) {
                true
            }
            else {
                // Otherwise, poll the duologue for activity.
                pollCount += connection.pollSubscriptions()
                false
            }
        }, { connectionToClean ->
            logger.debug("[{}] deleted connection", connectionToClean.sessionId)

            removeConnection(connectionToClean)

            // have to free up resources!
            connectionsPerIpCounts.getAndDecrement(connectionToClean.remoteAddressInt)
            portAllocator.free(connectionToClean.subscriptionPort)
            portAllocator.free(connectionToClean.publicationPort)
            sessionIdAllocator.free(connectionToClean.sessionId)
            streamIdAllocator.free(connectionToClean.streamId)

            notifyDisconnect(connectionToClean)
        })

        return pollCount
    }
}
