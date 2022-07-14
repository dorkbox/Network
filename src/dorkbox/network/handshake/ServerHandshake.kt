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

import dorkbox.netUtil.IP
import dorkbox.network.Server
import dorkbox.network.ServerConfiguration
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.IpcMediaDriverConnection
import dorkbox.network.aeron.UdpMediaDriverPairedConnection
import dorkbox.network.connection.Connection
import dorkbox.network.connection.ConnectionParams
import dorkbox.network.connection.ListenerManager
import dorkbox.network.connection.PublicKeyValidationState
import dorkbox.network.exceptions.AllocationException
import io.aeron.Publication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KLogger
import net.jodah.expiringmap.ExpirationPolicy
import net.jodah.expiringmap.ExpiringMap
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.*


/**
 * 'notifyConnect' must be THE ONLY THING in this class to use the action dispatch!
 *
 * NOTE: all methods in here are called by the SAME thread!
 */
@Suppress("DuplicatedCode", "JoinDeclarationAndAssignment")
internal class ServerHandshake<CONNECTION : Connection>(private val logger: KLogger,
                                                        private val config: ServerConfiguration,
                                                        private val listenerManager: ListenerManager<CONNECTION>) {

    // note: the expire time here is a LITTLE longer than the expire time in the client, this way we can adjust for network lag if it's close
    private val pendingConnections = ExpiringMap.builder()
        .expiration(config.connectionCloseTimeoutInSeconds.toLong() * 2, TimeUnit.SECONDS)
        .expirationPolicy(ExpirationPolicy.CREATED)
        .expirationListener<Long, CONNECTION> { clientConnectKey, connection ->
            // this blocks until it fully runs (which is ok. this is fast)
            logger.error { "[${clientConnectKey} Connection (${connection.id}) Timed out waiting for registration response from client" }
            connection.closeBlocking()
        }
        .build<Long, CONNECTION>()


    private val connectionsPerIpCounts = ConnectionCounts()

    // guarantee that session/stream ID's will ALWAYS be unique! (there can NEVER be a collision!)
    private val sessionIdAllocator = RandomId65kAllocator(AeronDriver.RESERVED_SESSION_ID_LOW, AeronDriver.RESERVED_SESSION_ID_HIGH)
    private val streamIdAllocator = RandomId65kAllocator(1, Integer.MAX_VALUE)

    /**
     * @return true if we should continue parsing the incoming message, false if we should abort
     */
    // note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD. ONLY RESPONSES ARE ON ACTION DISPATCH!
    private fun validateMessageTypeAndDoPending(
        server: Server<CONNECTION>,
        actionDispatch: CoroutineScope,
        handshakePublication: Publication,
        message: HandshakeMessage,
        connectionString: String,
        logger: KLogger
    ): Boolean {
        // check to see if this sessionId is ALREADY in use by another connection!
        // this can happen if there are multiple connections from the SAME ip address (ie: localhost)
        if (message.state == HandshakeMessage.HELLO) {
            // this should be null.
            val hasExistingConnectionInProgress = pendingConnections[message.connectKey] != null
            if (hasExistingConnectionInProgress) {
                // WHOOPS! tell the client that it needs to retry, since a DIFFERENT client has a handshake in progress with the same sessionId
                logger.error { "[${message.connectKey}] Connection from $connectionString had an in-use session ID! Telling client to retry." }

                try {
                    server.writeHandshakeMessage(handshakePublication, HandshakeMessage.retry("Handshake already in progress for sessionID!"))
                } catch (e: Error) {
                    logger.error(e) { "Handshake error!" }
                }
                return false
            }

            return true
        }

        // check to see if this is a pending connection
        if (message.state == HandshakeMessage.DONE) {
            val pendingConnection = pendingConnections.remove(message.connectKey)
            if (pendingConnection == null) {
                logger.error { "[${message.connectKey}] Error! Pending connection from client $connectionString was null, and cannot complete handshake!" }
            } else {
                logger.debug { "[${message.connectKey}] Connection (${pendingConnection.id}) from $connectionString done with handshake." }

                pendingConnection.closeAction = {
                    // called on connection.close()
                    pendingConnection.doNotifyDisconnect()
                }

                // before we finish creating the connection, we initialize it (in case there needs to be logic that happens-before `onConnect` calls occur
                runBlocking {
                    listenerManager.notifyInit(pendingConnection)
                }

                // this enables the connection to start polling for messages
                server.addConnection(pendingConnection)

                // now tell the client we are done
                try {
                    server.writeHandshakeMessage(handshakePublication, HandshakeMessage.doneToClient(message.connectKey))

                    // this always has to be on event dispatch, otherwise we can have weird logic loops if we reconnect within a disconnect callback
                    actionDispatch.launch {
                        listenerManager.notifyConnect(pendingConnection)
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Handshake error!" }
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
    private fun validateUdpConnectionInfo(
        server: Server<CONNECTION>,
        handshakePublication: Publication,
        config: ServerConfiguration,
        clientAddressString: String,
        clientAddress: InetAddress,
        logger: KLogger
    ): Boolean {

        try {
            // VALIDATE:: Check to see if there are already too many clients connected.
            if (server.connections.connectionCount() >= config.maxClientCount) {
                logger.error("Connection from $clientAddressString not allowed! Server is full. Max allowed is ${config.maxClientCount}")

                try {
                    server.writeHandshakeMessage(handshakePublication, HandshakeMessage.error("Server is full"))
                } catch (e: Exception) {
                    logger.error(e) { "Handshake error!" }
                }
                return false
            }


            // VALIDATE:: we are now connected to the client and are going to create a new connection.
            val currentCountForIp = connectionsPerIpCounts.get(clientAddress)
            if (currentCountForIp >= config.maxConnectionsPerIpAddress) {
                // decrement it now, since we aren't going to permit this connection (take the extra decrement hit on failure, instead of always)
                connectionsPerIpCounts.decrement(clientAddress, currentCountForIp)

                logger.error { "Too many connections for IP address $clientAddressString. Max allowed is ${config.maxConnectionsPerIpAddress}" }

                try {
                    server.writeHandshakeMessage(handshakePublication, HandshakeMessage.error("Too many connections for IP address"))
                } catch (e: Exception) {
                    logger.error(e) { "Handshake error!" }
                }
                return false
            }
            connectionsPerIpCounts.increment(clientAddress, currentCountForIp)
        } catch (e: Exception) {
            logger.error(e) { "could not validate client message" }

            try {
                server.writeHandshakeMessage(handshakePublication, HandshakeMessage.error("Invalid connection"))
            } catch (e: Exception) {
                logger.error(e) { "Handshake error!" }
            }
        }

        return true
    }


    // note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
    fun processIpcHandshakeMessageServer(
        server: Server<CONNECTION>,
        handshakePublication: Publication,
        message: HandshakeMessage,
        aeronDriver: AeronDriver,
        connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION,
        logger: KLogger
    ) {

        val connectionString = "IPC"

        if (!validateMessageTypeAndDoPending(server, server.actionDispatch, handshakePublication, message, connectionString, logger)) {
            return
        }

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
            logger.error { "Connection from $connectionString not allowed! Unable to allocate a session ID for the client connection!" }

            try {
                server.writeHandshakeMessage(handshakePublication, HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                logger.error(e) { "Handshake error!" }
            }
            return
        }


        val connectionStreamPubId: Int
        try {
            connectionStreamPubId = streamIdAllocator.allocate()
        } catch (e: AllocationException) {
            // have to unwind actions!
            sessionIdAllocator.free(connectionSessionId)

            logger.error { "Connection from $connectionString not allowed! Unable to allocate a stream ID for the client connection!" }

            try {
                server.writeHandshakeMessage(handshakePublication, HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                logger.error(e) { "Handshake error!" }
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

            logger.error { "Connection from $connectionString not allowed! Unable to allocate a stream ID for the client connection!" }

            try {
                server.writeHandshakeMessage(handshakePublication, HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                logger.error(e) { "Handshake error!" }
            }
            return
        }


        // create a new connection. The session ID is encrypted.
        try {
            val clientConnection = IpcMediaDriverConnection(streamId = connectionStreamPubId,
                                                            streamIdSubscription = connectionStreamSubId,
                                                            sessionId = connectionSessionId)

            // we have to construct how the connection will communicate!
            clientConnection.buildServer(aeronDriver, logger, true)

            logger.info { "[${clientConnection.sessionId}] IPC connection established to [${clientConnection.streamIdSubscription}|${clientConnection.streamId}]" }

            val connection = connectionFunc(ConnectionParams(server, clientConnection, PublicKeyValidationState.VALID))

            // VALIDATE:: are we allowed to connect to this server (now that we have the initial server information)
            // NOTE: all IPC client connections are, by default, always allowed to connect, because they are running on the same machine


            ///////////////
            ///  HANDSHAKE
            ///////////////



            // The one-time pad is used to encrypt the session ID, so that ONLY the correct client knows what it is!
            val successMessage = HandshakeMessage.helloAckIpcToClient(message.connectKey)


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
            pendingConnections[message.connectKey] = connection

            // this tells the client all the info to connect.
            server.writeHandshakeMessage(handshakePublication, successMessage) // exception is already caught!
        } catch (e: Exception) {
            // have to unwind actions!
            sessionIdAllocator.free(connectionSessionId)
            streamIdAllocator.free(connectionStreamPubId)

            logger.error(e) { "Connection handshake from $connectionString crashed! Message $message" }
        }
    }

    // note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
    fun processUdpHandshakeMessageServer(
        server: Server<CONNECTION>,
        handshakePublication: Publication,
        remoteIpAndPort: String,
        message: HandshakeMessage,
        aeronDriver: AeronDriver,
        isIpv6Wildcard: Boolean,
        connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION,
        logger: KLogger
    ) {

        // split
        val splitPoint = remoteIpAndPort.lastIndexOf(':')
        val clientAddressString = remoteIpAndPort.substring(0, splitPoint)
        // val port = remoteIpAndPort.substring(splitPoint+1)

        // this should never be null, because we are feeding it a valid IP address from aeron
        val clientAddress = IP.toAddress(clientAddressString)
        if (clientAddress == null) {
            logger.error { "Connection from $clientAddressString not allowed! Invalid IP address!" }
            return
        }



        // Manage the Handshake state
        if (!validateMessageTypeAndDoPending(server, server.actionDispatch, handshakePublication, message, clientAddressString, logger)) {
            return
        }



        val clientPublicKeyBytes = message.publicKey
        val validateRemoteAddress: PublicKeyValidationState
        val serialization = config.serialization

        // VALIDATE:: check to see if the remote connection's public key has changed!
        validateRemoteAddress = server.crypto.validateRemoteAddress(clientAddress, clientAddressString, clientPublicKeyBytes)
        if (validateRemoteAddress == PublicKeyValidationState.INVALID) {
            logger.error { "Connection from $clientAddressString not allowed! Public key mismatch." }
            return
        }


        if (!clientAddress.isLoopbackAddress &&
            !validateUdpConnectionInfo(server, handshakePublication, config, clientAddressString, clientAddress, logger)) {
            // we do not want to limit the loopback addresses!
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

            logger.error { "Connection from $clientAddressString not allowed! Unable to allocate a session ID for the client connection!" }

            try {
                server.writeHandshakeMessage(handshakePublication, HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                logger.error(e) { "Handshake error!" }
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

            logger.error { "Connection from $clientAddressString not allowed! Unable to allocate a stream ID for the client connection!" }

            try {
                server.writeHandshakeMessage(handshakePublication, HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                logger.error(e) { "Handshake error!" }
            }
            return
        }

        // the pub/sub do not necessarily have to be the same. They can be ANY port
        val publicationPort = config.publicationPort
        val subscriptionPort = config.subscriptionPort


        // create a new connection. The session ID is encrypted.
        var connection: CONNECTION? = null
        try {
            // connection timeout of 0 doesn't matter. it is not used by the server
            // the client address WILL BE either IPv4 or IPv6
            val listenAddress = if (clientAddress is Inet4Address && !isIpv6Wildcard) {
                server.listenIPv4Address!!
            } else {
                // wildcard is SPECIAL, in that if we bind wildcard, it will ALSO bind to IPv4, so we can't bind both!
                server.listenIPv6Address!!
            }

            val clientConnection = UdpMediaDriverPairedConnection(listenAddress,
                                                                  clientAddress,
                                                                  clientAddressString,
                                                                  publicationPort,
                                                                  subscriptionPort,
                                                                  connectionStreamId,
                                                                  connectionSessionId,
                                                                  0,
                                                                  message.isReliable)

            // we have to construct how the connection will communicate!
            clientConnection.buildServer(aeronDriver, logger, true)

            logger.info { "Creating new connection from $clientConnection" }

            connection = connectionFunc(ConnectionParams(server, clientConnection, validateRemoteAddress))

            // VALIDATE:: are we allowed to connect to this server (now that we have the initial server information)
            val permitConnection = listenerManager.notifyFilter(connection)
            if (!permitConnection) {
                // have to unwind actions!
                connectionsPerIpCounts.decrementSlow(clientAddress)
                sessionIdAllocator.free(connectionSessionId)
                streamIdAllocator.free(connectionStreamId)
                connection.closeBlocking()

                logger.error { "Connection $clientAddressString was not permitted!" }

                try {
                    server.writeHandshakeMessage(handshakePublication, HandshakeMessage.error("Connection was not permitted!"))
                } catch (e: Exception) {
                    logger.error(e) { "Handshake error!" }
                }
                return
            }


            ///////////////
            ///  HANDSHAKE
            ///////////////


            // The one-time pad is used to encrypt the session ID, so that ONLY the correct client knows what it is!
            val successMessage = HandshakeMessage.helloAckToClient(message.connectKey)


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
            pendingConnections[message.connectKey] = connection

            logger.debug { "[${message.connectKey}] Connection (${connection.id}) responding to handshake hello." }

            // this tells the client all the info to connect.
            server.writeHandshakeMessage(handshakePublication, successMessage) // exception is already caught
        } catch (e: Exception) {
            // have to unwind actions!
            connectionsPerIpCounts.decrementSlow(clientAddress)
            sessionIdAllocator.free(connectionSessionId)
            streamIdAllocator.free(connectionStreamId)

            logger.error(e) { "[${message.connectKey}] Connection (${connection?.id}) handshake from $clientAddressString crashed! Message $message" }
        }
    }

    /**
     * Free up resources from the closed connection
     */
    fun freeResources(connection: CONNECTION) {
        // note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
        logger.debug { "[${connection.id}] Freeing resources" }

        connection.cleanup(connectionsPerIpCounts, sessionIdAllocator, streamIdAllocator)
    }


    /**
     * Validates that all the resources have been freed (for all connections)
     */
    fun checkForMemoryLeaks() {
        val noAllocations = connectionsPerIpCounts.isEmpty() && sessionIdAllocator.isEmpty() && streamIdAllocator.isEmpty()

        if (!noAllocations) {
            throw AllocationException("Unequal allocate/free method calls for validation.")
        }
    }

    /**
     * Reset and clear all connection information
     */
    fun clear() {
        // note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
        pendingConnections.clear()
    }
}
