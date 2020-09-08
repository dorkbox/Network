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

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalCause
import com.github.benmanes.caffeine.cache.RemovalListener
import dorkbox.network.Server
import dorkbox.network.ServerConfiguration
import dorkbox.network.aeron.IpcMediaDriverConnection
import dorkbox.network.aeron.UdpMediaDriverConnection
import dorkbox.network.connection.Connection
import dorkbox.network.connection.ConnectionParams
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.ListenerManager
import dorkbox.network.connection.PublicKeyValidationState
import dorkbox.network.exceptions.AllocationException
import dorkbox.network.exceptions.ClientRejectedException
import dorkbox.network.exceptions.ClientTimedOutException
import dorkbox.network.exceptions.ServerException
import io.aeron.Aeron
import io.aeron.Publication
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KLogger
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write


/**
 * @throws IllegalArgumentException If the port range is not valid
 */
@Suppress("DuplicatedCode")
internal class ServerHandshake<CONNECTION : Connection>(private val logger: KLogger,
                                                        private val config: ServerConfiguration,
                                                        private val listenerManager: ListenerManager<CONNECTION>) {

    private val pendingConnectionsLock = ReentrantReadWriteLock()
    private val pendingConnections: Cache<Int, CONNECTION> = Caffeine.newBuilder()
        .expireAfterAccess(config.connectionCloseTimeoutInSeconds.toLong(), TimeUnit.SECONDS)
        .removalListener(RemovalListener<Any?, Any?> { _, value, cause ->
            if (cause == RemovalCause.EXPIRED) {
                @Suppress("UNCHECKED_CAST")
                val connection = value as CONNECTION

                listenerManager.notifyError(ClientTimedOutException("[${connection.id}] Waiting for registration response from client"))
                runBlocking {
                    connection.close()
                }
            }
        }).build()

    private val connectionsPerIpCounts = ConnectionCounts()

    // guarantee that session/stream ID's will ALWAYS be unique! (there can NEVER be a collision!)
    private val sessionIdAllocator = RandomIdAllocator(EndPoint.RESERVED_SESSION_ID_LOW, EndPoint.RESERVED_SESSION_ID_HIGH)
    private val streamIdAllocator = RandomIdAllocator(1, Integer.MAX_VALUE)


    /**
     * @return true if we should continue parsing the incoming message, false if we should abort
     */
    // note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
    private fun validateMessageTypeAndDoPending(server: Server<CONNECTION>,
                                                handshakePublication: Publication,
                                                message: Any?,
                                                sessionId: Int,
                                                connectionString: String): Boolean {

        // VALIDATE:: a Registration object is the only acceptable message during the connection phase
        if (message !is HandshakeMessage) {
            listenerManager.notifyError(ClientRejectedException("[$sessionId] Connection from $connectionString not allowed! Invalid connection request"))

            server.actionDispatch.launch {
                server.writeHandshakeMessage(handshakePublication, HandshakeMessage.error("Invalid connection request"))
            }
            return false
        }

        // check to see if this is a pending connection
        if (message.state == HandshakeMessage.DONE) {
            val pendingConnection = pendingConnectionsLock.write {
                val con = pendingConnections.getIfPresent(sessionId)
                pendingConnections.invalidate(sessionId)
                con
            }

            if (pendingConnection == null) {
                logger.error { "[$sessionId] Error! Connection from client $connectionString was null, and cannot complete handshake!" }
            } else {
                logger.trace { "[${pendingConnection.id}] Connection from client $connectionString done with handshake." }

                // this enables the connection to start polling for messages
                server.connections.add(pendingConnection)

                server.actionDispatch.launch {
                    // now tell the client we are done
                    server.writeHandshakeMessage(handshakePublication, HandshakeMessage.doneToClient(sessionId))
                    listenerManager.notifyConnect(pendingConnection)
                }
            }

            return false
        }

        return true
    }

    /**
     * @return true if we should continue parsing the incoming message, false if we should abort
     */
    // note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
    private fun validateUdpConnectionInfo(server: Server<CONNECTION>,
                                          handshakePublication: Publication,
                                          config: ServerConfiguration,
                                          clientAddressString: String,
                                          clientAddress: InetAddress): Boolean {

        if (clientAddress.isLoopbackAddress) {
            // we do not want to limit loopback addresses
            return true
        }

        try {
            // VALIDATE:: Check to see if there are already too many clients connected.
            if (server.connections.connectionCount() >= config.maxClientCount) {
                listenerManager.notifyError(ClientRejectedException("Connection from $clientAddressString not allowed! Server is full. Max allowed is ${config.maxClientCount}"))

                server.actionDispatch.launch {
                    server.writeHandshakeMessage(handshakePublication, HandshakeMessage.error("Server is full"))
                }
                return false
            }


            // VALIDATE:: we are now connected to the client and are going to create a new connection.
            val currentCountForIp = connectionsPerIpCounts.get(clientAddress)
            if (currentCountForIp >= config.maxConnectionsPerIpAddress) {
                // decrement it now, since we aren't going to permit this connection (take the extra decrement hit on failure, instead of always)
                connectionsPerIpCounts.decrement(clientAddress, currentCountForIp)

                listenerManager.notifyError(ClientRejectedException("Too many connections for IP address $clientAddressString. Max allowed is ${config.maxConnectionsPerIpAddress}"))
                server.actionDispatch.launch {
                    server.writeHandshakeMessage(handshakePublication, HandshakeMessage.error("Too many connections for IP address"))
                }

                return false
            }
            connectionsPerIpCounts.increment(clientAddress, currentCountForIp)
        } catch (e: Exception) {
            listenerManager.notifyError(ClientRejectedException("could not validate client message", e))
            server.actionDispatch.launch {
                server.writeHandshakeMessage(handshakePublication, HandshakeMessage.error("Invalid connection"))
            }
            return false
        }

        return true
    }


    // note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
    fun processIpcHandshakeMessageServer(server: Server<CONNECTION>,
                                         handshakePublication: Publication,
                                         sessionId: Int,
                                         message: Any?,
                                         aeron: Aeron) {

        val connectionString = "IPC"

        if (!validateMessageTypeAndDoPending(server, handshakePublication, message, sessionId, connectionString)) {
            return
        }
        message as HandshakeMessage

        val serialization = config.serialization

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
            listenerManager.notifyError(ClientRejectedException("Connection from $connectionString not allowed! Unable to allocate a session ID for the client connection!"))
            server.actionDispatch.launch {
                server.writeHandshakeMessage(handshakePublication, HandshakeMessage.error("Connection error!"))
            }
            return
        }


        val connectionStreamPubId: Int
        try {
            connectionStreamPubId = streamIdAllocator.allocate()
        } catch (e: AllocationException) {
            // have to unwind actions!
            sessionIdAllocator.free(connectionSessionId)

            listenerManager.notifyError(ClientRejectedException("Connection from $connectionString not allowed! Unable to allocate a stream ID for the client connection!"))
            server.actionDispatch.launch {
                server.writeHandshakeMessage(handshakePublication, HandshakeMessage.error("Connection error!"))
            }
            return
        }

        val connectionStreamSubId: Int
        try {
            connectionStreamSubId = streamIdAllocator.allocate()
        } catch (e: AllocationException) {
            // have to unwind actions!
            sessionIdAllocator.free(connectionSessionId)
            sessionIdAllocator.free(connectionStreamPubId)

            listenerManager.notifyError(ClientRejectedException("Connection from $connectionString not allowed! Unable to allocate a stream ID for the client connection!"))
            server.actionDispatch.launch {
                server.writeHandshakeMessage(handshakePublication, HandshakeMessage.error("Connection error!"))
            }
            return
        }


        // create a new connection. The session ID is encrypted.
        try {
            // connection timeout of 0 doesn't matter. it is not used by the server
            val clientConnection = IpcMediaDriverConnection(streamId = connectionStreamPubId,
                                                            streamIdSubscription = connectionStreamSubId,
                                                            sessionId = connectionSessionId,
                                                            connectionTimeoutMS = 0)

            // we have to construct how the connection will communicate!
            clientConnection.buildServer(aeron, logger)

            logger.info {
                "[${clientConnection.sessionId}] aeron IPC connection established to $clientConnection"
            }

            val connection = server.newConnection(ConnectionParams(server, clientConnection, PublicKeyValidationState.VALID))

            // VALIDATE:: are we allowed to connect to this server (now that we have the initial server information)
            @Suppress("UNCHECKED_CAST")
            val permitConnection = listenerManager.notifyFilter(connection)
            if (!permitConnection) {
                // have to unwind actions!
                sessionIdAllocator.free(connectionSessionId)
                streamIdAllocator.free(connectionStreamPubId)

                val exception = ClientRejectedException("Connection was not permitted!")
                ListenerManager.cleanStackTrace(exception)
                listenerManager.notifyError(connection, exception)

                server.actionDispatch.launch {
                    server.writeHandshakeMessage(handshakePublication, HandshakeMessage.error("Connection was not permitted!"))
                }

                return
            }




            ///////////////
            ///  HANDSHAKE
            ///////////////



            // The one-time pad is used to encrypt the session ID, so that ONLY the correct client knows what it is!
            val successMessage = HandshakeMessage.helloAckIpcToClient(sessionId)


            // if necessary, we also send the kryo RMI id's that are registered as RMI on this endpoint, but maybe not on the other endpoint

            // now create the encrypted payload, using ECDH
            val cryptOutput = server.crypto.cryptOutput
            cryptOutput.reset()
            cryptOutput.writeInt(connectionSessionId)
            cryptOutput.writeInt(connectionStreamSubId)
            cryptOutput.writeInt(connectionStreamPubId)

            val regDetails = serialization.getKryoRegistrationDetails()
            cryptOutput.writeInt(regDetails.size)
            cryptOutput.writeBytes(regDetails)

            successMessage.registrationData = cryptOutput.toBytes()

            successMessage.publicKey = server.crypto.publicKeyBytes

            // before we notify connect, we have to wait for the client to tell us that they can receive data
            pendingConnectionsLock.write {
                pendingConnections.put(sessionId, connection)
            }

            // this tells the client all of the info to connect.
            server.actionDispatch.launch {
                server.writeHandshakeMessage(handshakePublication, successMessage)
            }
        } catch (e: Exception) {
            // have to unwind actions!
            sessionIdAllocator.free(connectionSessionId)
            streamIdAllocator.free(connectionStreamPubId)

            listenerManager.notifyError(ServerException("Connection handshake from $connectionString crashed! Message $message", e))
        }

    }

    // note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
    fun processUdpHandshakeMessageServer(server: Server<CONNECTION>,
                                         handshakePublication: Publication,
                                         sessionId: Int,
                                         clientAddressString: String,
                                         clientAddress: InetAddress,
                                         message: Any?,
                                         aeron: Aeron,
                                         isIpv6Wildcard: Boolean) {

        if (!validateMessageTypeAndDoPending(server, handshakePublication, message, sessionId, clientAddressString)) {
            return
        }
        message as HandshakeMessage

        val clientPublicKeyBytes = message.publicKey
        val validateRemoteAddress: PublicKeyValidationState
        val serialization = config.serialization

        // VALIDATE:: check to see if the remote connection's public key has changed!
        validateRemoteAddress = server.crypto.validateRemoteAddress(clientAddress, clientPublicKeyBytes)
        if (validateRemoteAddress == PublicKeyValidationState.INVALID) {
            listenerManager.notifyError(ClientRejectedException("Connection from $clientAddressString not allowed! Public key mismatch."))
            return
        }

        if (!validateUdpConnectionInfo(server, handshakePublication, config, clientAddressString, clientAddress)) {
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
            connectionsPerIpCounts.decrementSlow(clientAddress)

            listenerManager.notifyError(ClientRejectedException("Connection from $clientAddressString not allowed! Unable to allocate a session ID for the client connection!"))
            server.actionDispatch.launch {
                server.writeHandshakeMessage(handshakePublication, HandshakeMessage.error("Connection error!"))
            }
            return
        }


        val connectionStreamId: Int
        try {
            connectionStreamId = streamIdAllocator.allocate()
        } catch (e: AllocationException) {
            // have to unwind actions!
            connectionsPerIpCounts.decrementSlow(clientAddress)
            sessionIdAllocator.free(connectionSessionId)

            listenerManager.notifyError(ClientRejectedException("Connection from $clientAddressString not allowed! Unable to allocate a stream ID for the client connection!"))
            server.actionDispatch.launch {
                server.writeHandshakeMessage(handshakePublication, HandshakeMessage.error("Connection error!"))
            }
            return
        }

        // the pub/sub do not necessarily have to be the same. The can be ANY port
        val publicationPort = config.publicationPort
        val subscriptionPort = config.subscriptionPort


        // create a new connection. The session ID is encrypted.
        try {
            // connection timeout of 0 doesn't matter. it is not used by the server
            // the client address WILL BE either IPv4 or IPv6

            val clientConnection = if (clientAddress is Inet4Address && !isIpv6Wildcard) {
                UdpMediaDriverConnection(server.listenIPv4Address!!,
                                         publicationPort,
                                         subscriptionPort,
                                         connectionStreamId,
                                         connectionSessionId,
                                         0,
                                         message.isReliable)
            } else {
                // wildcard is SPECIAL, in that if we bind wildcard, it will ALSO bind to IPv4, so we can't bind both!
                UdpMediaDriverConnection(server.listenIPv6Address!!,
                                         publicationPort,
                                         subscriptionPort,
                                         connectionStreamId,
                                         connectionSessionId,
                                         0,
                                         message.isReliable)
            }

            // we have to construct how the connection will communicate!
            clientConnection.buildServer(aeron, logger)

            logger.info {
                "Creating new connection from $clientConnection"
            }

            val connection = server.newConnection(ConnectionParams(server, clientConnection, validateRemoteAddress))

            // VALIDATE:: are we allowed to connect to this server (now that we have the initial server information)
            @Suppress("UNCHECKED_CAST")
            val permitConnection = listenerManager.notifyFilter(connection)
            if (!permitConnection) {
                // have to unwind actions!
                connectionsPerIpCounts.decrementSlow(clientAddress)
                sessionIdAllocator.free(connectionSessionId)
                streamIdAllocator.free(connectionStreamId)

                val exception = ClientRejectedException("Connection was not permitted!")
                ListenerManager.cleanStackTrace(exception)
                listenerManager.notifyError(connection, exception)

                server.actionDispatch.launch {
                    server.writeHandshakeMessage(handshakePublication, HandshakeMessage.error("Connection was not permitted!"))
                }

                return
            }


            ///////////////
            ///  HANDSHAKE
            ///////////////



            // The one-time pad is used to encrypt the session ID, so that ONLY the correct client knows what it is!
            val successMessage = HandshakeMessage.helloAckToClient(sessionId)


            // Also send the RMI registration data to the client (so the client doesn't register anything)

            // now create the encrypted payload, using ECDH
            successMessage.registrationData = server.crypto.encrypt(clientPublicKeyBytes!!,
                                                                    publicationPort,
                                                                    subscriptionPort,
                                                                    connectionSessionId,
                                                                    connectionStreamId,
                                                                    serialization.getKryoRegistrationDetails())

            successMessage.publicKey = server.crypto.publicKeyBytes

            // before we notify connect, we have to wait for the client to tell us that they can receive data
            pendingConnectionsLock.write {
                pendingConnections.put(sessionId, connection)
            }

            // this tells the client all of the info to connect.
            server.actionDispatch.launch {
                server.writeHandshakeMessage(handshakePublication, successMessage)
            }
        } catch (e: Exception) {
            // have to unwind actions!
            connectionsPerIpCounts.decrementSlow(clientAddress)
            sessionIdAllocator.free(connectionSessionId)
            streamIdAllocator.free(connectionStreamId)

            listenerManager.notifyError(ServerException("Connection handshake from $clientAddressString crashed! Message $message", e))
        }
    }

    /**
     * Free up resources from the closed connection
     */
    fun cleanup(connection: CONNECTION) {
        // note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
        connection.cleanup(connectionsPerIpCounts, sessionIdAllocator, streamIdAllocator)
    }

    /**
     * Reset and clear all connection information
     */
    fun clear() {
        // note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
        sessionIdAllocator.clear()
        streamIdAllocator.clear()
        pendingConnections.invalidateAll()
    }
}
