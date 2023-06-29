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
package dorkbox.network

import dorkbox.bytes.toHexString
import dorkbox.dns.DnsClient
import dorkbox.netUtil.IP
import dorkbox.netUtil.IPv4
import dorkbox.netUtil.IPv6
import dorkbox.netUtil.dnsUtils.ResolvedAddressTypes
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.EventPoller
import dorkbox.network.connection.*
import dorkbox.network.connection.IpInfo.Companion.formatCommonAddress
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTrace
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTraceInternal
import dorkbox.network.exceptions.*
import dorkbox.network.handshake.ClientConnectionDriver
import dorkbox.network.handshake.ClientHandshake
import dorkbox.network.handshake.ClientHandshakeDriver
import dorkbox.network.ping.Ping
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
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
        config: ClientConfiguration = ClientConfiguration(),
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
    constructor(config: ClientConfiguration,
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
    constructor(config: ClientConfiguration,
                connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION)
            : this(config, connectionFunc, Client::class.java.simpleName)


    /**
     * The client is both SYNC and ASYNC. It starts off SYNC (blocks thread until it's done), then once it's connected to the server, it's
     * ASYNC.
     *
     * @param config these are the specific connection options
     * @param loggerName allows for a custom logger name for this endpoint (for when there are multiple endpoints)
     */
    constructor(config: ClientConfiguration,
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
    constructor(config: ClientConfiguration)
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
        const val version = "6.4"

        /**
         * Ensures that the client (using the specified configuration) is NO LONGER running.
         *
         * NOTE: This method should only be used to check if a client is running for a DIFFERENT configuration than the currently running client
         *
         * By default, we will wait the [Configuration.connectionCloseTimeoutInSeconds] * 2 amount of time before returning.
         *
         * @return true if the media driver is STOPPED.
         */
        fun ensureStopped(configuration: Configuration): Boolean = runBlocking {
            val timeout = TimeUnit.SECONDS.toMillis(configuration.connectionCloseTimeoutInSeconds.toLong() * 2)

            val logger = KotlinLogging.logger(Client::class.java.simpleName)
            AeronDriver.ensureStopped(configuration, logger, timeout)
        }

        /**
         * Checks to see if a client (using the specified configuration) is running.
         *
         * NOTE: This method should only be used to check if a client is running for a DIFFERENT configuration than the currently running client
         *
         * @return true if the media driver is active and running
         */
        fun isRunning(configuration: Configuration): Boolean = runBlocking {
            val logger = KotlinLogging.logger(Client::class.java.simpleName)
            AeronDriver.isRunning(configuration, logger)
        }

        init {
            // Add this project to the updates system, which verifies this class + UUID + version information
            dorkbox.updates.Updates.add(Client::class.java, "5be42ae40cac49fb90dea86bc513141b", version)
        }
    }

    /**
     * The network address of the remote machine that the client connected to. This will be null for IPC connections.
     */
    @Volatile
    var address: InetAddress? = IPv4.LOCALHOST
        private set

    /**
     * The network address of the remote machine that the client connected to, as a string. This will be "IPC" for IPC connections.
     */
    @Volatile
    var addressString: String = "UNKNOWN"
        private set

    /**
     * The network address of the remote machine that the client connected to, as a pretty string. This will be "IPC" for IPC connections.
     */
    @Volatile
    var addressPrettyString: String = "UNKNOWN"
        private set

    /**
     * The machine port of the remote machine that the client has connected to. This will be 0 for IPC connections
     */
    @Volatile
    var port: Int = 0
        private set

    /**
     * The default connection reliability type (ie: can the lower-level network stack throw away data that has errors, for example real-time-voice)
     */
    @Volatile
    var reliable: Boolean = true
        private set

    /**
     * How long (in seconds) will connections wait to connect. 0 will wait indefinitely,
     */
    @Volatile
    var connectionTimeoutSec: Int = 0
        private set


    private val handshake = ClientHandshake(this, logger)

    @Volatile
    private var slowDownForException = false

    // is valid when there is a connection to the server, otherwise it is null
    @Volatile
    private var connection0: CONNECTION? = null

    final override fun newException(message: String, cause: Throwable?): Throwable {
        return ClientException(message, cause)
    }

    /**
     * Will attempt to re-connect to the server, with the settings previously used when calling connect()
     *
     * @throws IllegalArgumentException if the remote address is invalid
     * @throws ClientTimedOutException if the client is unable to connect in x amount of time
     * @throws ClientRejectedException if the client connection is rejected
     * @throws ClientShutdownException if the client connection is shutdown while trying to connect
     * @throws ClientException if there are misc errors
     */
    @Suppress("DuplicatedCode")
    suspend fun reconnect() {
        connect(
            remoteAddress = address,
            remoteAddressString = addressString,
            remoteAddressPrettyString = addressPrettyString,
            port = port,
            connectionTimeoutSec = connectionTimeoutSec,
            reliable = reliable,
        )
    }

    /**
     * Will attempt to connect via IPC to the server, with a default 30 second connection timeout and will block until completed.
     *
     * ### For the IPC (Inter-Process-Communication) it must be:
     *  - `connectIpc()`
     *
     * @param connectionTimeoutSec wait for x seconds. 0 will wait indefinitely
     *
     * @throws ClientTimedOutException if the client is unable to connect in x amount of time
     */
    fun connectIpc(connectionTimeoutSec: Int = 30) = runBlocking {
        connect(remoteAddress = null, // required!
                port = 0,
                remoteAddressString = IPC_NAME,
                remoteAddressPrettyString = IPC_NAME,
                connectionTimeoutSec = connectionTimeoutSec)
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
     *  - `connect(LOCALHOST)`
     *  - `connect("localhost")`
     *  - `connect("bob.example.org")`
     *  - `connect("127.0.0.1")`
     *  - `connect("::1")`
     *
     * ### For the IPC (Inter-Process-Communication) it must be:
     *  - `connectIPC()`
     *
     * ### Case does not matter, and "localhost" is the default.
     *
     * @param remoteAddress The network or if localhost, IPC address for the client to connect to
     * @param port The network host port to connect to
     * @param connectionTimeoutSec wait for x seconds. 0 will wait indefinitely
     * @param reliable true if we want to create a reliable connection, can the lower-level network stack throw away data that has errors, (IE: real-time-voice traffic)
     *
     * @throws IllegalArgumentException if the remote address is invalid
     * @throws ClientTimedOutException if the client is unable to connect in x amount of time
     * @throws ClientRejectedException if the client connection is rejected
     */
     fun connect(
        remoteAddress: InetAddress,
        port: Int,
        connectionTimeoutSec: Int = 30,
        reliable: Boolean = true) = runBlocking {

        val remoteAddressString = when (remoteAddress) {
            is Inet4Address -> IPv4.toString(remoteAddress)
            is Inet6Address -> IPv6.toString(remoteAddress, true)
            else ->  throw IllegalArgumentException("Cannot connect to $remoteAddress It is an invalid address type!")
        }

        connect(remoteAddress = remoteAddress,
                remoteAddressString = remoteAddressString,
                remoteAddressPrettyString = remoteAddressString,
                port = port,
                connectionTimeoutSec = connectionTimeoutSec,
                reliable = reliable)
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
     *  - `connect(LOCALHOST)`
     *  - `connect("localhost")`
     *  - `connect("bob.example.org")`
     *  - `connect("127.0.0.1")`
     *  - `connect("::1")`
     *
     * ### For the IPC (Inter-Process-Communication) it must be:
     *  - `connectIPC()`
     *
     * ### Case does not matter, and "localhost" is the default.
     *
     * @param remoteAddress The network host name or ip address
     * @param port The network host port to connect to
     * @param connectionTimeoutSec wait for x seconds. 0 will wait indefinitely
     * @param reliable true if we want to create a reliable connection, can the lower-level network stack throw away data that has errors, (IE: real-time-voice traffic)
     *
     * @throws IllegalArgumentException if the remote address is invalid
     * @throws ClientTimedOutException if the client is unable to connect in x amount of time
     * @throws ClientRejectedException if the client connection is rejected
     */
    fun connect(
        remoteAddress: String,
        port: Int,
        connectionTimeoutSec: Int = 30,
        reliable: Boolean = true) {
            fun connect(dnsResolveType: ResolvedAddressTypes) = runBlocking {
                val ipv4Requested = dnsResolveType == ResolvedAddressTypes.IPV4_ONLY || dnsResolveType == ResolvedAddressTypes.IPV4_PREFERRED

                val inetAddress = formatCommonAddress(remoteAddress, ipv4Requested) {
                    // we already checked first if it's a valid IP address. This is called if it's not, since it might be a DNS lookup
                    val client = DnsClient()
                    client.resolvedAddressTypes(dnsResolveType)
                    val records = client.resolve(remoteAddress)
                    client.stop()
                    records?.get(0)
                } ?: throw IllegalArgumentException("The remote address '$remoteAddress' cannot be found.")

                val remoteAddressAsIp = IP.toString(inetAddress)
                val formattedString = if (remoteAddress == remoteAddressAsIp) {
                    remoteAddress
                } else {
                    "$remoteAddress ($remoteAddressAsIp)"
                }

                connect(remoteAddress = inetAddress,
                        // we check again, because the inetAddress that comes back from DNS, might not be what we expect
                        remoteAddressString = remoteAddressAsIp,
                        remoteAddressPrettyString = formattedString,
                        port = port,
                        connectionTimeoutSec = connectionTimeoutSec,
                        reliable = reliable)
            }

            when {
                // this is default IPC settings
                remoteAddress.isEmpty() && config.enableIpc -> runBlocking {
                    connect(remoteAddress = null, // required!
                            port = 0,
                            remoteAddressString = IPC_NAME,
                            remoteAddressPrettyString = IPC_NAME,
                            connectionTimeoutSec = connectionTimeoutSec)
                }

                // IPv6 takes precedence ONLY if it's enabled manually
                config.enableIPv6 -> { connect(ResolvedAddressTypes.IPV6_ONLY) }
                config.enableIPv4 -> { connect(ResolvedAddressTypes.IPV4_ONLY) }
                IPv4.isPreferred -> { connect(ResolvedAddressTypes.IPV4_PREFERRED) }
                IPv6.isPreferred -> { connect(ResolvedAddressTypes.IPV6_PREFERRED) }
                else -> { connect(ResolvedAddressTypes.IPV4_PREFERRED) }
            }
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
     *  - `connect()` (same as localhost, but only if ipc is disabled in the configuration)
     *  - `connect("localhost")`
     *  - `connect("bob.example.org")`
     *  - `connect("127.0.0.1")`
     *  - `connect("::1")`
     *
     * ### For the IPC (Inter-Process-Communication) it must be:
     *  - `connect()` (only if ipc is enabled in the configuration)
     *
     * ### Case does not matter, and "localhost" is the default.
     *
     * @param remoteAddress The network or if localhost for the client to connect to
     * @param port The network host port to connect to
     * @param connectionTimeoutSec wait for x seconds. 0 will wait indefinitely.
     * @param reliable true if we want to create a reliable connection, can the lower-level network stack throw away data that has errors, (IE: real-time-voice traffic)
     *
     * @throws IllegalArgumentException if the remote address is invalid
     * @throws ClientTimedOutException if the client is unable to connect in x amount of time
     * @throws ClientRejectedException if the client connection is rejected
     * @throws ClientShutdownException if the client connection is shutdown while trying to connect
     * @throws ClientException if there are misc errors
     */
    @Suppress("DuplicatedCode")
    private suspend fun connect(
        remoteAddress: InetAddress? = null,
        remoteAddressString: String,
        remoteAddressPrettyString: String,
        port: Int = 0,
        connectionTimeoutSec: Int = 30,
        reliable: Boolean = true)
    {
        // NOTE: it is critical to remember that Aeron DOES NOT like running from coroutines!

        // on the client, we must GUARANTEE that the disconnect/close completes before NEW connect begins.
        // we will know this if we are running inside an INTERNAL dispatch that is NOT the connect dispatcher!
        val currentDispatcher = EventDispatcher.getCurrentEvent()
        if (currentDispatcher != null && currentDispatcher != EventDispatcher.CONNECT) {
            EventDispatcher.CONNECT.launch {
                connect(
                    remoteAddress = remoteAddress,
                    remoteAddressString = remoteAddressString,
                    remoteAddressPrettyString = remoteAddressPrettyString,
                    port = port,
                    connectionTimeoutSec = connectionTimeoutSec,
                    reliable = reliable)
            }
            return
        }

        require(port > 0 || remoteAddress == null) { "port must be > 0" }
        require(port < 65535) { "port must be < 65535" }

        // the lifecycle of a client is the ENDPOINT (measured via the network event poller) and CONNECTION (measure from connection closed)
        if (!waitForClose()) {
            if (endpointIsRunning.value) {
                listenerManager.notifyError(ServerException("Unable to start, the client is already running!"))
            } else {
                listenerManager.notifyError(ClientException("Unable to connect the client!"))
            }
            return
        }

        config as ClientConfiguration

        require(connectionTimeoutSec >= 0) { "connectionTimeoutSec '$connectionTimeoutSec' is invalid. It must be >=0" }

        connection0 = null

        // only try to connect via IPv4 if we have a network interface that supports it!
        if (remoteAddress is Inet4Address && !IPv4.isAvailable) {
            require(false) { "Unable to connect to the IPv4 address $remoteAddressPrettyString, there are no IPv4 interfaces available!" }
        }

        // only try to connect via IPv6 if we have a network interface that supports it!
        if (remoteAddress is Inet6Address && !IPv6.isAvailable) {
            require(false) { "Unable to connect to the IPv6 address $remoteAddressPrettyString, there are no IPv6 interfaces available!" }
        }

        if (remoteAddress != null && remoteAddress.isAnyLocalAddress) {
            require(false) { "Cannot connect to $remoteAddressPrettyString It is an invalid address!" }
        }

        // we are done with initial configuration, now initialize aeron and the general state of this endpoint
        // this also makes sure that the dispatchers are still active.
        // Calling `client.close()` will shutdown the dispatchers (and a new client instance must be created)
        try {
            startDriver()
            verifyState()
            initializeState()
        } catch (e: Exception) {
            resetOnError()
            listenerManager.notifyError(ClientException("Unable to start the client!", e))
            return
        }

        // localhost/loopback IP might not always be 127.0.0.1 or ::1
        // will be null if it's IPC
        this.address = remoteAddress

        // will be exactly 'IPC' if it's IPC
        // if it's an IP address, it will be the IP address
        // if it's a DNS name, the name will be resolved, and it will be DNS (IP)
        this.addressString = remoteAddressString
        this.addressPrettyString = remoteAddressString

        this.port = port
        this.reliable = reliable
        this.connectionTimeoutSec = connectionTimeoutSec

        val isSelfMachine = remoteAddress?.isLoopbackAddress == true || remoteAddress == lanAddress


        // IPC can be enabled TWO ways!
        // - config.enableIpc
        // - NULL remoteAddress
        // It is entirely possible that the server does not have IPC enabled!
        val autoChangeToIpc =
            (config.enableIpc && (remoteAddress == null || isSelfMachine)) || (!config.enableIpc && remoteAddress == null)

        // how long does the initial handshake take to connect
        var handshakeTimeoutSec = 5
        // how long before we COMPLETELY give up retrying
        var connectionTimoutInNanos = TimeUnit.SECONDS.toNanos(connectionTimeoutSec.toLong())

        if (DEBUG_CONNECTIONS) {
            // connections are extremely difficult to diagnose when the connection timeout is short
            connectionTimoutInNanos = TimeUnit.HOURS.toNanos(1).toLong()
            handshakeTimeoutSec = TimeUnit.HOURS.toSeconds(1).toInt()
        }

        val startTime = System.nanoTime()
        var success = false
        while (System.nanoTime() - startTime < connectionTimoutInNanos) {
            if (isShutdown()) {
                resetOnError()

                // If we are connecting indefinitely, we have to make sure to end the connection process
                val exception = ClientShutdownException("Unable to connect while shutting down, aborting connection retry attempt to server.")
                listenerManager.notifyError(exception)
                throw exception
            }

            if (slowDownForException) {
                // short delay, since it failed we want to limit the retry rate to something slower than "as fast as the CPU can do it"
                // we also want to go at SLIGHTLY slower that the aeron driver timeout frequency, this way - if there are connection or handshake issues, the server has the chance to expire the connections.
                // If we go TOO FAST, then the server will EVENTUALLY have aeron errors (since it can't keep up per client). We literally
                // want to have 1 in-flight handshake, per connection attempt, during the aeron connection timeout

                // ALSO, we want to make sure we DO NOT approach the linger timeout!
                aeronDriver.delayLingerTimeout(2)
            }



            // the handshake connection is closed when the handshake has an error, or it is finished
            var handshakeConnection: ClientHandshakeDriver? = null

            try {
                // always start the aeron driver inside the restart loop.
                // If we've already started the driver (on the first "start"), then this does nothing (slowDownForException also make this not "double check")
                if (slowDownForException) {
                    startDriver()
                }

                // throws a ConnectTimedOutException if the client cannot connect for any reason to the server handshake ports
                handshakeConnection = ClientHandshakeDriver.build(
                    aeronDriver = aeronDriver,
                    autoChangeToIpc = autoChangeToIpc,
                    remoteAddress = remoteAddress,
                    remoteAddressString = remoteAddressString,
                    remotePort = port,
                    port = config.port,
                    handshakeTimeoutSec = handshakeTimeoutSec,
                    reliable = reliable,
                    logger = logger
                )

                // Note: the pub/sub info is from the perspective of the SERVER
                val pubSub = handshakeConnection.pubSub
                val logInfo = pubSub.reverseForClient().getLogInfo(logger.isDebugEnabled)

                if (logger.isDebugEnabled) {
                    logger.debug { "Creating new handshake to $logInfo" }
                } else {
                    logger.info { "Creating new handshake to $logInfo" }
                }

                connect0(handshake, handshakeConnection, handshakeTimeoutSec)
                success = true
                slowDownForException = false

                // once we're done with the connection process, stop trying
                break
            } catch (e: ClientRetryException) {
                val message = if (connection0 == null) {
                    "Unable to connect, retrying..."
                } else if (isIPC) {
                    "Unable to connect to IPC, retrying..."
                } else {
                    "Unable to connect to UDP $remoteAddressPrettyString, retrying..."
                }

                if (logger.isTraceEnabled) {
                    logger.trace(e) { message }
                } else {
                    logger.info { message }
                }

                // maybe the aeron driver isn't running? (or isn't running correctly?)
                aeronDriver.closeIfSingle() // if we are the ONLY instance using the media driver, restart it

                slowDownForException = true
            } catch (e: ClientRejectedException) {
                aeronDriver.closeIfSingle() // if we are the ONLY instance using the media driver, restart it

                slowDownForException = true

                if (e.cause is ServerException) {
                    resetOnError()
                    val cause = e.cause!!
                    val wrapped = ClientException(cause.message!!)
                    listenerManager.notifyError(wrapped)
                    throw wrapped
                } else {
                    resetOnError()
                    listenerManager.notifyError(e)
                    throw e
                }
            } catch (e: Exception) {
                aeronDriver.closeIfSingle() // if we are the ONLY instance using the media driver, restart it
                listenerManager.notifyError(ClientException("[${handshake.connectKey}] : Un-recoverable error during handshake with $handshakeConnection. Aborting.", e))
                resetOnError()
                throw e
            }
        }

        if (!success) {
            endpointIsRunning.lazySet(false)

            if (System.nanoTime() - startTime < connectionTimoutInNanos) {

                val type = if (connection0 == null) {
                    "UNKNOWN"
                } else if (isIPC) {
                    "IPC"
                } else {
                    "$remoteAddressPrettyString:$port"
                }

                // we timed out. Throw the appropriate exception
                val exception = ClientTimedOutException("Unable to connect to the server at $type in $connectionTimeoutSec seconds, aborting connection attempt to server.")
                listenerManager.notifyError(exception)
                throw exception
            }

            // If we did not connect - throw an error. When `client.connect()` is called, either it connects or throws an error
            val exception = ClientRejectedException("The server did not respond or permit the connection attempt within $connectionTimeoutSec seconds, aborting connection retry attempt to server.")
            exception.cleanStackTrace()

            listenerManager.notifyError(exception)
            throw exception
        }
    }



    // the handshake process might have to restart this connection process.
    private suspend fun connect0(handshake: ClientHandshake<CONNECTION>, handshakeConnection: ClientHandshakeDriver, connectionTimeoutSec: Int) {
        // this will block until the connection timeout, and throw an exception if we were unable to connect with the server


        // throws(ConnectTimedOutException::class, ClientRejectedException::class, ClientException::class)
        val connectionInfo = handshake.hello(handshakeConnection, connectionTimeoutSec, uuid)

        // VALIDATE:: check to see if the remote connection's public key has changed!
        val validateRemoteAddress = if (handshakeConnection.pubSub.isIpc) {
            PublicKeyValidationState.VALID
        } else {
            crypto.validateRemoteAddress(address!!, addressString, connectionInfo.publicKey)
        }

        if (validateRemoteAddress == PublicKeyValidationState.INVALID) {
            handshakeConnection.close()

            val exception = ClientRejectedException("Connection to [$addressString] not allowed! Public key mismatch.")
            listenerManager.notifyError(exception)
            throw exception
        }


        // VALIDATE:: If the serialization DOES NOT match between the client/server, then the server will emit a log, and the
        // client will timeout. SPECIFICALLY.... we do not give class serialization/registration info to the client - in case the client
        // is rogue, we do not want to carelessly provide info.


        ///////////////
        ////   RMI
        ///////////////

        // we set up our kryo information once we connect to a server (using the server's kryo registration details)
        val kryoConfiguredFromServer = serialization.finishClientConnect(connectionInfo.kryoRegistrationDetails)
        if (kryoConfiguredFromServer == null) {
            handshakeConnection.close()

            // because we are getting the class registration details from the SERVER, this should never be the case.
            // It is still and edge case where the reconstruction of the registration details fails (maybe because of custom serializers)
            val exception = if (handshakeConnection.pubSub.isIpc) {
                ClientRejectedException("[${handshake.connectKey}] Connection to IPC has incorrect class registration details!!")
            } else {
                ClientRejectedException("[${handshake.connectKey}] Connection to [$addressString] has incorrect class registration details!!")
            }
            exception.cleanStackTraceInternal()
            listenerManager.notifyError(exception)
            throw exception
        }

        // every time we connect to a server, we have to reconfigure AND reassign the readKryos.
        readKryo = kryoConfiguredFromServer


        ///////////////
        ////   CONFIG THE CLIENT
        ///////////////


        // we are now connected, so we can connect to the NEW client-specific ports
        val clientConnection = ClientConnectionDriver.build(aeronDriver, connectionTimeoutSec, handshakeConnection, connectionInfo)

        // Note: the pub/sub info is from the perspective of the SERVER
        val pubSub = clientConnection.connectionInfo.reverseForClient()
        val logInfo = pubSub.getLogInfo(logger.isDebugEnabled)

        if (logger.isDebugEnabled) {
            logger.debug { "Creating new connection to $logInfo" }
        } else {
            logger.info { "Creating new connection to $logInfo" }
        }

        val newConnection: CONNECTION
        if (handshakeConnection.pubSub.isIpc) {
            newConnection = connectionFunc(ConnectionParams(uuid, this, clientConnection.connectionInfo, PublicKeyValidationState.VALID))
        } else {
            newConnection = connectionFunc(ConnectionParams(uuid, this, clientConnection.connectionInfo, validateRemoteAddress))
            address!!

            // NOTE: Client can ALWAYS connect to the server. The server makes the decision if the client can connect or not.

            logger.info { "[${handshakeConnection.details}] (${handshake.connectKey}) Connection (${newConnection.id}) adding new signature for [$addressString] : ${connectionInfo.publicKey.toHexString()}" }

            storage.addRegisteredServerKey(address!!, connectionInfo.publicKey)
        }

        connection0 = newConnection
        addConnection(newConnection)

        // tell the server our connection handshake is done, and the connection can now listen for data.
        // also closes the handshake (will also throw connect timeout exception)

        try {
            handshake.done(handshakeConnection, clientConnection, connectionTimeoutSec, handshakeConnection.details)
        } catch (e: Exception) {
            listenerManager.notifyError(ClientHandshakeException("[${handshakeConnection.details}] (${handshake.connectKey}) Connection (${newConnection.id}) to [$addressString] error during handshake", e))
            throw e
        }

        // finished with the handshake, so always close these!
        handshakeConnection.close()

        logger.debug { "[${handshakeConnection.details}] (${handshake.connectKey}) Connection (${newConnection.id}) to [$addressString] done with handshake." }

        // before we finish creating the connection, we initialize it (in case there needs to be logic that happens-before `onConnect` calls
        listenerManager.notifyInit(newConnection)

        // have to make a new thread to listen for incoming data!
        // SUBSCRIPTIONS ARE NOT THREAD SAFE! Only one thread at a time can poll them
        networkEventPoller.submit(
        action = {
            // if we initiate a disconnect manually, then there is no need to wait for aeron to verify it's closed
            // we only want to wait for aeron to verify it's closed if we are SUPPOSED to be connected, but there's a network blip
            if (!(shutdownEventPoller || newConnection.isClosedViaAeron())) {
                newConnection.poll()
            } else {
                // If the connection has either been closed, or has expired, it needs to be cleaned-up/deleted.
                logger.debug { "[${connection}] connection expired (cleanup)" }

                // the connection MUST be removed in the same thread that is processing events (it will be removed again in close, and that is expected)
                removeConnection(newConnection)

                // we already removed the connection, we can call it again without side affects
                newConnection.close()

                // remove ourselves from processing
                EventPoller.REMOVE
            }
        },
        onShutdown = {
            // this can be closed when the connection is remotely closed in ADDITION to manually closing
            logger.debug { "Client event dispatch closing..." }

            // we only need to run shutdown methods if there was a network outage or D/C
            if (!shutdownInProgress.value) {
                this@Client.closeSuspending(false)
            }

            // we can now call connect again
            endpointIsRunning.lazySet(false)
            pollerClosedLatch.countDown()

            logger.debug { "Closed the Network Event Poller..." }
        })

        listenerManager.notifyConnect(newConnection)
    }

    /**
     * true if the remote public key changed. This can be useful if specific actions are necessary when the key has changed.
     */
    val remoteKeyHasChanged: Boolean
        get() = connection.hasRemoteKeyChanged()

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
     * @return the connection id of this connection.
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
            val exception = TransmitException("Cannot send a message when there is no connection!")
            listenerManager.notifyError(exception)
            false
        }
    }

    /**
     * Sends a "ping" packet to measure **ROUND TRIP** time to the remote connection.
     *
     * @param function called when the ping returns (ie: update time/latency counters/metrics/etc)
     *
     * @return true if the ping was successfully sent to the client
     */
    suspend fun ping(pingTimeoutSeconds: Int = config.pingTimeoutSeconds, function: suspend Ping.() -> Unit): Boolean {
        val c = connection0

        if (c != null) {
            return super.ping(c, pingTimeoutSeconds, function)
        } else {
            val exception = TransmitException("Cannot send a ping when there is no connection!")
            listenerManager.notifyError(exception)
        }

        return false
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

    /**
     * Will throw an exception if there are resources that are still in use
     */
    fun checkForMemoryLeaks() {
        AeronDriver.checkForMemoryLeaks()
    }

    /**
     * By default, if you call close() on the client, it will shut down all parts of the endpoint (listeners, driver, event polling, etc).
     *
     * @param closeEverything if true, all parts of the client will be closed (listeners, driver, event polling, etc)
     */
    fun close(closeEverything: Boolean = true) {
        runBlocking {
            closeSuspending(closeEverything)
        }
    }

    override fun toString(): String {
        return "EndPoint [Client: $uuid]"
    }

    fun <R> use(block: (Client<CONNECTION>) -> R): R {
        return try {
            block(this)
        } finally {
            close()
        }
    }
}
