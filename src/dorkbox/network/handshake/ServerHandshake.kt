/*
 * Copyright 2020 dorkbox, llc
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
package dorkbox.network.handshake

import dorkbox.network.Server
import dorkbox.network.ServerConfiguration
import dorkbox.network.aeron.client.ClientRejectedException
import dorkbox.network.aeron.server.AllocationException
import dorkbox.network.aeron.server.RandomIdAllocator
import dorkbox.network.aeron.server.ServerException
import dorkbox.network.connection.Connection
import dorkbox.network.connection.ConnectionParams
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.ListenerManager
import dorkbox.network.connection.PublicKeyValidationState
import dorkbox.network.connection.UdpMediaDriverConnection
import io.aeron.Aeron
import io.aeron.Publication
import kotlinx.coroutines.launch
import mu.KLogger
import org.agrona.collections.Int2IntCounterMap
import org.agrona.collections.Int2ObjectHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write


/**
 * @throws IllegalArgumentException If the port range is not valid
 */
internal class ServerHandshake<CONNECTION : Connection>(private val logger: KLogger,
                                                        private val config: ServerConfiguration,
                                                        private val listenerManager: ListenerManager<CONNECTION>) {

    private val pendingConnectionsLock = ReentrantReadWriteLock()
    private val pendingConnections = Int2ObjectHashMap<CONNECTION>()

    private val connectionsPerIpCounts = Int2IntCounterMap(0)

    // guarantee that session/stream ID's will ALWAYS be unique! (there can NEVER be a collision!)
    private val sessionIdAllocator = RandomIdAllocator(EndPoint.RESERVED_SESSION_ID_LOW,
                                                       EndPoint.RESERVED_SESSION_ID_HIGH)
    private val streamIdAllocator = RandomIdAllocator(1, Integer.MAX_VALUE)

    // note: this is called in action dispatch
    suspend fun processHandshakeMessageServer(handshakePublication: Publication,
                                              sessionId: Int,
                                              clientAddressString: String,
                                              clientAddress: Int,
                                              message: Any?,
                                              server: Server<CONNECTION>,
                                              aeron: Aeron) {

        // VALIDATE:: a Registration object is the only acceptable message during the connection phase
        if (message !is HandshakeMessage) {
            listenerManager.notifyError(ClientRejectedException("Connection from $clientAddressString not allowed! Invalid connection request"))
            server.writeHandshakeMessage(handshakePublication, HandshakeMessage.error("Invalid connection request"))
            return
        }

        val clientPublicKeyBytes = message.publicKey
        val validateRemoteAddress: PublicKeyValidationState


        // check to see if this is a pending connection
        if (message.state == HandshakeMessage.DONE) {
            pendingConnectionsLock.write {
                val pendingConnection = pendingConnections.remove(sessionId)
                if (pendingConnection != null) {
                    logger.debug("Connection from client $clientAddressString ready")

                    // now tell the client we are done
                    server.writeHandshakeMessage(handshakePublication, HandshakeMessage.doneToClient(sessionId))

                    server.actionDispatch.launch {
                        listenerManager.notifyConnect(pendingConnection)
                    }

                    // this enables the connection to start polling for messages
                    server.connections.add(pendingConnection)

                    return
                }
            }
        }


        val serialization = config.serialization

        try {
            // VALIDATE:: Check to see if there are already too many clients connected.
            if (server.connections.connectionCount() >= config.maxClientCount) {
                listenerManager.notifyError(ClientRejectedException("Connection from $clientAddressString not allowed! Server is full. Max allowed is ${config.maxClientCount}"))

                server.writeHandshakeMessage(handshakePublication, HandshakeMessage.error("Server is full"))
                return
            }

            // VALIDATE:: check to see if the remote connection's public key has changed!
            validateRemoteAddress = server.crypto.validateRemoteAddress(clientAddress, clientPublicKeyBytes)
            if (validateRemoteAddress == PublicKeyValidationState.INVALID) {
                listenerManager.notifyError(ClientRejectedException("Connection from $clientAddressString not allowed! Public key mismatch."))
                return
            }

            // VALIDATE:: make sure the serialization matches between the client/server!
            if (!serialization.verifyKryoRegistration(message.registrationData!!)) {
                listenerManager.notifyError(ClientRejectedException("Connection from $clientAddressString not allowed! Registration data mismatch."))
                return
            }

            // VALIDATE:: we are now connected to the client and are going to create a new connection.
            val currentCountForIp = connectionsPerIpCounts.getAndIncrement(clientAddress)
            if (currentCountForIp >= config.maxConnectionsPerIpAddress) {
                listenerManager.notifyError(ClientRejectedException("Too many connections for IP address $clientAddressString. Max allowed is ${config.maxConnectionsPerIpAddress}"))

                // decrement it now, since we aren't going to permit this connection (take the extra decrement hit on failure, instead of always)
                connectionsPerIpCounts.getAndDecrement(clientAddress)
                server.writeHandshakeMessage(handshakePublication,
                                             HandshakeMessage.error("Too many connections for IP address"))
                return
            }
        } catch (e: Exception) {
            listenerManager.notifyError(ClientRejectedException("could not validate client message", e))
            server.writeHandshakeMessage(handshakePublication, HandshakeMessage.error("Invalid connection"))
            return
        }



        /////
        /////
        ///// DONE WITH VALIDATION
        /////
        /////


        // allocate session/stream id's
        val connectionSessionId: Int
        try {
            connectionSessionId = sessionIdAllocator.allocate()
        } catch (e: AllocationException) {
            // have to unwind actions!
            connectionsPerIpCounts.getAndDecrement(clientAddress)

            listenerManager.notifyError(ClientRejectedException("Connection from $clientAddressString not allowed! Unable to allocate a session ID for the client connection!"))

            server.writeHandshakeMessage(handshakePublication,
                                         HandshakeMessage.error("Connection error!"))
            return
        }


        val connectionStreamId: Int
        try {
            connectionStreamId = streamIdAllocator.allocate()
        } catch (e: AllocationException) {
            // have to unwind actions!
            connectionsPerIpCounts.getAndDecrement(clientAddress)
            sessionIdAllocator.free(connectionSessionId)

            listenerManager.notifyError(ClientRejectedException("Connection from $clientAddressString not allowed! Unable to allocate a stream ID for the client connection!"))

            server.writeHandshakeMessage(handshakePublication,
                                         HandshakeMessage.error("Connection error!"))
            return
        }

        val serverAddress = config.listenIpAddress // TODO :: my IP address?? this should be the IP of the box?

        // the pub/sub do not necessarily have to be the same. The can be ANY port
        val publicationPort = config.publicationPort
        val subscriptionPort = config.subscriptionPort


        // create a new connection. The session ID is encrypted.
        try {
            // connection timeout of 0 doesn't matter. it is not used by the server
            val clientConnection = UdpMediaDriverConnection(serverAddress,
                                                            publicationPort,
                                                            subscriptionPort,
                                                            connectionStreamId,
                                                            connectionSessionId,
                                                            0,
                                                            message.isReliable)

            // we have to construct how the connection will communicate!
            clientConnection.buildServer(aeron)

            logger.trace {
                "Creating new connection $clientConnection"
            }

            val connection = server.newConnection(ConnectionParams(server, clientConnection, validateRemoteAddress)) as CONNECTION

            // VALIDATE:: are we allowed to connect to this server (now that we have the initial server information)
            @Suppress("UNCHECKED_CAST")
            val permitConnection = listenerManager.notifyFilter(connection)
            if (!permitConnection) {
                // have to unwind actions!
                connectionsPerIpCounts.getAndDecrement(clientAddress)
                sessionIdAllocator.free(connectionSessionId)
                streamIdAllocator.free(connectionStreamId)

                logger.error("Error creating new connection")

                val exception = ClientRejectedException("Connection was not permitted!")
                ListenerManager.cleanStackTrace(exception)
                listenerManager.notifyError(connection, exception)

                server.writeHandshakeMessage(handshakePublication,
                                             HandshakeMessage.error("Connection was not permitted!"))
                return
            }


            ///////////////
            ////   RMI
            ///////////////

            // if necessary (and only for RMI id's that have never been seen before) we want to re-write our kryo information
            val rmiModificationIds = message.registrationRmiIdData!!
            server.updateKryoIdsForRmi(connection, rmiModificationIds)



            ///////////////
            ///  HANDSHAKE
            ///////////////



            // The one-time pad is used to encrypt the session ID, so that ONLY the correct client knows what it is!
            val successMessage = HandshakeMessage.helloAckToClient(sessionId)


            // if necessary, we also send the kryo RMI id's that are registered as RMI on this endpoint, but maybe not on the other endpoint

            // now create the encrypted payload, using ECDH
            successMessage.registrationData = server.crypto.encrypt(clientPublicKeyBytes!!,
                                                                    publicationPort,
                                                                    subscriptionPort,
                                                                    connectionSessionId,
                                                                    connectionStreamId,
                                                                    serialization.getKryoRmiIds())

            successMessage.publicKey = server.crypto.publicKeyBytes

            // before we notify connect, we have to wait for the client to tell us that they can receive data
            pendingConnectionsLock.write {
                pendingConnections[sessionId] = connection
            }

            // this tells the client all of the info to connect.
            server.writeHandshakeMessage(handshakePublication, successMessage)
        } catch (e: Exception) {
            // have to unwind actions!
            connectionsPerIpCounts.getAndDecrement(clientAddress)
            sessionIdAllocator.free(connectionSessionId)
            streamIdAllocator.free(connectionStreamId)

            listenerManager.notifyError(ServerException("Connection handshake from $clientAddressString crashed! Message $message", e))
        }
    }

    /**
     * Free up resources from the closed connection
     */
    fun cleanup(connection: CONNECTION) {
        connectionsPerIpCounts.getAndDecrement(connection.remoteAddressInt)
        sessionIdAllocator.free(connection.sessionId)
        streamIdAllocator.free(connection.streamId)
    }
}
