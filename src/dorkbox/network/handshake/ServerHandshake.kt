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
package dorkbox.network.handshake

import dorkbox.network.Server
import dorkbox.network.ServerConfiguration
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.AeronDriver.Companion.sessionIdAllocator
import dorkbox.network.aeron.AeronDriver.Companion.streamIdAllocator
import dorkbox.network.aeron.mediaDriver.ServerIpcConnectionDriver
import dorkbox.network.aeron.mediaDriver.ServerUdpConnectionDriver
import dorkbox.network.aeron.mediaDriver.ServerUdpHandshakeDriver
import dorkbox.network.connection.Connection
import dorkbox.network.connection.ConnectionParams
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.EventDispatcher
import dorkbox.network.connection.EventDispatcher.Companion.EVENT
import dorkbox.network.connection.Handshaker
import dorkbox.network.connection.ListenerManager
import dorkbox.network.connection.PublicKeyValidationState
import dorkbox.network.exceptions.AllocationException
import dorkbox.util.sync.CountDownLatch
import io.aeron.Publication
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
    val aeronDriver: AeronDriver
) {


    // note: the expire time here is a LITTLE longer than the expire time in the client, this way we can adjust for network lag if it's close
    private val pendingConnections = ExpiringMap.builder()
        .apply {
            // connections are extremely difficult to diagnose when the connection timeout is short
            val timeUnit = if (EndPoint.DEBUG_CONNECTIONS) { TimeUnit.HOURS } else { TimeUnit.NANOSECONDS }

            // we MUST include the publication linger timeout, otherwise we might encounter problems that are NOT REALLY problems
            this.expiration(TimeUnit.SECONDS.toNanos(config.connectionCloseTimeoutInSeconds.toLong() * 2) + aeronDriver.getLingerNs(), timeUnit)
        }
        .expirationPolicy(ExpirationPolicy.CREATED)
        .expirationListener<Long, CONNECTION> { clientConnectKey, connection ->
            // this blocks until it fully runs (which is ok. this is fast)
            logger.error { "[${clientConnectKey} Connection (${connection.id}) Timed out waiting for registration response from client" }
            EventDispatcher.launch(EVENT.CLOSE) {
                connection.close(enableRemove = true)
            }
        }
        .build<Long, CONNECTION>()


    private val connectionsPerIpCounts = ConnectionCounts()

    /**
     * @return true if we should continue parsing the incoming message, false if we should abort (as we are DONE processing data)
     */
    // note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD. ONLY RESPONSES ARE ON ACTION DISPATCH!
    suspend fun validateMessageTypeAndDoPending(
        server: Server<CONNECTION>,
        handshaker: Handshaker<CONNECTION>,
        handshakePublication: Publication,
        message: HandshakeMessage,
        logger: KLogger): Boolean {

        // check to see if this sessionId is ALREADY in use by another connection!
        // this can happen if there are multiple connections from the SAME ip address (ie: localhost)
        if (message.state == HandshakeMessage.HELLO) {
            // this should be null.

            val existingConnection = pendingConnections[message.connectKey]
            if (existingConnection != null) {
                // Server is the "source", client mirrors the server

                // WHOOPS! tell the client that it needs to retry, since a DIFFERENT client has a handshake in progress with the same sessionId
                logger.error { "[$existingConnection] (${message.connectKey}) Connection had an in-use session ID! Telling client to retry." }

                try {
                    handshaker.writeMessage(handshakePublication,
                                            existingConnection.details,
                                            HandshakeMessage.retry("Handshake already in progress for sessionID!"))
                } catch (e: Error) {
                    logger.error(e) { "[${existingConnection.details}] Handshake error!" }
                }
                return false
            }
        }

        // check to see if this is a pending connection
        if (message.state == HandshakeMessage.DONE) {
            val existingConnection = pendingConnections.remove(message.connectKey)
            if (existingConnection == null) {
                logger.error { "[?????] (${message.connectKey}) Error! Pending connection from client was null, and cannot complete handshake!" }
                return true
            }


            // Server is the "source", client mirrors the server
            logger.debug { "[${existingConnection.details}] (${message.connectKey}) Connection done with handshake." }

            // NOTE: This is called on connection.close()
            existingConnection.closeAction = {
                // clean up the resources associated with this connection when it's closed
                logger.debug { "[${existingConnection.details}] freeing resources" }
                existingConnection.cleanup(connectionsPerIpCounts)

                // this always has to be on event dispatch, otherwise we can have weird logic loops if we reconnect within a disconnect callback
                EventDispatcher.launch(EVENT.DISCONNECT) {
                    existingConnection.doNotifyDisconnect()
                    listenerManager.notifyDisconnect(existingConnection)
                }
            }

            // before we finish creating the connection, we initialize it (in case there needs to be logic that happens-before `onConnect` calls occur
            EventDispatcher.launch(EVENT.INIT) {
                listenerManager.notifyInit(existingConnection)
            }

            // this enables the connection to start polling for messages
            server.addConnection(existingConnection)

            // now tell the client we are done
            try {
                handshaker.writeMessage(handshakePublication, existingConnection.details,
                                        HandshakeMessage.doneToClient(message.connectKey))

                // this always has to be on event dispatch, otherwise we can have weird logic loops if we reconnect within a disconnect callback
                EventDispatcher.launch(EVENT.CONNECT) {
                    listenerManager.notifyConnect(existingConnection)
                }
            } catch (e: Exception) {
                logger.error(e) { "[${existingConnection.details}] Handshake error!" }
            }

            return false
        }

        return true
    }

    /**
     * @return true if we should continue parsing the incoming message, false if we should abort
     */
    // note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
    private suspend fun validateUdpConnectionInfo(
        server: Server<CONNECTION>,
        handshaker: Handshaker<CONNECTION>,
        handshakePublication: Publication,
        config: ServerConfiguration,
        clientAddress: InetAddress,
        aeronLogInfo: String,
        logger: KLogger
    ): Boolean {

        try {
            // VALIDATE:: Check to see if there are already too many clients connected.
            if (server.connections.size() >= config.maxClientCount) {
                logger.error("[$aeronLogInfo] Connection not allowed! Server is full. Max allowed is ${config.maxClientCount}")

                try {
                    handshaker.writeMessage(handshakePublication, aeronLogInfo,
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

                logger.error { "[$aeronLogInfo] Too many connections for IP address. Max allowed is ${config.maxConnectionsPerIpAddress}" }

                try {
                    handshaker.writeMessage(handshakePublication, aeronLogInfo,
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
                handshaker.writeMessage(handshakePublication, aeronLogInfo,
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
    suspend fun processIpcHandshakeMessageServer(
        server: Server<CONNECTION>,
        handshaker: Handshaker<CONNECTION>,
        aeronDriver: AeronDriver,
        handshakePublication: Publication,
        message: HandshakeMessage,
        aeronLogInfo: String,
        connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION,
        logger: KLogger
    ) {
        val serialization = config.serialization

        /////
        /////
        ///// DONE WITH VALIDATION
        /////
        /////


        // allocate session/stream id's
        val connectionSessionIdPub: Int
        try {
            connectionSessionIdPub = sessionIdAllocator.allocate()
        } catch (e: AllocationException) {
            logger.error { "[$aeronLogInfo] Connection not allowed! Unable to allocate a session pub ID for the client connection!" }

            try {
                handshaker.writeMessage(handshakePublication, aeronLogInfo,
                                        HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                logger.error(e) { "[$aeronLogInfo] Handshake error!" }
            }
            return
        }

        val connectionSessionIdSub: Int
        try {
            connectionSessionIdSub = sessionIdAllocator.allocate()
        } catch (e: AllocationException) {
            // have to unwind actions!
            sessionIdAllocator.free(connectionSessionIdPub)

            logger.error { "[$aeronLogInfo] Connection not allowed! Unable to allocate a session sub ID for the client connection!" }

            try {
                handshaker.writeMessage(handshakePublication, aeronLogInfo,
                                        HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                logger.error(e) { "[$aeronLogInfo] Handshake error!" }
            }
            return
        }


        val connectionStreamIdPub: Int
        try {
            connectionStreamIdPub = streamIdAllocator.allocate()
        } catch (e: AllocationException) {
            // have to unwind actions!
            sessionIdAllocator.free(connectionSessionIdPub)
            sessionIdAllocator.free(connectionSessionIdSub)

            logger.error { "[$aeronLogInfo] Connection not allowed! Unable to allocate a stream publication ID for the client connection!" }

            try {
                handshaker.writeMessage(handshakePublication, aeronLogInfo,
                                        HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                logger.error(e) { "[$aeronLogInfo] Handshake error!" }
            }
            return
        }

        val connectionStreamIdSub: Int
        try {
            connectionStreamIdSub = streamIdAllocator.allocate()
        } catch (e: AllocationException) {
            // have to unwind actions!
            sessionIdAllocator.free(connectionSessionIdPub)
            sessionIdAllocator.free(connectionSessionIdSub)
            streamIdAllocator.free(connectionStreamIdPub)

            logger.error { "[$aeronLogInfo] Connection not allowed! Unable to allocate a stream subscription ID for the client connection!" }

            try {
                handshaker.writeMessage(handshakePublication, aeronLogInfo,
                                        HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                logger.error(e) { "[$aeronLogInfo] Handshake error!" }
            }
            return
        }





        // create a new connection. The session ID is encrypted.
        var connection: CONNECTION? = null
        try {
            // Create a pub/sub at the given address and port, using the given stream ID.
            val newConnectionDriver = ServerIpcConnectionDriver(
                aeronDriver = aeronDriver,
                sessionIdPub = connectionSessionIdPub,
                sessionIdSub = connectionSessionIdSub,
                streamIdPub = connectionStreamIdPub,
                streamIdSub = connectionStreamIdSub,
                logInfo = "IPC",
                isReliable = true,
                logger = logger
            )

            connection = connectionFunc(ConnectionParams(server, newConnectionDriver.connectionInfo(), PublicKeyValidationState.VALID))

            // VALIDATE:: are we allowed to connect to this server (now that we have the initial server information)
            // NOTE: all IPC client connections are, by default, always allowed to connect, because they are running on the same machine


            ///////////////
            ///  HANDSHAKE
            ///////////////


            // The one-time pad is used to encrypt the session ID, so that ONLY the correct client knows what it is!
            val successMessage = HandshakeMessage.helloAckIpcToClient(message.connectKey)


            // Also send the RMI registration data to the client (so the client doesn't register anything)

            // now create the encrypted payload, using no crypto
            successMessage.registrationData = server.crypto.nocrypt(
                connectionSessionIdPub,
                connectionSessionIdSub,
                connectionStreamIdPub,
                connectionStreamIdSub,
                serialization.getKryoRegistrationDetails())

            successMessage.publicKey = server.crypto.publicKeyBytes

            // before we notify connect, we have to wait for the client to tell us that they can receive data
            pendingConnections[message.connectKey] = connection

            logger.debug { "[$aeronLogInfo] (${message.connectKey}) Connection (${connection.id}) responding to handshake hello." }

            // this tells the client all the info to connect.
            handshaker.writeMessage(handshakePublication, aeronLogInfo, successMessage) // exception is already caught!
        } catch (e: Exception) {
            // have to unwind actions!
            sessionIdAllocator.free(connectionSessionIdPub)
            sessionIdAllocator.free(connectionSessionIdSub)
            streamIdAllocator.free(connectionStreamIdSub)
            streamIdAllocator.free(connectionStreamIdPub)

            logger.error(e) { "[$aeronLogInfo] (${message.connectKey}) Connection (${connection?.id}) handshake crashed! Message $message" }
        }
    }

    /**
     * note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
     * @return true if the handshake poller is to close the publication, false will keep the publication
     */
    suspend fun processUdpHandshakeMessageServer(
        server: Server<CONNECTION>,
        handshaker: Handshaker<CONNECTION>,
        mediaDriver: ServerHandshakeDriver,
        handshakePublication: Publication,
        clientAddress: InetAddress,
        clientAddressString: String,
        isReliable: Boolean,
        message: HandshakeMessage,
        aeronLogInfo: String,
        connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION,
        logger: KLogger
    ) {
        val serialization = config.serialization

        // UDP ONLY
        val clientPublicKeyBytes = message.publicKey
        val validateRemoteAddress: PublicKeyValidationState

        // VALIDATE:: check to see if the remote connection's public key has changed!
        validateRemoteAddress = server.crypto.validateRemoteAddress(clientAddress, clientAddressString, clientPublicKeyBytes)
        if (validateRemoteAddress == PublicKeyValidationState.INVALID) {
            logger.error { "[$aeronLogInfo] Connection not allowed! Public key mismatch." }
            return
        }

        val isSelfMachine = clientAddress.isLoopbackAddress || clientAddress == EndPoint.lanAddress

        if (!isSelfMachine &&
            !validateUdpConnectionInfo(server, handshaker, handshakePublication, config, clientAddress, aeronLogInfo, logger)) {
            // we do not want to limit the loopback addresses!
            return
        }


        /////
        /////
        ///// DONE WITH VALIDATION
        /////
        /////


        // allocate session/stream id's
        val connectionSessionIdPub: Int
        try {
            connectionSessionIdPub = sessionIdAllocator.allocate()
        } catch (e: AllocationException) {
            // have to unwind actions!
            connectionsPerIpCounts.decrementSlow(clientAddress)

            logger.error { "[$aeronLogInfo] Connection not allowed! Unable to allocate a session ID for the client connection!" }

            try {
                handshaker.writeMessage(handshakePublication, aeronLogInfo,
                                        HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                logger.error(e) { "[$aeronLogInfo] Handshake error!" }
            }
            return
        }


        val connectionSessionIdSub: Int
        try {
            connectionSessionIdSub = sessionIdAllocator.allocate()
        } catch (e: AllocationException) {
            // have to unwind actions!
            connectionsPerIpCounts.decrementSlow(clientAddress)
            sessionIdAllocator.free(connectionSessionIdPub)

            logger.error { "[$aeronLogInfo] Connection not allowed! Unable to allocate a session ID for the client connection!" }

            try {
                handshaker.writeMessage(handshakePublication, aeronLogInfo,
                                        HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                logger.error(e) { "[$aeronLogInfo] Handshake error!" }
            }
            return
        }


        val connectionStreamIdPub: Int
        try {
            connectionStreamIdPub = streamIdAllocator.allocate()
        } catch (e: AllocationException) {
            // have to unwind actions!
            connectionsPerIpCounts.decrementSlow(clientAddress)
            sessionIdAllocator.free(connectionSessionIdPub)
            sessionIdAllocator.free(connectionSessionIdSub)

            logger.error { "[$aeronLogInfo] Connection not allowed! Unable to allocate a stream ID for the client connection!" }

            try {
                handshaker.writeMessage(handshakePublication, aeronLogInfo,
                                        HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                logger.error(e) { "[$aeronLogInfo] Handshake error!" }
            }
            return
        }

        val connectionStreamIdSub: Int
        try {
            connectionStreamIdSub = streamIdAllocator.allocate()
        } catch (e: AllocationException) {
            // have to unwind actions!
            connectionsPerIpCounts.decrementSlow(clientAddress)
            sessionIdAllocator.free(connectionSessionIdPub)
            sessionIdAllocator.free(connectionSessionIdSub)
            streamIdAllocator.free(connectionStreamIdPub)

            logger.error { "[$aeronLogInfo] Connection not allowed! Unable to allocate a stream ID for the client connection!" }

            try {
                handshaker.writeMessage(handshakePublication, aeronLogInfo,
                                        HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                logger.error(e) { "[$aeronLogInfo] Handshake error!" }
            }
            return
        }



        // the pub/sub do not necessarily have to be the same. They can be ANY port
        val portPub = message.port
        val portSub = config.port

        val logType = if (clientAddress is Inet4Address) {
            "IPv4"
        } else {
            "IPv6"
        }

        // create a new connection. The session ID is encrypted.
        var connection: CONNECTION? = null
        try {
            // Create a pub/sub at the given address and port, using the given stream ID.
            val newConnectionDriver = ServerUdpConnectionDriver(
                aeronDriver = aeronDriver,
                sessionIdPub = connectionSessionIdPub,
                sessionIdSub = connectionSessionIdSub,
                streamIdPub = connectionStreamIdPub,
                streamIdSub = connectionStreamIdSub,

                listenAddress = mediaDriver.listenAddress,
                remoteAddress = clientAddress,
                remoteAddressString = clientAddressString,

                portPub = portPub,
                portSub = portSub,

                logInfo = logType,
                isReliable = isReliable,
                logger = logger
            )


            logger.error {
                "SERVER INFO:\n" +
                "sessionId PUB: $connectionSessionIdPub\n" +
                "sessionId SUB: $connectionSessionIdSub\n" +

                "streamId PUB: $connectionStreamIdPub\n" +
                "streamId SUB: $connectionStreamIdSub\n" +

                "port PUB: $portPub\n" +
                "port SUB: $portSub\n" +
                ""
            }

            connection = connectionFunc(ConnectionParams(server, newConnectionDriver.connectionInfo(), validateRemoteAddress))

            // VALIDATE:: are we allowed to connect to this server (now that we have the initial server information)
            val permitConnection = listenerManager.notifyFilter(connection)
            if (!permitConnection) {
                // have to unwind actions!
                connectionsPerIpCounts.decrementSlow(clientAddress)
                sessionIdAllocator.free(connectionSessionIdPub)
                sessionIdAllocator.free(connectionSessionIdSub)
                streamIdAllocator.free(connectionStreamIdPub)
                streamIdAllocator.free(connectionStreamIdSub)

                EventDispatcher.launch(EVENT.CLOSE) {
                    connection.close(enableRemove = true)
                }

                logger.error { "[$aeronLogInfo] Connection was not permitted!" }

                try {
                    handshaker.writeMessage(handshakePublication, aeronLogInfo,
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
            successMessage.registrationData = server.crypto.encrypt(
                clientPublicKeyBytes = clientPublicKeyBytes!!,
                sessionIdPub = connectionSessionIdPub,
                sessionIdSub = connectionSessionIdSub,
                streamIdPub = connectionStreamIdPub,
                streamIdSub = connectionStreamIdSub,
                kryoRegDetails = serialization.getKryoRegistrationDetails()
            )

            successMessage.publicKey = server.crypto.publicKeyBytes

            // before we notify connect, we have to wait for the client to tell us that they can receive data
            pendingConnections[message.connectKey] = connection

            logger.debug { "[$aeronLogInfo] (${message.connectKey}) Connection (${connection.id}) responding to handshake hello." }

            // this tells the client all the info to connect.
            handshaker.writeMessage(handshakePublication, aeronLogInfo, successMessage) // exception is already caught
        } catch (e: Exception) {
            // have to unwind actions!
            connectionsPerIpCounts.decrementSlow(clientAddress)
            sessionIdAllocator.free(connectionSessionIdPub)
            sessionIdAllocator.free(connectionSessionIdSub)
            streamIdAllocator.free(connectionStreamIdPub)
            streamIdAllocator.free(connectionStreamIdSub)

            logger.error(e) { "[$aeronLogInfo] (${message.connectKey}) Connection (${connection?.id}) handshake crashed! Message $message" }
        }
    }

    /**
     * Validates that all the resources have been freed (for all connections)
     *
     * note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
     */
    fun checkForMemoryLeaks() {
        val noAllocations = connectionsPerIpCounts.isEmpty()

        if (!noAllocations) {
            throw AllocationException("Unequal allocate/free method calls for validation. \n" +
                                      "connectionsPerIpCounts: '$connectionsPerIpCounts'")

        }
    }

    /**
     * Reset and clear all connection information
     *
     * note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
     */
    suspend fun clear() {
        val connections = pendingConnections
        val latch = CountDownLatch(connections.size)

        EventDispatcher.launch(EVENT.CLOSE) {
            connections.forEach { (_, v) ->
                v.close(enableRemove = true)
                latch.countDown()
            }
        }

        latch.await(config.connectionCloseTimeoutInSeconds.toLong() * connections.size)
        connections.clear()
    }
}
