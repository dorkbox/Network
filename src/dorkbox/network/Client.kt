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
package dorkbox.network

import dorkbox.bytes.toHexString
import dorkbox.dns.DnsClient
import dorkbox.netUtil.IP
import dorkbox.netUtil.IPv4
import dorkbox.netUtil.IPv6
import dorkbox.netUtil.Inet4
import dorkbox.netUtil.Inet6
import dorkbox.netUtil.dnsUtils.ResolvedAddressTypes
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.IpcMediaDriverConnection
import dorkbox.network.aeron.MediaDriverConnection
import dorkbox.network.aeron.UdpMediaDriverClientConnection
import dorkbox.network.connection.Connection
import dorkbox.network.connection.ConnectionParams
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.ListenerManager
import dorkbox.network.connection.PublicKeyValidationState
import dorkbox.network.connection.eventLoop
import dorkbox.network.exceptions.ClientException
import dorkbox.network.exceptions.ClientRejectedException
import dorkbox.network.exceptions.ClientRetryException
import dorkbox.network.exceptions.ClientShutdownException
import dorkbox.network.exceptions.ClientTimedOutException
import dorkbox.network.handshake.ClientHandshake
import dorkbox.network.ping.Ping
import dorkbox.network.ping.PingManager
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.*

/**
 * The client is both SYNC and ASYNC. It starts off SYNC (blocks thread until it's done), then once it's connected to the server, it's
 * ASYNC.
 *
 * @param config these are the specific connection options
 * @param connectionFunc allows for custom connection implementations defined as a unit function
 * @param loggerName allows for a custom logger name for this endpoint (for when there are multiple endpoints)
 */
