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
import dorkbox.network.aeron.mediaDriver.ClientConnectionDriver
import dorkbox.network.aeron.mediaDriver.ClientHandshakeDriver
import dorkbox.network.connection.*
import dorkbox.network.connection.EventDispatcher.Companion.EVENT
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTrace
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTraceInternal
import dorkbox.network.exceptions.*
import dorkbox.network.handshake.ClientHandshake
import dorkbox.network.ping.Ping
import dorkbox.network.ping.PingManager
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

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
            AeronDriver(configuration, logger).use {
                it.ensureStopped(timeout, 500)
            }
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
            AeronDriver(configuration, logger).use {
                it.isRunning()
            }
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
    @Volatile
    var remoteAddress: InetAddress? = IPv4.LOCALHOST
        private set

    /**
     * the remote address, as a string.
     */
    @Volatile
    var remoteAddressString: String = "UNKNOWN"
        private set

    @Volatile
    private var slowDownForException = false

    @Volatile
    private var isConnected = false

    // is valid when there is a connection to the server, otherwise it is null
    private var connection0: CONNECTION? = null

    // This is set by the client so if there is a "connect()" call in the disconnect callback, we can have proper
    // lock-stop ordering for how disconnect and connect work with each-other
    // GUARANTEE that the callbacks for a NEW connect happen AFTER the previous 'onDisconnect' is finished.
    // a CDL is used because it doesn't matter the order in which it's called (as it will always ensure it's correct)
    private val disconnectInProgress = atomic<dorkbox.util.sync.CountDownLatch?>(null)

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
     fun connect(
        remoteAddress: InetAddress,
        connectionTimeoutSec: Int = 30,
        reliable: Boolean = true) = runBlocking {

        val remoteAddressString = when (remoteAddress) {
            is Inet4Address -> IPv4.toString(remoteAddress)
            is Inet6Address -> IPv6.toString(remoteAddress, true)
            else ->  throw IllegalArgumentException("Cannot connect to $remoteAddress It is an invalid address type!")
        }


        // Default IPC ports are flipped because they are in the perspective of the SERVER
        connect(remoteAddress = remoteAddress,
                remoteAddressString = remoteAddressString,
                remoteAddressPrettyString = remoteAddressString,
                connectionTimeoutSec = connectionTimeoutSec,
                reliable = reliable)
    }

    /**
     * Will attempt to connect to the server via IPC, with a default 30 second connection timeout and will block until completed.
     *
     * @param ipcId The IPC address for the client to connect to
     * @param connectionTimeoutSec wait for x seconds. 0 will wait indefinitely.
     *
     * @throws IllegalArgumentException if the remote address is invalid
     * @throws ClientTimedOutException if the client is unable to connect in x amount of time
     * @throws ClientRejectedException if the client connection is rejected
     */
    @Suppress("DuplicatedCode")
    fun connectIpc(
        ipcId: Int = AeronDriver.IPC_HANDSHAKE_STREAM_ID,
        connectionTimeoutSec: Int = 30) = runBlocking {

        connect(remoteAddress = null, // required!
                remoteAddressString = IPC_NAME,
                remoteAddressPrettyString = IPC_NAME,
                ipcId = ipcId,
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
    fun connect(
        remoteAddress: String = "",
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
                        connectionTimeoutSec = connectionTimeoutSec,
                        reliable = reliable)
            }

            when {
                // this is default IPC settings
                remoteAddress.isEmpty() && config.enableIpc -> {
                    connectIpc(connectionTimeoutSec = connectionTimeoutSec)
                }

                // IPv6 takes precedence ONLY if it's enabled manually
                config.enableIPv6 -> connect(ResolvedAddressTypes.IPV6_ONLY)
                config.enableIPv4 -> connect(ResolvedAddressTypes.IPV4_ONLY)
                IPv4.isPreferred -> connect(ResolvedAddressTypes.IPV4_PREFERRED)
                IPv6.isPreferred -> connect(ResolvedAddressTypes.IPV6_PREFERRED)
                else -> connect(ResolvedAddressTypes.IPV4_PREFERRED)
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
     *
     * ### For the IPC (Inter-Process-Communication) it must be:
     *  - `connect()`
     *  - `connect("")`
     *  - `connectIpc()`
     *
     * ### Case does not matter, and "localhost" is the default.
     *
     * @param remoteAddress The network or if localhost, IPC address for the client to connect to
     * @param ipcId The IPC publication address for the client to connect to
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
    private suspend fun connect(
        remoteAddress: InetAddress? = null,
        remoteAddressString: String,
        remoteAddressPrettyString: String,
        // Default IPC ports are flipped because they are in the perspective of the SERVER
        ipcId: Int = AeronDriver.IPC_HANDSHAKE_STREAM_ID,
        connectionTimeoutSec: Int = 30,
        reliable: Boolean = true)
    {
        // on the client, we must GUARANTEE that the disconnect completes before NEW connect begins.
        if (!disconnectInProgress.compareAndSet(null, dorkbox.util.sync.CountDownLatch(1))) {
            val disconnectCDL = disconnectInProgress.value!!

            logger.debug { "Redispatching connect request!" }

            EventDispatcher.launch(EVENT.CONNECT) {
                logger.debug { "Redispatch connect request started!" }
                disconnectCDL.await(config.connectionCloseTimeoutInSeconds.toLong(), TimeUnit.SECONDS)
                connect(remoteAddress,
                        remoteAddressString,
                        remoteAddressPrettyString,
                        ipcId,
                        connectionTimeoutSec,
                        reliable)
            }
            return
        }

        // NOTE: it is critical to remember that Aeron DOES NOT like running from coroutines!
        config as ClientConfiguration

        require(connectionTimeoutSec >= 0) { "connectionTimeoutSec '$connectionTimeoutSec' is invalid. It must be >=0" }

        if (isConnected) {
            logger.error { "Unable to connect when already connected!" }
            return
        }

        connection0 = null

        // localhost/loopback IP might not always be 127.0.0.1 or ::1
        // will be null if it's IPC
        this.remoteAddress = remoteAddress

        // will be exactly 'IPC' if it's IPC
        // if it's an IP address, it will be the IP address
        // if it's a DNS name, the name will be resolved, and it will be DNS (IP)
        this.remoteAddressString = remoteAddressString

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
        try {
            startDriver()
        } catch (e: Exception) {
            logger.error(e) { "Unable to start the network driver" }
            return
        }

        val isSelfMachine = remoteAddress?.isLoopbackAddress == true || remoteAddress == lanAddress


        // IPC can be enabled TWO ways!
        // - config.enableIpc
        // - NULL remoteAddress
        // It is entirely possible that the server does not have IPC enabled!
        val autoChangeToIpc =
            (config.enableIpc && (remoteAddress == null || isSelfMachine)) || (!config.enableIpc && remoteAddress == null)

        val handshake = ClientHandshake(this, logger)

        var handshakeTimeoutSec = 5
        var timoutInNanos = TimeUnit.SECONDS.toNanos(connectionTimeoutSec.toLong())

        if (DEBUG_CONNECTIONS) {
            // connections are extremely difficult to diagnose when the connection timeout is short
            timoutInNanos = 0 // no timeout!
            handshakeTimeoutSec += TimeUnit.HOURS.toSeconds(1).toInt()
        }

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

            if (slowDownForException) {
                // short delay, since it failed we want to limit the retry rate to something slower than "as fast as the CPU can do it"
                // we also want to go at SLIGHTLY slower that the aeron driver timeout frequency, this way - if there are connection or handshake issues, the server has the chance to expire the connections.
                // If we go TOO FAST, then the server will EVENTUALLY have aeron errors (since it can't keep up per client). We literally
                // want to have 1 in-flight handshake, per connection attempt, during the aeron connection timeout

                // ALSO, we want to make sure we DO NOT approach the linger timeout!
                aeronDriver.delayLingerTimeout(2)
            }

            // we have to pre-set the type (which will ultimately get set to the correct type on success)
            var type = ""

            // the handshake connection is closed when the handshake has an error, or it is finished
            var handshakeConnection: ClientHandshakeDriver? = null

            try {
                // always start the aeron driver inside the restart loop.
                // If we've already started the driver (on the first "start"), then this does nothing (slowDownForException also make this not "double check")
                if (slowDownForException) {
                    startDriver()
                }

                // throws a ConnectTimedOutException if the client cannot connect for any reason to the server handshake ports
                handshakeConnection = ClientHandshakeDriver(
                    aeronDriver = aeronDriver,
                    autoChangeToIpc = autoChangeToIpc,
                    remoteAddress = remoteAddress,
                    remoteAddressString = remoteAddressString,
                    ipcId = ipcId,
                    config = config,
                    handshakeTimeoutSec = handshakeTimeoutSec,
                    reliable = reliable,
                    logger = logger
                )

                logger.info("")
                logger.info("")
                logger.info { "Connecting to ${handshakeConnection.infoPub}" }
                logger.info { "Connecting to ${handshakeConnection.infoSub}" }
                logger.info("")
                logger.info("")

                connect0(handshake, handshakeConnection, handshakeTimeoutSec)
                success = true
                slowDownForException = false

                // once we're done with the connection process, stop trying
                break
            } catch (e: ClientRetryException) {
                if (logger.isTraceEnabled) {
                    logger.trace(e) { "Unable to connect to $type, retrying..." }
                } else {
                    logger.info { "Unable to connect to $type, retrying..." }
                }

                handshake.reset()

                // maybe the aeron driver isn't running? (or isn't running correctly?)
                aeronDriver.closeIfSingle() // if we are the ONLY instance using the media driver, restart it

                slowDownForException = true
            } catch (e: ClientRejectedException) {
                aeronDriver.closeIfSingle() // if we are the ONLY instance using the media driver, restart it

                slowDownForException = true

                if (e.cause is ServerException) {
                    val cause = e.cause!!
                    val wrapped = ClientException(cause.message!!)
                    listenerManager.notifyError(wrapped)
                    throw wrapped
                } else {
                    listenerManager.notifyError(e)
                    throw e
                }
            } catch (e: Exception) {
                logger.error(e) { "[${handshake.connectKey}] : Un-recoverable error during handshake with $type. Aborting." }

                aeronDriver.closeIfSingle() // if we are the ONLY instance using the media driver, restart it

                listenerManager.notifyError(e)
                throw e
            }
        }

        if (!success) {
            if (System.nanoTime() - startTime < timoutInNanos) {
                // we timed out. Throw the appropriate exception
                val exception = ClientTimedOutException("Unable to connect to the server at $type in $connectionTimeoutSec seconds")
                logger.error(exception) { "Aborting connection attempt to server." }
                listenerManager.notifyError(exception)
                throw exception
            }

            // If we did not connect - throw an error. When `client.connect()` is called, either it connects or throws an error
            val exception = ClientRejectedException("The server did not respond or permit the connection attempt within $connectionTimeoutSec seconds")
            exception.cleanStackTrace()

            logger.error(exception) { "Aborting connection retry attempt to server." }
            listenerManager.notifyError(exception)
            throw exception
        }
    }

    // the handshake process might have to restart this connection process.
    private suspend fun connect0(handshake: ClientHandshake<CONNECTION>, handshakeConnection: ClientHandshakeDriver, connectionTimeoutSec: Int) {
        // this will block until the connection timeout, and throw an exception if we were unable to connect with the server

        // throws(ConnectTimedOutException::class, ClientRejectedException::class, ClientException::class)
        val connectionInfo = handshake.hello(aeronDriver, handshakeConnection, connectionTimeoutSec)

        // VALIDATE:: check to see if the remote connection's public key has changed!
        val validateRemoteAddress = if (handshakeConnection.isUsingIPC) {
            PublicKeyValidationState.VALID
        } else {
            crypto.validateRemoteAddress(remoteAddress!!, remoteAddressString, connectionInfo.publicKey)
        }

        if (validateRemoteAddress == PublicKeyValidationState.INVALID) {
            handshakeConnection.close()

            val exception = ClientRejectedException("Connection to [$remoteAddressString] not allowed! Public key mismatch.")
            logger.error(exception) { "Validation error" }
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
            val exception = if (handshakeConnection.isUsingIPC) {
                ClientRejectedException("[${handshake.connectKey}] Connection to IPC has incorrect class registration details!!")
            } else {
                ClientRejectedException("[${handshake.connectKey}] Connection to [$remoteAddressString] has incorrect class registration details!!")
            }
            exception.cleanStackTraceInternal()
            throw exception
        }

        // every time we connect to a server, we have to reconfigure AND reassign the readKryos.
        readKryo = kryoConfiguredFromServer
        streamingReadKryo = serialization.initKryo()


        ///////////////
        ////   CONFIG THE CLIENT
        ///////////////


        // we are now connected, so we can connect to the NEW client-specific ports
        val clientConnection = ClientConnectionDriver(handshakeConnection, connectionInfo)

        // have to rebuild the client pub/sub for the next part of the handshake (since it's a 1-shot deal for the server per session)
        // if we go SLOWLY (slower than the linger timeout), it will work. if we go quickly, this it will have problems (so we must do this!)
//        handshakeConnection.resetSession(logger)


        val newConnection: CONNECTION
        if (handshakeConnection.isUsingIPC) {
            newConnection = connectionFunc(ConnectionParams(this, clientConnection.connectionInfo(), PublicKeyValidationState.VALID))
        } else {
            newConnection = connectionFunc(ConnectionParams(this, clientConnection.connectionInfo(), validateRemoteAddress))
            remoteAddress!!

            // NOTE: Client can ALWAYS connect to the server. The server makes the decision if the client can connect or not.

            logger.info { "[${handshakeConnection.details}] (${handshake.connectKey}) Connection (${newConnection.id}) adding new signature for [$remoteAddressString] : ${connectionInfo.publicKey.toHexString()}" }

            storage.addRegisteredServerKey(remoteAddress!!, connectionInfo.publicKey)
        }

        // This is set by the client so if there is a "connect()" call in the disconnect callback, we can have proper
        // lock-stop ordering for how disconnect and connect work with each-other
        // GUARANTEE that the callbacks for 'onDisconnect' happens-before the 'onConnect'.
        // a CDL is used because it doesn't matter the order in which it's called (as it will always ensure it's correct)
        val lockStepForConnect = dorkbox.util.sync.CountDownLatch(1)

        val connectWaitTimeout = if (EventPoller.DEBUG) 99999999L else config.connectionCloseTimeoutInSeconds.toLong()


        //////////////
        ///  Extra Close action
        //////////////
        newConnection.closeAction = {
            // this is called whenever connection.close() is called by the framework or via client.close()
            isConnected = false

            // make sure to call our client.notifyConnect() callbacks execute

            // force us to wait until AFTER the connect logic has run. we MUST use a CDL. A mutex doesn't work properly
            lockStepForConnect.await(connectWaitTimeout, TimeUnit.SECONDS)

            EventDispatcher.launch(EVENT.DISCONNECT) {
                listenerManager.notifyDisconnect(connection)
            }

            // we must reset the disconnect-in-progress latch AND count down, so that reconnects can successfully reconnect
            val disconnectCDL = disconnectInProgress.getAndSet(null)!!
            disconnectCDL.countDown()
        }

        // before we finish creating the connection, we initialize it (in case there needs to be logic that happens-before `onConnect` calls
        EventDispatcher.launch(EVENT.INIT) {
            listenerManager.notifyInit(newConnection)
        }

        connection0 = newConnection
        addConnection(newConnection)

        // tell the server our connection handshake is done, and the connection can now listen for data.
        // also closes the handshake (will also throw connect timeout exception)

        try {
            handshake.done(aeronDriver, handshakeConnection, clientConnection, connectionTimeoutSec, handshakeConnection.details)
        } catch (e: Exception) {
            logger.error(e) { "[${handshakeConnection.details}] (${handshake.connectKey}) Connection (${newConnection.id}) to [$remoteAddressString] error during handshake" }
            throw e
        }

        // finished with the handshake, so always close these!
        handshakeConnection.close()

        isConnected = true

        logger.debug { "[${handshakeConnection.details}] (${handshake.connectKey}) Connection (${newConnection.id}) to [$remoteAddressString] done with handshake." }


        // have to make a new thread to listen for incoming data!
        // SUBSCRIPTIONS ARE NOT THREAD SAFE! Only one thread at a time can poll them

        // additionally, if we have MULTIPLE clients on the same machine, we are limited by the CPU core count. Ideally we want to share this among ALL clients within the same JVM so that we can support multiple clients/servers
        networkEventPoller.submit {
            if (!isShutdown()) {
                if (!newConnection.isClosedViaAeron()) {
                    // Polls the AERON media driver subscription channel for incoming messages
                    newConnection.poll()
                } else {
                    // If the connection has either been closed, or has expired, it needs to be cleaned-up/deleted.
                    logger.debug { "[${handshakeConnection.details}] connection expired" }

                    // NOTE: We do not shutdown the client!! The client is only closed by explicitly calling `client.close()`
                    EventDispatcher.launch(EVENT.CLOSE) {
                        newConnection.close(enableRemove = true)
                    }

                    // remove ourselves from processing
                    EventPoller.REMOVE
                }
            } else {
                // remove ourselves from processing
                EventPoller.REMOVE
            }
        }

        // if something inside-of listenerManager.notifyConnect is blocking or suspends, then polling will never happen!
        // This must be on a different thread
        EventDispatcher.launch(EVENT.CONNECT) {
            // what happens if the disconnect runs INSIDE the connect?

            listenerManager.notifyConnect(newConnection)

            // now the disconnect logic can run because we are done with the connect logic.
            lockStepForConnect.countDown()
        }
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
        val c = connection0

        return if (c != null) {
            runBlocking {
                c.send(message)
            }
        } else {
            val exception = ClientException("Cannot send a message when there is no connection!")
            logger.error(exception) { "No connection!" }
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
    suspend fun ping(pingTimeoutSeconds: Int = PingManager.DEFAULT_TIMEOUT_SECONDS, function: suspend Ping.() -> Unit): Boolean {
        val c = connection0

        if (c != null) {
            return super.ping(c, pingTimeoutSeconds, function)
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

    final override suspend fun close0() {
        // no impl
    }

    fun <R> use(block: (Client<CONNECTION>) -> R): R {
        return try {
            block(this)
        } finally {
            close()
            runBlocking {
                waitForClose()
                logger.error { "finished close event" }
            }
        }
    }
}
