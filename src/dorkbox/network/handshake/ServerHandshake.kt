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
import dorkbox.network.connection.*
import dorkbox.network.exceptions.AllocationException
import dorkbox.network.exceptions.ServerHandshakeException
import dorkbox.network.exceptions.ServerTimedoutException
import dorkbox.network.exceptions.TransmitException
import dorkbox.util.sync.CountDownLatch
import io.aeron.Publication
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
            this.expiration(TimeUnit.SECONDS.toNanos(config.connectionCloseTimeoutInSeconds.toLong() * 2) + aeronDriver.lingerNs(), timeUnit)
        }
        .expirationPolicy(ExpirationPolicy.CREATED)
        .expirationListener<Long, CONNECTION> { clientConnectKey, connection ->
            // this blocks until it fully runs (which is ok. this is fast)
            listenerManager.notifyError(ServerTimedoutException("[${clientConnectKey} Connection (${connection.id}) Timed out waiting for registration response from client"))

            runBlocking {
                connection.close()
            }
        }
        .build<Long, CONNECTION>()


    internal val connectionsPerIpCounts = ConnectionCounts()

    /**
     * how long does the initial handshake take to connect
     */
    internal var handshakeTimeoutNs: Long

    init {
        // we MUST include the publication linger timeout, otherwise we might encounter problems that are NOT REALLY problems
        var handshakeTimeoutNs = aeronDriver.publicationConnectionTimeoutNs() + aeronDriver.lingerNs()

        if (EndPoint.DEBUG_CONNECTIONS) {
            // connections are extremely difficult to diagnose when the connection timeout is short
            handshakeTimeoutNs = TimeUnit.HOURS.toNanos(1)
        }

        this.handshakeTimeoutNs = handshakeTimeoutNs
    }

    /**
     * @return true if we should continue parsing the incoming message, false if we should abort (as we are DONE processing data)
     */
    // note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD. ONLY RESPONSES ARE ON ACTION DISPATCH!
    suspend fun validateMessageTypeAndDoPending(
        server: Server<CONNECTION>,
        handshaker: Handshaker<CONNECTION>,
        handshakePublication: Publication,
        message: HandshakeMessage,
        aeronLogInfo: String,
        logger: KLogger
    ): Boolean {

        // check to see if this sessionId is ALREADY in use by another connection!
        // this can happen if there are multiple connections from the SAME ip address (ie: localhost)
        if (message.state == HandshakeMessage.HELLO) {
            // this should be null.

            val existingConnection = pendingConnections[message.connectKey]
            if (existingConnection != null) {
                // Server is the "source", client mirrors the server

                // WHOOPS! tell the client that it needs to retry, since a DIFFERENT client has a handshake in progress with the same sessionId
                listenerManager.notifyError(ServerHandshakeException("[$existingConnection] (${message.connectKey}) Connection had an in-use session ID! Telling client to retry."))

                try {
                    handshaker.writeMessage(handshakePublication,
                                            aeronLogInfo,
                                            HandshakeMessage.retry("Handshake already in progress for sessionID!"))
                } catch (e: Error) {
                    listenerManager.notifyError(ServerHandshakeException("[$existingConnection] Handshake error", e))
                }
                return false
            }
        }

        // check to see if this is a pending connection
        if (message.state == HandshakeMessage.DONE) {
            val existingConnection = pendingConnections.remove(message.connectKey)
            if (existingConnection == null) {
                listenerManager.notifyError(ServerHandshakeException("[?????] (${message.connectKey}) Error! Pending connection from client was null, and cannot complete handshake!"))
                return true
            }

            // Server is the "source", client mirrors the server
            logger.debug { "[${existingConnection}] (${message.connectKey}) Connection done with handshake." }

            // before we finish creating the connection, we initialize it (in case there needs to be logic that happens-before `onConnect` calls occur
            listenerManager.notifyInit(existingConnection)

            // this enables the connection to start polling for messages
            server.addConnection(existingConnection)

            // now tell the client we are done
            try {
                handshaker.writeMessage(handshakePublication,
                                        aeronLogInfo,
                                        HandshakeMessage.doneToClient(message.connectKey))

                listenerManager.notifyConnect(existingConnection)
            } catch (e: Exception) {
                listenerManager.notifyError(existingConnection, TransmitException("[$existingConnection] Handshake error", e))
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
        aeronLogInfo: String
    ): Boolean {

        try {
            // VALIDATE:: Check to see if there are already too many clients connected.
            if (server.connections.size() >= config.maxClientCount) {
                listenerManager.notifyError(ServerHandshakeException("[$aeronLogInfo] Connection not allowed! Server is full. Max allowed is ${config.maxClientCount}"))

                try {
                    handshaker.writeMessage(handshakePublication, aeronLogInfo,
                                            HandshakeMessage.error("Server is full"))
                } catch (e: Exception) {
                    listenerManager.notifyError(TransmitException("[$aeronLogInfo] Handshake error", e))
                }
                return false
            }


            // VALIDATE:: we are now connected to the client and are going to create a new connection.
            val currentCountForIp = connectionsPerIpCounts.get(clientAddress)
            if (currentCountForIp >= config.maxConnectionsPerIpAddress) {
                // decrement it now, since we aren't going to permit this connection (take the extra decrement hit on failure, instead of always)
                connectionsPerIpCounts.decrement(clientAddress, currentCountForIp)

                listenerManager.notifyError(ServerHandshakeException("[$aeronLogInfo] Too many connections for IP address. Max allowed is ${config.maxConnectionsPerIpAddress}"))

                try {
                    handshaker.writeMessage(handshakePublication, aeronLogInfo,
                                            HandshakeMessage.error("Too many connections for IP address"))
                } catch (e: Exception) {
                    listenerManager.notifyError(TransmitException("[$aeronLogInfo] Handshake error", e))
                }
                return false
            }
            connectionsPerIpCounts.increment(clientAddress, currentCountForIp)
        } catch (e: Exception) {
            listenerManager.notifyError(ServerHandshakeException("[$aeronLogInfo] Handshake error, Could not validate client message", e))

            try {
                handshaker.writeMessage(handshakePublication, aeronLogInfo,
                                        HandshakeMessage.error("Invalid connection"))
            } catch (e: Exception) {
                listenerManager.notifyError(TransmitException("[$aeronLogInfo] Handshake error", e))
            }
        }

        return true
    }


    /**
     * @return true if the connection was SUCCESS. False if the handshake poller should immediately close the publication
     */
    suspend fun processIpcHandshakeMessageServer(
        server: Server<CONNECTION>,
        handshaker: Handshaker<CONNECTION>,
        aeronDriver: AeronDriver,
        handshakePublication: Publication,
        publicKey: ByteArray,
        message: HandshakeMessage,
        aeronLogInfo: String,
        connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION,
        logger: KLogger
    ): Boolean {
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
            listenerManager.notifyError(ServerHandshakeException("[$aeronLogInfo] Connection not allowed! Unable to allocate a session pub ID for the client connection!", e))

            try {
                handshaker.writeMessage(handshakePublication, aeronLogInfo,
                                        HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                listenerManager.notifyError(TransmitException("[$aeronLogInfo] Handshake error", e))
            }
            return false
        }

        val connectionSessionIdSub: Int
        try {
            connectionSessionIdSub = sessionIdAllocator.allocate()
        } catch (e: AllocationException) {
            // have to unwind actions!
            sessionIdAllocator.free(connectionSessionIdPub)

            listenerManager.notifyError(ServerHandshakeException("[$aeronLogInfo] Connection not allowed! Unable to allocate a session sub ID for the client connection!", e))

            try {
                handshaker.writeMessage(handshakePublication, aeronLogInfo,
                                        HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                listenerManager.notifyError(TransmitException("[$aeronLogInfo] Handshake error", e))
            }
            return false
        }


        val connectionStreamIdPub: Int
        try {
            connectionStreamIdPub = streamIdAllocator.allocate()
        } catch (e: AllocationException) {
            // have to unwind actions!
            sessionIdAllocator.free(connectionSessionIdPub)
            sessionIdAllocator.free(connectionSessionIdSub)

            listenerManager.notifyError(ServerHandshakeException("[$aeronLogInfo] Connection not allowed! Unable to allocate a stream publication ID for the client connection!", e))

            try {
                handshaker.writeMessage(handshakePublication, aeronLogInfo,
                                        HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                listenerManager.notifyError(TransmitException("[$aeronLogInfo] Handshake error", e))
            }
            return false
        }

        val connectionStreamIdSub: Int
        try {
            connectionStreamIdSub = streamIdAllocator.allocate()
        } catch (e: AllocationException) {
            // have to unwind actions!
            sessionIdAllocator.free(connectionSessionIdPub)
            sessionIdAllocator.free(connectionSessionIdSub)
            streamIdAllocator.free(connectionStreamIdPub)

            listenerManager.notifyError(ServerHandshakeException("[$aeronLogInfo] Connection not allowed! Unable to allocate a stream subscription ID for the client connection!", e))

            try {
                handshaker.writeMessage(handshakePublication, aeronLogInfo,
                                        HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                listenerManager.notifyError(TransmitException("[$aeronLogInfo] Handshake error", e))
            }
            return false
        }



        // create a new connection. The session ID is encrypted.
        var connection: CONNECTION? = null
        try {
            // Create a pub/sub at the given address and port, using the given stream ID.
            val newConnectionDriver = ServerConnectionDriver.build(
                aeronDriver = aeronDriver,
                ipInfo = server.ipInfo,
                isIpc = true,
                logInfo = "IPC",

                remoteAddress = null,
                remoteAddressString = "",
                sessionIdPub = connectionSessionIdPub,
                sessionIdSub = connectionSessionIdSub,
                streamIdPub = connectionStreamIdPub,
                streamIdSub = connectionStreamIdSub,
                portPubMdc = 0,
                portPub = 0,
                portSub = 0,
                reliable = true
            )

            val logInfo = newConnectionDriver.pubSub.getLogInfo(logger.isDebugEnabled)
            if (logger.isDebugEnabled) {
                logger.debug { "Creating new connection to $logInfo" }
            } else {
                logger.info { "Creating new connection to $logInfo" }
            }



            connection = connectionFunc(ConnectionParams(publicKey, server, newConnectionDriver.pubSub, PublicKeyValidationState.VALID))

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

            listenerManager.notifyError(ServerHandshakeException("[$aeronLogInfo] (${message.connectKey}) Connection (${connection?.id}) handshake crashed! Message $message", e))

            return false
        }

        return true
    }

    /**
     * note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
     *
     * @return true if the connection was SUCCESS. False if the handshake poller should immediately close the publication
     */
    suspend fun processUdpHandshakeMessageServer(
        server: Server<CONNECTION>,
        handshaker: Handshaker<CONNECTION>,
        handshakePublication: Publication,
        publicKey: ByteArray,
        clientAddress: InetAddress,
        clientAddressString: String,
        portSub: Int,
        portPub: Int,
        mdcPortPub: Int,
        isReliable: Boolean,
        message: HandshakeMessage,
        aeronLogInfo: String,
        connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION,
        logger: KLogger
    ): Boolean {
        val serialization = config.serialization

        // UDP ONLY
        val clientPublicKeyBytes = message.publicKey
        val validateRemoteAddress: PublicKeyValidationState

        // VALIDATE:: check to see if the remote connection's public key has changed!
        validateRemoteAddress = server.crypto.validateRemoteAddress(clientAddress, clientAddressString, clientPublicKeyBytes)
        if (validateRemoteAddress == PublicKeyValidationState.INVALID) {
            listenerManager.notifyError(ServerHandshakeException("[$aeronLogInfo] Connection not allowed! Public key mismatch."))
            return false
        }

        clientPublicKeyBytes!!

        val isSelfMachine = clientAddress.isLoopbackAddress || clientAddress == EndPoint.lanAddress

        if (!isSelfMachine &&
            !validateUdpConnectionInfo(server, handshaker, handshakePublication, config, clientAddress, aeronLogInfo)) {
            // we do not want to limit the loopback addresses!
            return false
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

            listenerManager.notifyError(ServerHandshakeException("[$aeronLogInfo] Connection not allowed! Unable to allocate a session ID for the client connection!"))

            try {
                handshaker.writeMessage(handshakePublication, aeronLogInfo,
                                        HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                listenerManager.notifyError(TransmitException("[$aeronLogInfo] Handshake error", e))
            }
            return false
        }


        val connectionSessionIdSub: Int
        try {
            connectionSessionIdSub = sessionIdAllocator.allocate()
        } catch (e: AllocationException) {
            // have to unwind actions!
            connectionsPerIpCounts.decrementSlow(clientAddress)
            sessionIdAllocator.free(connectionSessionIdPub)

            listenerManager.notifyError(ServerHandshakeException("[$aeronLogInfo] Connection not allowed! Unable to allocate a session ID for the client connection!"))

            try {
                handshaker.writeMessage(handshakePublication, aeronLogInfo,
                                        HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                listenerManager.notifyError(TransmitException("[$aeronLogInfo] Handshake error", e))
            }
            return false
        }


        val connectionStreamIdPub: Int
        try {
            connectionStreamIdPub = streamIdAllocator.allocate()
        } catch (e: AllocationException) {
            // have to unwind actions!
            connectionsPerIpCounts.decrementSlow(clientAddress)
            sessionIdAllocator.free(connectionSessionIdPub)
            sessionIdAllocator.free(connectionSessionIdSub)

            listenerManager.notifyError(ServerHandshakeException("[$aeronLogInfo] Connection not allowed! Unable to allocate a stream ID for the client connection!"))

            try {
                handshaker.writeMessage(handshakePublication, aeronLogInfo,
                                        HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                listenerManager.notifyError(TransmitException("[$aeronLogInfo] Handshake error", e))
            }
            return false
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

            listenerManager.notifyError(ServerHandshakeException("[$aeronLogInfo] Connection not allowed! Unable to allocate a stream ID for the client connection!"))

            try {
                handshaker.writeMessage(handshakePublication, aeronLogInfo,
                                        HandshakeMessage.error("Connection error!"))
            } catch (e: Exception) {
                listenerManager.notifyError(TransmitException("[$aeronLogInfo] Handshake error", e))
            }
            return false
        }


        val logType = if (clientAddress is Inet4Address) {
            "IPv4"
        } else {
            "IPv6"
        }

        // create a new connection. The session ID is encrypted.
        var connection: CONNECTION? = null
        try {
            // Create a pub/sub at the given address and port, using the given stream ID.
            val newConnectionDriver = ServerConnectionDriver.build(
                ipInfo = server.ipInfo,
                aeronDriver = aeronDriver,
                isIpc = false,
                logInfo = logType,

                remoteAddress = clientAddress,
                remoteAddressString = clientAddressString,
                sessionIdPub = connectionSessionIdPub,
                sessionIdSub = connectionSessionIdSub,
                streamIdPub = connectionStreamIdPub,
                streamIdSub = connectionStreamIdSub,
                portPubMdc = mdcPortPub,
                portPub = portPub,
                portSub = portSub,
                reliable = isReliable
            )

            val cryptoSecretKey = server.crypto.generateAesKey(clientPublicKeyBytes, clientPublicKeyBytes, server.crypto.publicKeyBytes)


            val logInfo = newConnectionDriver.pubSub.getLogInfo(logger.isDebugEnabled)
            if (logger.isDebugEnabled) {
                logger.debug { "Creating new connection to $logInfo" }
            } else {
                logger.info { "Creating new connection to $logInfo" }
            }

            connection = connectionFunc(ConnectionParams(publicKey, server, newConnectionDriver.pubSub, validateRemoteAddress))

            // VALIDATE:: are we allowed to connect to this server (now that we have the initial server information)
            val permitConnection = listenerManager.notifyFilter(connection)
            if (!permitConnection) {
                // this will also unwind/free allocations
                connection.close()

                listenerManager.notifyError(ServerHandshakeException("[$aeronLogInfo] Connection was not permitted!"))

                try {
                    handshaker.writeMessage(handshakePublication, aeronLogInfo,
                                            HandshakeMessage.error("Connection was not permitted!"))
                } catch (e: Exception) {
                    listenerManager.notifyError(TransmitException("[$aeronLogInfo] Handshake error", e))
                }
                return false
            }


            ///////////////
            ///  HANDSHAKE
            ///////////////


            // The one-time pad is used to encrypt the session ID, so that ONLY the correct client knows what it is!
            val successMessage = HandshakeMessage.helloAckToClient(message.connectKey)


            // Also send the RMI registration data to the client (so the client doesn't register anything)

            // now create the encrypted payload, using ECDH
            successMessage.registrationData = server.crypto.encrypt(
                cryptoSecretKey = cryptoSecretKey,
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

            listenerManager.notifyError(ServerHandshakeException("[$aeronLogInfo] (${message.connectKey}) Connection (${connection?.id}) handshake crashed! Message $message"))
            return false
        }

        return true
    }

    /**
     * Validates that all the resources have been freed (for all connections)
     *
     * note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
     */
    fun checkForMemoryLeaks() {
        val noAllocations = connectionsPerIpCounts.isEmpty()

        if (!noAllocations) {
            throw AllocationException("Unequal allocate/free method calls for IP validation. \n" +
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

        EventDispatcher.launchSequentially(EventDispatcher.CLOSE) {
            connections.forEach { (_, v) ->
                v.close()
                latch.countDown()
            }
        }

        latch.await(config.connectionCloseTimeoutInSeconds.toLong() * connections.size)
        connections.clear()
    }
}