@Suppress("unused")
open class Client<CONNECTION : Connection>(
        config: Configuration = Configuration(),
        connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION,
        loggerName: String = Client::class.java.simpleName)
    : EndPoint<CONNECTION>(config, connectionFunc, loggerName) {

    /**
     * The client is both SYNC and ASYNC. It starts off SYNC (blocks thread until it's done), then once it's connected to the server, it's
     * ASYNC.
     *
     * @param config these are the specific connection options
     * @param loggerName allows for a custom logger name for this endpoint (for when there are multiple endpoints)
     * @param connectionFunc allows for custom connection implementations defined as a unit function
     */
    constructor(config: Configuration,
                loggerName: String,
                connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION)
            : this(config, connectionFunc, loggerName)


    /**
     * The client is both SYNC and ASYNC. It starts off SYNC (blocks thread until it's done), then once it's connected to the server, it's
     * ASYNC.
     *
     * @param config these are the specific connection options
     * @param connectionFunc allows for custom connection implementations defined as a unit function
     */
    constructor(config: Configuration,
                connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION)
            : this(config, connectionFunc, Client::class.java.simpleName)


    /**
     * The client is both SYNC and ASYNC. It starts off SYNC (blocks thread until it's done), then once it's connected to the server, it's
     * ASYNC.
     *
     * @param config these are the specific connection options
     * @param loggerName allows for a custom logger name for this endpoint (for when there are multiple endpoints)
     */
    constructor(config: Configuration,
                loggerName: String)
            : this(config,
                   {
                       @Suppress("UNCHECKED_CAST")
                       Connection(it) as CONNECTION
                   },
                   loggerName)


    /**
     * The client is both SYNC and ASYNC. It starts off SYNC (blocks thread until it's done), then once it's connected to the server, it's
     * ASYNC.
     *
     * @param config these are the specific connection options
     */
    constructor(config: Configuration)
            : this(config,
                   {
                       @Suppress("UNCHECKED_CAST")
                       Connection(it) as CONNECTION
                   },
                   Client::class.java.simpleName)



    companion object {
        /**
         * Gets the version number.
         */
        const val version = "5.20"

        /**
         * Checks to see if a client (using the specified configuration) is running.
         *
         * This method should only be used to check if a client is running for a DIFFERENT configuration than the currently running client
         */
        fun isRunning(configuration: Configuration): Boolean {
            return AeronDriver(configuration).isRunning()
        }

        init {
            // Add this project to the updates system, which verifies this class + UUID + version information
            dorkbox.updates.Updates.add(Client::class.java, "5be42ae40cac49fb90dea86bc513141b", version)
        }
    }

    /**
     * The network or IPC address for the client to connect to.
     *
     * For a network address, it can be:
     * - a network name ("localhost", "loopback", "lo", "bob.example.org")
     * - an IP address ("127.0.0.1", "123.123.123.123", "::1")
     *
     * For the IPC (Inter-Process-Communication) address. it must be:
     * - the IPC integer ID, "0x1337c0de", "0x12312312", etc.
     */
    private var remoteAddress0: InetAddress? = IPv4.LOCALHOST

    @Volatile
    private var isConnected = false

    // is valid when there is a connection to the server, otherwise it is null
    private var connection0: CONNECTION? = null


    // This is set by the client so if there is a "connect()" call in the the disconnect callback, we can have proper
    // lock-stop ordering for how disconnect and connect work with each-other
    // GUARANTEE that the callbacks for 'onDisconnect' happens-before the 'onConnect'.
    private val lockStepForConnect = atomic<Mutex?>(null)

    final override fun newException(message: String, cause: Throwable?): Throwable {
        return ClientException(message, cause)
    }

    /**
     * Will attempt to connect to the server, with a default 30 second connection timeout and will block until completed.
     *
     * Default connection is to localhost
     *
     * ### For a network address, it can be:
     *  - a network name ("localhost", "bob.example.org")
     *  - an IP address ("127.0.0.1", "123.123.123.123", "::1")
     *  - an InetAddress address
     *  - if no address is specified, and IPC is disabled in the config, then localhost will be selected
     *
     * ### For the IPC (Inter-Process-Communication) it must be:
     *  - `connect()` (only if ipc is enabled in the configuration)
     *  - `connect("")` (only if ipc is enabled in the configuration)
     *  - `connectIpc()`
     *
     * ### Case does not matter, and "localhost" is the default.
     *
     * @param remoteAddress The network host or ip address
     * @param connectionTimeoutSec wait for x seconds. 0 will wait indefinitely
     * @param reliable true if we want to create a reliable connection (for UDP connections, is message loss acceptable?).
     *
     * @throws IllegalArgumentException if the remote address is invalid
     * @throws ClientTimedOutException if the client is unable to connect in x amount of time
     * @throws ClientRejectedException if the client connection is rejected
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    fun connect(remoteAddress: String = "",
                connectionTimeoutSec: Int = 30,
                reliable: Boolean = true) {
        when {
            // this is default IPC settings
            remoteAddress.isEmpty() && config.enableIpc -> {
                connectIpc(connectionTimeoutSec = connectionTimeoutSec)
            }

            IPv4.isPreferred -> {
                // we have to check first if it's a valid IPv4 address. If not, maybe it's a DNS lookup
                val inet4Address = if (IPv4.isValid(remoteAddress)) {
                    Inet4.toAddress(remoteAddress)
                } else {
                    val client = DnsClient()
                    client.resolvedAddressTypes(ResolvedAddressTypes.IPV4_ONLY)
                    val records = client.resolve(remoteAddress)
                    client.stop()
                    records?.get(0)
                }

                if (inet4Address == null) {
                    throw IllegalArgumentException("The remote address '$remoteAddress' cannot be found.")
                }

                connect(remoteAddress = inet4Address,
                        connectionTimeoutSec = connectionTimeoutSec,
                        reliable = reliable
                )
            }

            IPv6.isPreferred -> {
                // we have to check first if it's a valid IPv6 address. If not, maybe it's a DNS lookup
                val inet6Address = if (IPv6.isValid(remoteAddress)) {
                    Inet6.toAddress(remoteAddress)
                } else {
                    val client = DnsClient()
                    client.resolvedAddressTypes(ResolvedAddressTypes.IPV6_ONLY)
                    val records = client.resolve(remoteAddress)
                    client.stop()
                    records?.get(0)
                }

                if (inet6Address == null) {
                    throw IllegalArgumentException("The remote address '$remoteAddress' cannot be found.")
                }

                connect(remoteAddress = Inet6.toAddress(remoteAddress),
                        connectionTimeoutSec = connectionTimeoutSec,
                        reliable = reliable
                )
            }

            // if there is no preference, then try to connect via IPv4
            else -> {
                // we have to check first if it's a valid IPv4 address. If not, maybe it's a DNS lookup
                val inetAddress = if (IP.isValid(remoteAddress)) {
                    IP.toAddress(remoteAddress)
                } else {
                    val client = DnsClient()
                    client.resolvedAddressTypes(ResolvedAddressTypes.IPV4_PREFERRED)
                    val records = client.resolve(remoteAddress)
                    client.stop()
                    records?.get(0)
                }

                if (inetAddress == null) {
                    throw IllegalArgumentException("The remote address '$remoteAddress' cannot be found.")
                }

                connect(remoteAddress = inetAddress,
                        connectionTimeoutSec = connectionTimeoutSec,
                        reliable = reliable
                )
            }
        }
    }

    /**
     * Will attempt to connect to the server, with a default 30 second connection timeout and will block until completed.
     *
     * Default connection is to localhost
     *
     * ### For a network address, it can be:
     *  - a network name ("localhost", "bob.example.org")
     *  - an IP address ("127.0.0.1", "123.123.123.123", "::1")
     *  - an InetAddress address
     *
     * ### For the IPC (Inter-Process-Communication) it must be:
     *  - `connect()`
     *  - `connect("")`
     *  - `connectIpc()`
     *
     * ### Case does not matter, and "localhost" is the default.
     *
     * @param remoteAddress The network or if localhost, IPC address for the client to connect to
     * @param connectionTimeoutSec wait for x seconds. 0 will wait indefinitely
     * @param reliable true if we want to create a reliable connection (for UDP connections, is message loss acceptable?).
     *
     * @throws IllegalArgumentException if the remote address is invalid
     * @throws ClientTimedOutException if the client is unable to connect in x amount of time
     * @throws ClientRejectedException if the client connection is rejected
     */
    fun connect(remoteAddress: InetAddress,
                connectionTimeoutSec: Int = 30,
                reliable: Boolean = true) {

        // Default IPC ports are flipped because they are in the perspective of the SERVER
        connect(remoteAddress = remoteAddress,
                ipcPublicationId = AeronDriver.IPC_HANDSHAKE_STREAM_ID_SUB,
                ipcSubscriptionId = AeronDriver.IPC_HANDSHAKE_STREAM_ID_PUB,
                connectionTimeoutSec = connectionTimeoutSec,
                reliable = reliable)
    }

    /**
     * Will attempt to connect to the server via IPC, with a default 30 second connection timeout and will block until completed.
     *
     * @param ipcPublicationId The IPC publication address for the client to connect to
     * @param ipcSubscriptionId The IPC subscription address for the client to connect to
     * @param connectionTimeoutSec wait for x seconds. 0 will wait indefinitely.
     *
     * @throws IllegalArgumentException if the remote address is invalid
     * @throws ClientTimedOutException if the client is unable to connect in x amount of time
     * @throws ClientRejectedException if the client connection is rejected
     */
    @Suppress("DuplicatedCode")
    fun connectIpc(ipcPublicationId: Int = AeronDriver.IPC_HANDSHAKE_STREAM_ID_SUB,
                   ipcSubscriptionId: Int = AeronDriver.IPC_HANDSHAKE_STREAM_ID_PUB,
                   connectionTimeoutSec: Int = 30) {
        // Default IPC ports are flipped because they are in the perspective of the SERVER

        require(ipcPublicationId != ipcSubscriptionId) { "IPC publication and subscription ports cannot be the same! The must match the server's configuration." }

        connect(remoteAddress = null, // required!
                ipcPublicationId = ipcPublicationId,
                ipcSubscriptionId = ipcSubscriptionId,
                connectionTimeoutSec = connectionTimeoutSec)
    }

    /**
     * Will attempt to connect to the server, with a default 30 second connection timeout and will block until completed.
     * If unable to connect within the specified timeout an exception will be thrown
     *
     * Default connection is to localhost
     *
     * ### For a network address, it can be:
     *  - a network name ("localhost", "bob.example.org")
     *  - an IP address ("127.0.0.1", "123.123.123.123", "::1")
     *  - an InetAddress address
     *
     * ### For the IPC (Inter-Process-Communication) it must be:
     *  - `connect()`
     *  - `connect("")`
     *  - `connectIpc()`
     *
     * ### Case does not matter, and "localhost" is the default.
     *
     * @param remoteAddress The network or if localhost, IPC address for the client to connect to
     * @param ipcPublicationId The IPC publication address for the client to connect to
     * @param ipcSubscriptionId The IPC subscription address for the client to connect to
     * @param connectionTimeoutSec wait for x seconds. 0 will wait indefinitely.
     * @param reliable true if we want to create a reliable connection (for UDP connections, is message loss acceptable?).
     *
     * @throws IllegalArgumentException if the remote address is invalid
     * @throws ClientTimedOutException if the client is unable to connect in x amount of time
     * @throws ClientRejectedException if the client connection is rejected
     * @throws ClientShutdownException if the client connection is shutdown while trying to connect
     * @throws ClientException if there are misc errors
     */
    @Suppress("DuplicatedCode")
    private fun connect(remoteAddress: InetAddress? = null,
                        // Default IPC ports are flipped because they are in the perspective of the SERVER
                        ipcPublicationId: Int = AeronDriver.IPC_HANDSHAKE_STREAM_ID_SUB,
                        ipcSubscriptionId: Int = AeronDriver.IPC_HANDSHAKE_STREAM_ID_PUB,
                        connectionTimeoutSec: Int = 30,
                        reliable: Boolean = true) {
        require(connectionTimeoutSec >= 0) { "connectionTimeoutSec '$connectionTimeoutSec' is invalid. It must be >=0" }

        if (isConnected) {
            logger.error { "Unable to connect when already connected!" }
            return
        }

        // localhost/loopback IP might not always be 127.0.0.1 or ::1
        this.remoteAddress0 = remoteAddress
        connection0 = null

        // we are done with initial configuration, now initialize aeron and the general state of this endpoint
        try {
            initEndpointState()
        } catch (e: Exception) {
            logger.error(e) { "Unable to initialize the endpoint state" }
            return
        }

        // only try to connect via IPv4 if we have a network interface that supports it!
        if (remoteAddress is Inet4Address && !IPv4.isAvailable) {
            require(false) { "Unable to connect to the IPv4 address ${IPv4.toString(remoteAddress)}, there are no IPv4 interfaces available!" }
        }

        // only try to connect via IPv6 if we have a network interface that supports it!
        if (remoteAddress is Inet6Address && !IPv6.isAvailable) {
            require(false) { "Unable to connect to the IPv6 address ${IPv6.toString(remoteAddress)}, there are no IPv6 interfaces available!" }
        }

        if (remoteAddress != null && remoteAddress.isAnyLocalAddress) {
            require(false) { "Cannot connect to ${IP.toString(remoteAddress)} It is an invalid address!" }
        }

        // IPC can be enabled TWO ways!
        // - config.enableIpc
        // - NULL remoteAddress
        // It is entirely possible that the server does not have IPC enabled!
        val autoChangeToIpc =
            (config.enableIpc && (remoteAddress == null || remoteAddress.isLoopbackAddress)) || (!config.enableIpc && remoteAddress == null)

        val handshake = ClientHandshake(crypto, this, logger)

        runBlocking {
            val handshakeTimeout = 5
            val timoutInNanos = TimeUnit.SECONDS.toNanos(connectionTimeoutSec.toLong())
            val startTime = System.nanoTime()
            var success = false
            while (timoutInNanos == 0L || System.nanoTime() - startTime < timoutInNanos) {
                if (isShutdown()) {
                    // If we are connecting indefinitely, we have to make sure to end the connection process
                    val exception = ClientShutdownException("Unable to connect while shutting down")
                    logger.error(exception) { "Aborting connection retry attempt to server." }
                    listenerManager.notifyError(exception)
                    throw exception
                }

                try {
                    val handshakeConnection = if (autoChangeToIpc) {
                        buildIpcHandshake(
                            ipcSubscriptionId = ipcSubscriptionId,
                            ipcPublicationId = ipcPublicationId,
                            connectionTimeoutSec = handshakeTimeout,
                            reliable = reliable
                        )
                    } else {
                        buildUdpHandshake(connectionTimeoutSec = handshakeTimeout, reliable = reliable)
                    }

                    logger.info { handshakeConnection.clientInfo }


                    connect0(handshake, handshakeConnection, handshakeTimeout)
                    success = true

                    // once we're done with the connection process, stop trying
                    break
                } catch (e: ClientRetryException) {
                    handshake.reset()

                    // short delay, since it failed we want to limit the retry rate to something slower than "as fast as the CPU can do it"
                    delay(500)
                    if (logger.isTraceEnabled) {
                        logger.trace(e) { "Unable to connect to ${IP.toString(remoteAddress!!)}, retrying..." }
                    } else {
                        logger.info { "Unable to connect to ${IP.toString(remoteAddress!!)}, retrying..." }
                    }

                } catch (e: Exception) {
                    logger.error(e) { "Un-recoverable error during handshake. Aborting." }
                    listenerManager.notifyError(e)
                    throw e
                }
            }

            if (!success) {
                if (System.nanoTime() - startTime < timoutInNanos) {
                    // we timed out. Throw the appropriate exception
                    val exception = ClientTimedOutException("Unable to connect to the server in $connectionTimeoutSec seconds")
                    logger.error(exception) { "Aborting connection attempt to server." }
                    listenerManager.notifyError(exception)
                    throw exception
                }

                // If we did not connect - throw an error. When `client.connect()` is called, either it connects or throws an error
                val exception = ClientRejectedException("The server did not respond or permit the connection attempt")
                logger.error(exception) { "Aborting connection retry attempt to server." }
                listenerManager.notifyError(exception)
                throw exception
            }
        }
    }

    private suspend fun buildIpcHandshake(ipcSubscriptionId: Int, ipcPublicationId: Int, connectionTimeoutSec: Int, reliable: Boolean): MediaDriverConnection {
        if (remoteAddress == null) {
            logger.info { "IPC enabled." }
        } else {
            logger.info { "IPC for loopback enabled and aeron is already running. Auto-changing network connection from ${IP.toString(remoteAddress!!)} -> IPC" }
        }

        // MAYBE the server doesn't have IPC enabled? If no, we need to connect via network instead
        val ipcConnection = IpcMediaDriverConnection(streamIdSubscription = ipcSubscriptionId,
                                                     streamId = ipcPublicationId,
                                                     sessionId = AeronDriver.RESERVED_SESSION_ID_INVALID
        )

        // throws a ConnectTimedOutException if the client cannot connect for any reason to the server handshake ports
        try {
            ipcConnection.buildClient(aeronDriver, logger)
            return ipcConnection
        } catch (e: Exception) {
            if (remoteAddress == null) {
                // if we specified that we MUST use IPC, then we have to throw the exception, because there is no IPC
                val clientException = ClientException("Unable to connect via IPC to server. No address was specified", e)
                ListenerManager.cleanStackTraceInternal(clientException)
                throw clientException
            }
        }

        if (remoteAddress == null) {
            // if we specified that we MUST use IPC, then we have to throw the exception, because there is no IPC
            val clientException = ClientException("Unable to connect via IPC to server. No address was specified")
            ListenerManager.cleanStackTraceInternal(clientException)
            throw clientException
        }

        logger.info { "IPC for loopback enabled, but unable to connect. Retrying with address ${IP.toString(remoteAddress!!)}" }

        // try a UDP connection instead
        val udpConnection = UdpMediaDriverClientConnection(
            address = remoteAddress!!,
            publicationPort = config.subscriptionPort,
            subscriptionPort = config.publicationPort,
            streamId = AeronDriver.UDP_HANDSHAKE_STREAM_ID,
            sessionId = AeronDriver.RESERVED_SESSION_ID_INVALID,
            connectionTimeoutSec = connectionTimeoutSec,
            isReliable = reliable
        )

        // throws a ConnectTimedOutException if the client cannot connect for any reason to the server handshake ports
        udpConnection.buildClient(aeronDriver, logger)
        return udpConnection
    }

    private suspend fun buildUdpHandshake(connectionTimeoutSec: Int, reliable: Boolean): MediaDriverConnection {
        val test = UdpMediaDriverClientConnection(
            address = remoteAddress!!,
            publicationPort = config.subscriptionPort,
            subscriptionPort = config.publicationPort,
            streamId = AeronDriver.UDP_HANDSHAKE_STREAM_ID,
            sessionId = AeronDriver.RESERVED_SESSION_ID_INVALID,
            connectionTimeoutSec = connectionTimeoutSec,
            isReliable = reliable
        )

        // throws a ConnectTimedOutException if the client cannot connect for any reason to the server handshake ports
        test.buildClient(aeronDriver, logger)
        return test
    }

    // the handshake process might have to restart this connection process.
    private suspend fun connect0(handshake: ClientHandshake<CONNECTION>, handshakeConnection: MediaDriverConnection, connectionTimeoutSec: Int) {
        // this will block until the connection timeout, and throw an exception if we were unable to connect with the server
        val isUsingIPC = handshakeConnection is IpcMediaDriverConnection


        // throws(ConnectTimedOutException::class, ClientRejectedException::class, ClientException::class)
        val connectionInfo = handshake.hello(handshakeConnection, connectionTimeoutSec)

        // VALIDATE:: check to see if the remote connection's public key has changed!
        val validateRemoteAddress = if (isUsingIPC) {
            PublicKeyValidationState.VALID
        } else {
            crypto.validateRemoteAddress(remoteAddress!!, remoteAddressString, connectionInfo.publicKey)
        }

        if (validateRemoteAddress == PublicKeyValidationState.INVALID) {
            handshakeConnection.close()
            val exception = ClientRejectedException("Connection to ${IP.toString(remoteAddress!!)} not allowed! Public key mismatch.")
            logger.error(exception) { "Validation error" }
            throw exception
        }


        // VALIDATE:: If the serialization DOES NOT match between the client/server, then the server will emit a log, and the
        // client will timeout. SPECIFICALLY.... we do not give class serialization/registration info to the client - in case the client
        // is rogue, we do not want to carelessly provide info.


        // we are now connected, so we can connect to the NEW client-specific ports
        val clientConnection = if (isUsingIPC) {
            IpcMediaDriverConnection(
                sessionId = connectionInfo.sessionId,
                // NOTE: pub/sub must be switched!
                streamIdSubscription = connectionInfo.publicationPort,
                streamId = connectionInfo.subscriptionPort)
        }
        else {
            UdpMediaDriverClientConnection(
                address = (handshakeConnection as UdpMediaDriverClientConnection).address,
                // NOTE: pub/sub must be switched!
                publicationPort = connectionInfo.subscriptionPort,
                subscriptionPort = connectionInfo.publicationPort,
                streamId = connectionInfo.streamId,
                sessionId = connectionInfo.sessionId,
                connectionTimeoutSec = connectionTimeoutSec,
                isReliable = handshakeConnection.isReliable)
        }

        // we have to construct how the connection will communicate!
        clientConnection.buildClient(aeronDriver, logger)

        // only the client connects to the server, so here we have to connect. The server (when creating the new "connection" object)
        // does not need to do anything
        //
        // throws a ConnectTimedOutException if the client cannot connect for any reason to the server-assigned client ports
        logger.info { clientConnection.clientInfo }


        ///////////////
        ////   RMI
        ///////////////

        // we set up our kryo information once we connect to a server (using the server's kryo registration details)
        if (!serialization.finishInit(type, connectionInfo.kryoRegistrationDetails)) {
            handshakeConnection.close()

            // because we are getting the class registration details from the SERVER, this should never be the case.
            // It is still and edge case where the reconstruction of the registration details fails (maybe because of custom serializers)
            val exception = if (isUsingIPC) {
                ClientRejectedException("Connection to IPC has incorrect class registration details!!")
            } else {
                ClientRejectedException("Connection to ${IP.toString(remoteAddress!!)} has incorrect class registration details!!")
            }
            ListenerManager.cleanStackTraceInternal(exception)
            throw exception
        }



        val newConnection: CONNECTION
        if (isUsingIPC) {
            newConnection = connectionFunc(ConnectionParams(this, clientConnection, PublicKeyValidationState.VALID, rmiConnectionSupport))
        } else {
            newConnection = connectionFunc(ConnectionParams(this, clientConnection, validateRemoteAddress, rmiConnectionSupport))
            remoteAddress!!

            // VALIDATE are we allowed to connect to this server (now that we have the initial server information)
            val permitConnection = listenerManager.notifyFilter(newConnection)
            if (!permitConnection) {
                handshakeConnection.close()
                val exception = ClientRejectedException("Connection to ${IP.toString(remoteAddress!!)} was not permitted!")
                ListenerManager.cleanStackTrace(exception)
                logger.error(exception) { "Permission error" }
                throw exception
            }

            logger.info { "Adding new signature for ${IP.toString(remoteAddress!!)} : ${connectionInfo.publicKey.toHexString()}" }
            storage.addRegisteredServerKey(remoteAddress!!, connectionInfo.publicKey)
        }


        //////////////
        ///  Extra Close action
        //////////////
        newConnection.closeAction = {
            // this is called whenever connection.close() is called by the framework or via client.close()

            // on the client, we want to GUARANTEE that the disconnect happens-before connect.
            if (!lockStepForConnect.compareAndSet(null, Mutex(locked = true))) {
                logger.error { "Connection ${newConnection.id} : close lockStep for disconnect was in the wrong state!" }
            }

            isConnected = false
            // this is called whenever connection.close() is called by the framework or via client.close()

            // make sure to call our client.notifyDisconnect() callbacks

            // this always has to be on event dispatch, otherwise we can have weird logic loops if we reconnect within a disconnect callback
            actionDispatch.eventLoop {
                listenerManager.notifyDisconnect(connection)
                lockStepForConnect.getAndSet(null)?.unlock()
            }
        }

        connection0 = newConnection
        addConnection(newConnection)

        logger.error { "Connection created, finishing handshake: ${handshake.connectKey}" }

        // tell the server our connection handshake is done, and the connection can now listen for data.
        // also closes the handshake (will also throw connect timeout exception)
        val canFinishConnecting: Boolean
        runBlocking {
            // this value matches the server, and allows for a more robust connection attempt
            val successAttemptTimeout = config.connectionCloseTimeoutInSeconds * 2
            canFinishConnecting = try {
                handshake.done(handshakeConnection, successAttemptTimeout)
            } catch (e: ClientException) {
                logger.error(e) { "Error during handshake" }
                false
            }
        }

        if (canFinishConnecting) {
            isConnected = true

            // this forces the current thread to WAIT until poll system has started
            val mutex = Mutex(locked = true)

            // have to make a new thread to listen for incoming data!
            // SUBSCRIPTIONS ARE NOT THREAD SAFE! Only one thread at a time can poll them

            // these have to be in two SEPARATE actionDispatch.launch commands.... otherwise...
            // if something inside-of notifyConnect is blocking or suspends, then polling will never happen!
            actionDispatch.launch {
                try {
                    mutex.unlock()
                } catch (ignored: Exception) {}

                val pollIdleStrategy = config.pollIdleStrategy

                while (!isShutdown()) {
                    if (newConnection.isClosedViaAeron()) {
                        // If the connection has either been closed, or has expired, it needs to be cleaned-up/deleted.
                        logger.debug { "[${newConnection.id}] connection expired" }

                        // event-loop is required, because we want to run this code AFTER the current coroutine has finished. This prevents
                        // odd race conditions when a client is restarted. Can only be run from inside another co-routine!
                        actionDispatch.eventLoop {
                            // NOTE: We do not shutdown the client!! The client is only closed by explicitly calling `client.close()`
                            newConnection.close()
                        }
                        return@launch
                    }
                    else {
                        //  Polls the AERON media driver subscription channel for incoming messages
                        val pollCount = newConnection.pollSubscriptions()

                        // 0 means we idle. >0 means reset and don't idle (because there are likely more poll events)
                        pollIdleStrategy.idle(pollCount)
                    }
                }
            }

            actionDispatch.eventLoop {
                mutex.withLock {  }

                lockStepForConnect.value?.withLock {  }

                listenerManager.notifyConnect(newConnection)

                lockStepForConnect.lazySet(null)
            }
        } else {
            close()

            val exception = ClientRejectedException("Unable to connect with server: ${handshakeConnection.clientInfo}")
            ListenerManager.cleanStackTrace(exception)
            throw exception
        }
    }

    /**
     * true if the remote public key changed. This can be useful if specific actions are necessary when the key has changed.
     */
    val remoteKeyHasChanged: Boolean
        get() = connection.hasRemoteKeyChanged()

    /**
     * the remote address
     */
    val remoteAddress: InetAddress?
        get() = remoteAddress0

    /**
     * the remote address, as a string.
     */
    val remoteAddressString: String
        get() {
            return when (val address = remoteAddress) {
                is Inet4Address -> IPv4.toString(address)
                is Inet6Address -> IPv6.toString(address, true)
                else -> "ipc"
            }
        }

    /**
     * true if this connection is an IPC connection
     */
    val isIPC: Boolean
        get() = connection.isIpc

    /**
     * @return true if this connection is a network connection
     */
    val isNetwork: Boolean
        get() = connection.isNetwork

    /**
     * @return the connection (TCP or IPC) id of this connection.
     */
    val id: Int
        get() = connection.id

    /**
     * the connection used by the client, this is only valid after the client has connected
     */
    val connection: CONNECTION
        get() = connection0 as CONNECTION


    /**
     * Sends a message to the server, if the connection is closed for any reason, this returns false.
     *
     * @return true if the message was sent successfully, false if the connection has been closed
     */
    suspend fun send(message: Any): Boolean {
        val c = connection0

        return if (c != null) {
            c.send(message)
        } else {
            val exception = ClientException("Cannot send a message when there is no connection!")
            logger.error(exception) { "No connection!" }
            false
        }
    }

    /**
     * Sends a message to the server, if the connection is closed for any reason, this returns false.
     *
     * @return true if the message was sent successfully, false if the connection has been closed
     */
    fun sendBlocking(message: Any): Boolean {
        return runBlocking {
            send(message)
        }
    }

    /**
     * Sends a "ping" packet to measure **ROUND TRIP** time to the remote connection.
     *
     * @param function called when the ping returns (ie: update time/latency counters/metrics/etc)
     *
     * @return true if the ping was successfully sent to the client
     */
    suspend fun ping(pingTimeoutSeconds: Int = PingManager.DEFAULT_TIMEOUT_SECONDS, function: suspend Ping.() -> Unit): Boolean {
        val c = connection0

        if (c != null) {
            return pingManager.ping(c, pingTimeoutSeconds, actionDispatch, responseManager, logger, function)
        } else {
            logger.error(ClientException("Cannot send a ping when there is no connection!")) { "No connection!" }
        }

        return false
    }

    /**
     * Sends a "ping" packet to measure **ROUND TRIP** time to the remote connection.
     *
     * @param function called when the ping returns (ie: update time/latency counters/metrics/etc)
     */
    fun pingBlocking(pingTimeoutSeconds: Int = PingManager.DEFAULT_TIMEOUT_SECONDS, function: suspend Ping.() -> Unit): Boolean {
        return runBlocking {
            ping(pingTimeoutSeconds, function)
        }
    }

    /**
     * Removes the specified host address from the list of registered server keys.
     */
    fun removeRegisteredServerKey(address: InetAddress) {
        val savedPublicKey = storage.getRegisteredServerKey(address)
        if (savedPublicKey != null) {
            logger.debug { "Deleting remote IP address key $address" }
            storage.removeRegisteredServerKey(address)
        }
    }

    // no impl
    final override fun close0() {
        // when we close(), don't permit reconnect. add "close(boolean)" (aka "shutdown"), to deny a connect request (and permanently stay closed)
    }
}
