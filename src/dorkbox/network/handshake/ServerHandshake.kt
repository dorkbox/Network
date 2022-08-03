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
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.mediaDriver.MediaDriverConnectInfo
import dorkbox.network.aeron.mediaDriver.MediaDriverConnection
import dorkbox.network.aeron.mediaDriver.ServerIpcDriver
import dorkbox.network.aeron.mediaDriver.UdpMediaDriverPairedConnection
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
internal class ServerHandshake<CONNECTION : Connection>(
    private val logger: KLogger,
    private val config: ServerConfiguration,
    private val listenerManager: ListenerManager<CONNECTION>,
    aeronDriver: AeronDriver
) {

    // note: the expire time here is a LITTLE longer than the expire time in the client, this way we can adjust for network lag if it's close
    private val pendingConnections = ExpiringMap.builder()
        // we MUST include the publication linger timeout, otherwise we might encounter problems that are NOT REALLY problems
        .expiration(TimeUnit.SECONDS.toNanos(config.connectionCloseTimeoutInSeconds.toLong() * 2) + aeronDriver.getLingerNs(), TimeUnit.NANOSECONDS)
        .expirationPolicy(ExpirationPolicy.CREATED)
        .expirationListener<Long, CONNECTION> { clientConnectKey, connection ->
            // this blocks until it fully runs (which is ok. this is fast)
            logger.error { "[${clientConnectKey} Connection (${connection.id}) Timed out waiting for registration response from client" }
            connection.close()
        }
        .build<Long, CONNECTION>()


    private val connectionsPerIpCounts = ConnectionCounts()

    // guarantee that session/stream ID's will ALWAYS be unique! (there can NEVER be a collision!)
    private val sessionIdAllocator = RandomId65kAllocator(AeronDriver.RESERVED_SESSION_ID_LOW, AeronDriver.RESERVED_SESSION_ID_HIGH)
    private val streamIdAllocator = RandomId65kAllocator(1, Integer.MAX_VALUE)


    /**
     * @return true if we should continue parsing the incoming message, false if we should abort (as we are DONE processing data)
     */
    // note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD. ONLY RESPONSES ARE ON ACTION DISPATCH!
    private fun validateMessageTypeAndDoPending(
        server: Server<CONNECTION>,
        actionDispatch: CoroutineScope,
        handshakePublication: Publication,
        message: HandshakeMessage,
        connectionString: String,
        aeronLogInfo: String,
        logger: KLogger
    ): Boolean {
        // check to see if this sessionId is ALREADY in use by another connection!
        // this can happen if there are multiple connections from the SAME ip address (ie: localhost)
        if (message.state == HandshakeMessage.HELLO) {
            // this should be null.

            val existingConnection = pendingConnections[message.connectKey]
            if (existingConnection != null) {
                val existingAeronLogInfo = "${existingConnection.id}/${existingConnection.streamId}"

                // WHOOPS! tell the client that it needs to retry, since a DIFFERENT client has a handshake in progress with the same sessionId
                logger.error { "[$existingAeronLogInfo - ${message.connectKey}] Connection from $connectionString had an in-use session ID! Telling client to retry." }

                try {
                    server.writeHandshakeMessage(handshakePublication, aeronLogInfo, HandshakeMessage.retry("Handshake already in progress for sessionID!"))
                } catch (e: Error) {
                    logger.error(e) { "[$aeronLogInfo - $existingAeronLogInfo] Handshake error!" }
                }
                return false
            }
        }

        // check to see if this is a pending connection
        if (message.state == HandshakeMessage.DONE) {
            val existingConnection = pendingConnections.remove(message.connectKey)
            if (existingConnection == null) {
                logger.error { "[$aeronLogInfo - ${message.connectKey}] Error! Pending connection from client $connectionString was null, and cannot complete handshake!" }
                return true
            } else {
                val existingAeronLogInfo = "${existingConnection.id}/${existingConnection.streamId}"

                logger.debug { "[$aeronLogInfo - $existingAeronLogInfo - ${message.connectKey}] Connection from $connectionString done with handshake." }

                // called on connection.close()
                existingConnection.closeAction = {
                    // clean up the resources associated with this connection when it's closed
                    logger.debug { "[$existingAeronLogInfo] freeing resources" }
                    existingConnection.cleanup(connectionsPerIpCounts, sessionIdAllocator, streamIdAllocator)

                    // this always has to be on event dispatch, otherwise we can have weird logic loops if we reconnect within a disconnect callback
                    actionDispatch.launch {
                        existingConnection.doNotifyDisconnect()
                        listenerManager.notifyDisconnect(existingConnection)
                    }
                }

                // before we finish creating the connection, we initialize it (in case there needs to be logic that happens-before `onConnect` calls occur
                listenerManager.notifyInit(existingConnection)

                // this enables the connection to start polling for messages
                server.addConnection(existingConnection)

                // now tell the client we are done
                try {
                    server.writeHandshakeMessage(handshakePublication, aeronLogInfo,
                                                 HandshakeMessage.doneToClient(message.connectKey))

                    // this always has to be on event dispatch, otherwise we can have weird logic loops if we reconnect within a disconnect callback
                    actionDispatch.launch {
                        listenerManager.notifyConnect(existingConnection)
                    }
                } catch (e: Exception) {
                    logger.error(e) { "$aeronLogInfo - $existingAeronLogInfo - Handshake error!" }
                }

                return false
            }
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
        aeronLogInfo: String,
        logger: KLogger
    ): Boolean {

        try {
            // VALIDATE:: Check to see if there are already too many clients connected.
            if (server.connections.connectionCount() >= config.maxClientCount) {
                logger.error("[$aeronLogInfo] Connection from $clientAddressString not allowed! Server is full. Max allowed is ${config.maxClientCount}")

                try {
                    server.writeHandshakeMessage(handshakePublication, aeronLogInfo,
                                                 HandshakeMessage.error("Server is full"))
                } catch (e: Exception) {
                    logger.error(e) { "[$aeronLogInfo] Handshake error!" }
                }
                return false
            }


            // VALIDATE:: we are now connected to the client and are going to create a new connection.
            val currentCountForIp = connectionsPerIpCounts.get(clientAddress)
            if (currentCountForIp >= config.maxConnectionsPerIpAddress) {
                // decrement it now, since we aren't going to permit this connection (take the extra decrement hit on failure, instead of always)
                connectionsPerIpCounts.decrement(clientAddress, currentCountForIp)

                logger.error { "[$aeronLogInfo] Too many connections for IP address $clientAddressString. Max allowed is ${config.maxConnectionsPerIpAddress}" }

                try {
                    server.writeHandshakeMessage(handshakePublication, aeronLogInfo,
                                                 HandshakeMessage.error("Too many connections for IP address"))
                } catch (e: Exception) {
                    logger.error(e) { "[$aeronLogInfo] Handshake error!" }
                }
                return false
            }
            connectionsPerIpCounts.increment(clientAddress, currentCountForIp)
        } catch (e: Exception) {
            logger.error(e) { "[$aeronLogInfo] Could not validate client message" }

            try {
                server.writeHandshakeMessage(handshakePublication, aeronLogInfo,
                                             HandshakeMessage.error("Invalid connection"))
            } catch (e: Exception) {
                logger.error(e) { "[$aeronLogInfo] Handshake error!" }
            }
        }

        return true
    }


    /**
     * @return true if the handshake poller is to close the publication, false will keep the publication (as we are DONE processing data)
     */
    fun processIpcHandshakeMessageServer(
        server: Server<CONNECTION>,
        handshakePublication: Publication,
        message: HandshakeMessage,
        aeronDriver: AeronDriver,
        aeronLogInfo: String,
        connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION,
        logger: KLogger
    ) {

        val connectionString = "IPC"

        if (!validateMessageTypeAndDoPending(
                server,
                server.actionDispatch,
                handshakePublication,
                message,
                connectionString,
                aeronLogInfo,
                logger
            )) {
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
            logger.error { "[$aeronLogInfo] Connection from $connectionString not allowed! Unable to allocate a session ID for the client connection!" }

            try {
                server.writeHandshakeMessage(handshakePublication, aeronLogInfo,
                                             HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                logger.error(e) { "[$aeronLogInfo] Handshake error!" }
            }
            return
        }


        val connectionStreamPubId: Int
        try {
            connectionStreamPubId = streamIdAllocator.allocate()
        } catch (e: AllocationException) {
            // have to unwind actions!
            sessionIdAllocator.free(connectionSessionId)

            logger.error { "[$aeronLogInfo] Connection from $connectionString not allowed! Unable to allocate a stream ID for the client connection!" }

            try {
                server.writeHandshakeMessage(handshakePublication, aeronLogInfo,
                                             HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                logger.error(e) { "[$aeronLogInfo] Handshake error!" }
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

            logger.error { "[$aeronLogInfo] Connection from $connectionString not allowed! Unable to allocate a stream ID for the client connection!" }

            try {
                server.writeHandshakeMessage(handshakePublication, aeronLogInfo,
                                             HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                logger.error(e) { "[$aeronLogInfo] Handshake error!" }
            }
            return
        }


        // create a new connection. The session ID is encrypted.
        try {
            // Create a subscription at the given address and port, using the given stream ID.
            val driver = ServerIpcDriver(streamId = connectionStreamSubId,
                                         sessionId = connectionSessionId)
            driver.build(aeronDriver, logger)

            // create a new publication for the connection (since the handshake ALWAYS closes the current publication)
            val publicationUri = MediaDriverConnection.uri("ipc", handshakePublication.sessionId())
            val clientPublication = aeronDriver.addPublication(publicationUri, message.subscriptionPort)

            val clientConnection = MediaDriverConnectInfo(
                publication = clientPublication,
                subscription = driver.subscription,
                subscriptionPort = driver.streamId,
                publicationPort = message.subscriptionPort,
                streamId = 0, // this is because with IPC, we have stream sub/pub (which are replaced as port sub/pub)
                sessionId = driver.sessionId,
                isReliable = driver.isReliable,
                remoteAddress = null,
                remoteAddressString = "ipc"
            )


            logger.info { "[$aeronLogInfo] Creating new IPC connection from $driver" }

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

            val regDetails = serialization.getKryoRegistrationDetails()
            cryptOutput.writeInt(regDetails.size)
            cryptOutput.writeBytes(regDetails)

            successMessage.registrationData = cryptOutput.toBytes()

            successMessage.publicKey = server.crypto.publicKeyBytes

            // before we notify connect, we have to wait for the client to tell us that they can receive data
            pendingConnections[message.connectKey] = connection

            // this tells the client all the info to connect.
            server.writeHandshakeMessage(handshakePublication, aeronLogInfo, successMessage) // exception is already caught!
        } catch (e: Exception) {
            // have to unwind actions!
            sessionIdAllocator.free(connectionSessionId)
            streamIdAllocator.free(connectionStreamPubId)

            logger.error(e) { "[$aeronLogInfo] Connection handshake from $connectionString crashed! Message $message" }
        }
    }

    /**
     * note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
     * @return true if the handshake poller is to close the publication, false will keep the publication
     */
    fun processUdpHandshakeMessageServer(
        server: Server<CONNECTION>,
        handshakePublication: Publication,
        clientAddress: InetAddress,
        clientAddressString: String,
        isReliable: Boolean,
        message: HandshakeMessage,
        aeronDriver: AeronDriver,
        aeronLogInfo: String,
        isIpv6Wildcard: Boolean,
        connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION,
        logger: KLogger
    ) {
        // Manage the Handshake state
        if (!validateMessageTypeAndDoPending(
                server, server.actionDispatch, handshakePublication, message,
                clientAddressString, aeronLogInfo, logger))
        {
            return
        }

        val clientPublicKeyBytes = message.publicKey
        val validateRemoteAddress: PublicKeyValidationState
        val serialization = config.serialization

        // VALIDATE:: check to see if the remote connection's public key has changed!
        validateRemoteAddress = server.crypto.validateRemoteAddress(clientAddress, clientAddressString, clientPublicKeyBytes)
        if (validateRemoteAddress == PublicKeyValidationState.INVALID) {
            logger.error { "[$aeronLogInfo] Connection from $clientAddressString not allowed! Public key mismatch." }
            return
        }


        if (!clientAddress.isLoopbackAddress &&
            !validateUdpConnectionInfo(server, handshakePublication, config, clientAddressString, clientAddress, aeronLogInfo, logger)) {
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

            logger.error { "[$aeronLogInfo] Connection from $clientAddressString not allowed! Unable to allocate a session ID for the client connection!" }

            try {
                server.writeHandshakeMessage(handshakePublication, aeronLogInfo,
                                             HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                logger.error(e) { "[$aeronLogInfo] Handshake error!" }
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

            logger.error { "[$aeronLogInfo] Connection from $clientAddressString not allowed! Unable to allocate a stream ID for the client connection!" }

            try {
                server.writeHandshakeMessage(handshakePublication, aeronLogInfo,
                                             HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                logger.error(e) { "[$aeronLogInfo] Handshake error!" }
            }
            return
        }

        // the pub/sub do not necessarily have to be the same. They can be ANY port
        val publicationPort = message.subscriptionPort
        val subscriptionPort = config.port


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

            // create a new publication for the connection (since the handshake ALWAYS closes the current publication)
            val publicationUri = MediaDriverConnection.uriEndpoint("udp", message.sessionId, isReliable, clientAddress, clientAddressString, message.subscriptionPort)
            val clientPublication = aeronDriver.addPublication(publicationUri, message.streamId)

            val driver = UdpMediaDriverPairedConnection(
                listenAddress,
                clientAddress,
                clientAddressString,
                publicationPort,
                subscriptionPort,
                connectionStreamId,
                connectionSessionId,
                0,
                isReliable,
                clientPublication
            )

            driver.build(aeronDriver, logger)
            logger.info { "[$aeronLogInfo] Creating new connection from $driver" }

            val clientConnection = MediaDriverConnectInfo(
                publication = driver.publication,
                subscription = driver.subscription,
                subscriptionPort = driver.port,
                publicationPort = publicationPort,
                streamId = driver.streamId,
                sessionId = driver.sessionId,
                isReliable = driver.isReliable,
                remoteAddress = clientAddress,
                remoteAddressString = clientAddressString
            )

            connection = connectionFunc(ConnectionParams(server, clientConnection, validateRemoteAddress))

            // VALIDATE:: are we allowed to connect to this server (now that we have the initial server information)
            val permitConnection = listenerManager.notifyFilter(connection)
            if (!permitConnection) {
                // have to unwind actions!
                connectionsPerIpCounts.decrementSlow(clientAddress)
                sessionIdAllocator.free(connectionSessionId)
                streamIdAllocator.free(connectionStreamId)
                connection.close()

                logger.error { "[$aeronLogInfo] Connection $clientAddressString was not permitted!" }

                try {
                    server.writeHandshakeMessage(handshakePublication, aeronLogInfo,
                                                 HandshakeMessage.error("Connection was not permitted!"))
                } catch (e: Exception) {
                    logger.error(e) { "[$aeronLogInfo] Handshake error!" }
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
                                                                    subscriptionPort,
                                                                    connectionSessionId,
                                                                    connectionStreamId,
                                                                    serialization.getKryoRegistrationDetails())

            successMessage.publicKey = server.crypto.publicKeyBytes

            // before we notify connect, we have to wait for the client to tell us that they can receive data
            pendingConnections[message.connectKey] = connection

            logger.debug { "[$aeronLogInfo - ${message.connectKey}] Connection (${connection.streamId}/${connection.id}) responding to handshake hello." }

            // this tells the client all the info to connect.
            server.writeHandshakeMessage(handshakePublication, aeronLogInfo, successMessage) // exception is already caught
        } catch (e: Exception) {
            // have to unwind actions!
            connectionsPerIpCounts.decrementSlow(clientAddress)
            sessionIdAllocator.free(connectionSessionId)
            streamIdAllocator.free(connectionStreamId)

            logger.error(e) { "[$aeronLogInfo - ${message.connectKey}] Connection (${connection?.streamId}/${connection?.id}) handshake from $clientAddressString crashed! Message $message" }
        }
    }

    /**
     * Validates that all the resources have been freed (for all connections)
     *
     * note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
     */
    fun checkForMemoryLeaks() {
        val noAllocations = connectionsPerIpCounts.isEmpty() && sessionIdAllocator.isEmpty() && streamIdAllocator.isEmpty()

        if (!noAllocations) {
            throw AllocationException("Unequal allocate/free method calls for validation. \n" +
                                      "connectionsPerIpCounts: '$connectionsPerIpCounts' \n" +
                                      "sessionIdAllocator: $sessionIdAllocator \n" +
                                      "streamIdAllocator: $streamIdAllocator")

        }
    }

    /**
     * Reset and clear all connection information
     *
     * note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
     */
    fun clear() {
        runBlocking {
            pendingConnections.forEach { (_, v) ->
                v.close()
            }

            pendingConnections.clear()
        }
    }
}
