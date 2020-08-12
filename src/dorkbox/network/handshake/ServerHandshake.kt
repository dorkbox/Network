package dorkbox.network.handshake

import dorkbox.netUtil.IPv4
import dorkbox.network.ServerConfiguration
import dorkbox.network.aeron.server.*
import dorkbox.network.connection.*
import io.aeron.Image
import io.aeron.Publication
import io.aeron.logbuffer.Header
import kotlinx.coroutines.launch
import mu.KLogger
import org.agrona.DirectBuffer
import org.agrona.collections.Int2IntCounterMap
import org.agrona.collections.Int2ObjectHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write



/**
 * TODO: when adding a "custom" connection, it's super important to not have to worry about the sessionID (which is what we key off of)
 *
 * @throws IllegalArgumentException If the port range is not valid
 */
internal class ServerHandshake<CONNECTION : Connection>(logger: KLogger,
                                                        config: ServerConfiguration,
                                                        listenerManager: ListenerManager<CONNECTION>) :
        ConnectionManager<CONNECTION>(logger, config, listenerManager) {

    companion object {
        // this is the number of ports used per client. Depending on how a client is configured, this number can change
        const val portsPerClient = 2
    }

    private val pendingConnectionsLock = ReentrantReadWriteLock()
    private val pendingConnections = Int2ObjectHashMap<CONNECTION>()


    private val portAllocator: PortAllocator
    private val connectionsPerIpCounts = Int2IntCounterMap(0)

    // guarantee that session/stream ID's will ALWAYS be unique! (there can NEVER be a collision!)
    private val sessionIdAllocator = RandomIdAllocator(EndPoint.RESERVED_SESSION_ID_LOW,
                                                       EndPoint.RESERVED_SESSION_ID_HIGH)
    private val streamIdAllocator = RandomIdAllocator(1, Integer.MAX_VALUE)

    init {
        val minPort = config.clientStartPort
        val maxPortCount = portsPerClient * config.maxClientCount
        portAllocator = PortAllocator(minPort, maxPortCount)

        logger.info("Server connection port range [$minPort - ${minPort + maxPortCount}")
    }


    // note: this is called in action dispatch
    suspend fun receiveHandshakeMessageServer(handshakePublication: Publication,
                                              buffer: DirectBuffer, offset: Int, length: Int, header: Header,
                                              endPoint: EndPoint<CONNECTION>) {
        // The sessionId is unique within a Subscription and unique across all Publication's from a sourceIdentity.
        // ONLY for the handshake, the sessionId IS NOT GLOBALLY UNIQUE
        val sessionId = header.sessionId()

        // note: this address will ALWAYS be an IP:PORT combo
        val remoteIpAndPort = (header.context() as Image).sourceIdentity()

        // split
        val splitPoint = remoteIpAndPort.lastIndexOf(':')
        val clientAddressString = remoteIpAndPort.substring(0, splitPoint)
//        val port = remoteIpAndPort.substring(splitPoint+1)
        val clientAddress = IPv4.toInt(clientAddressString)

        config as ServerConfiguration

        val message = endPoint.readHandshakeMessage(buffer, offset, length, header)

        // VALIDATE:: a Registration object is the only acceptable message during the connection phase
        if (message !is Message) {
            listenerManager.notifyError(ClientRejectedException("Connection from $clientAddressString not allowed! Invalid connection request"))
            endPoint.writeHandshakeMessage(handshakePublication, Message.error("Invalid connection request"))
            return
        }

        val clientPublicKeyBytes = message.publicKey

        // check to see if this is a pending connection
        if (message.state == Message.DONE) {
            pendingConnectionsLock.write {
                val pendingConnection = pendingConnections.remove(sessionId)
                if (pendingConnection != null) {
                    logger.debug("Connection from client $clientAddressString ready")

                    // now tell the client we are done
                    endPoint.writeHandshakeMessage(handshakePublication, Message.doneToClient(sessionId))

                    endPoint.actionDispatch.launch {
                        listenerManager.notifyConnect(pendingConnection)
                    }
                    return
                }
            }
        }


        try {
            // VALIDATE:: Check to see if there are already too many clients connected.
            if (connectionCount() >= config.maxClientCount) {
                listenerManager.notifyError(ClientRejectedException("Connection from $clientAddressString not allowed! Server is full"))
                endPoint.writeHandshakeMessage(handshakePublication, Message.error("Server full. Max allowed is ${config.maxClientCount}"))
                return
            }

            // VALIDATE:: check to see if the remote connection's public key has changed!
            if (!endPoint.crypto.validateRemoteAddress(clientAddress, clientPublicKeyBytes)) {
                listenerManager.notifyError(ClientRejectedException("Connection from $clientAddressString not allowed! Public key mismatch."))
                return
            }

            // VALIDATE:: make sure the serialization matches between the client/server!
            if (!config.serialization.verifyKryoRegistration(message.registrationData!!)) {
                listenerManager.notifyError(ClientRejectedException("Connection from $clientAddressString not allowed! Registration data mismatch."))
                return
            }

            // VALIDATE:: we are now connected to the client and are going to create a new connection.
            val currentCountForIp = connectionsPerIpCounts.getAndIncrement(clientAddress)
            if (currentCountForIp >= config.maxConnectionsPerIpAddress) {
                listenerManager.notifyError(ClientRejectedException("Too many connections for IP address $clientAddressString"))

                // decrement it now, since we aren't going to permit this connection (take the hit on failure, instead
                connectionsPerIpCounts.getAndDecrement(clientAddress)
                endPoint.writeHandshakeMessage(handshakePublication, Message.error("too many connections for IP address. Max allowed is ${config.maxConnectionsPerIpAddress}"))
                return
            }
        } catch (e: Exception) {
            listenerManager.notifyError(ClientRejectedException("could not validate client message",
                                                                e))
            endPoint.writeHandshakeMessage(handshakePublication, Message.error("Invalid connection"))
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
            listenerManager.notifyError(ClientRejectedException("Connection from $clientAddressString not allowed! Unable to allocate $portsPerClient ports for client connection!"))
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

            listenerManager.notifyError(ClientRejectedException("Connection from $clientAddressString not allowed! Unable to allocate a session ID for the client connection!"))
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

            listenerManager.notifyError(ClientRejectedException("Connection from $clientAddressString not allowed! Unable to allocate a stream ID for the client connection!"))
            return
        }

        val serverAddress = config.listenIpAddress // TODO :: my IP address?? this should be the IP of the box?
        val subscriptionPort = connectionPorts[0]
        val publicationPort = connectionPorts[1]


        // create a new connection. The session ID is encrypted.
        try {
            // connection timeout of 0 doesn't matter. it is not used by the server
            val clientConnection = UdpMediaDriverConnection(serverAddress,
                                                            subscriptionPort,
                                                            publicationPort,
                                                            connectionStreamId,
                                                            connectionSessionId,
                                                            0,
                                                            message.isReliable)

            val connection: Connection = endPoint.newConnection(endPoint, clientConnection)

            // VALIDATE:: are we allowed to connect to this server (now that we have the initial server information)
            @Suppress("UNCHECKED_CAST")
            val permitConnection = listenerManager.notifyFilter(connection as CONNECTION)
            if (!permitConnection) {
                // have to unwind actions!
                connectionsPerIpCounts.getAndDecrement(clientAddress)
                portAllocator.free(connectionPorts)
                sessionIdAllocator.free(connectionSessionId)
                streamIdAllocator.free(connectionStreamId)

                logger.error("Error creating new connection")

                listenerManager.notifyError(connection,
                                            ClientRejectedException("Connection was not permitted!"))
                return
            }

            logger.info {
                "Client connected [$clientAddressString:$subscriptionPort|$publicationPort] (session: $sessionId)"
            }

            logger.debug("Created new client connection sessionID {}", connectionSessionId)

            // The one-time pad is used to encrypt the session ID, so that ONLY the correct client knows what it is!
            val successMessage = Message.helloAckToClient(sessionId)

            // now create the encrypted payload, using ECDH
            successMessage.registrationData = endPoint.crypto.encrypt(publicationPort,
                                                                      subscriptionPort,
                                                                      connectionSessionId,
                                                                      connectionStreamId,
                                                                      clientPublicKeyBytes!!)

            successMessage.publicKey = endPoint.crypto.publicKeyBytes

            // this tells the client all of the info to connect.
            endPoint.writeHandshakeMessage(handshakePublication, successMessage)

            addConnection(connection)

            // before we notify connect, we have to wait for the client to tell us that they can receive data
            pendingConnectionsLock.write {
                pendingConnections[sessionId] = connection
            }
        } catch (e: Exception) {
            // have to unwind actions!
            connectionsPerIpCounts.getAndDecrement(clientAddress)
            portAllocator.free(connectionPorts)
            sessionIdAllocator.free(connectionSessionId)
            streamIdAllocator.free(connectionStreamId)

            listenerManager.notifyError(ServerException("Connection handshake from $clientAddressString crashed! Message $message", e))
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

            listenerManager.notifyDisconnect(connectionToClean)
            connectionToClean.close()
        })

        return pollCount
    }
}
