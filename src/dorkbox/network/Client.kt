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

import dorkbox.dns.DnsClient
import dorkbox.hex.toHexString
import dorkbox.netUtil.IP
import dorkbox.netUtil.IPv4
import dorkbox.netUtil.IPv6
import dorkbox.netUtil.dnsUtils.ResolvedAddressTypes
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.EventActionOperator
import dorkbox.network.aeron.EventCloseOperator
import dorkbox.network.aeron.EventPoller
import dorkbox.network.connection.*
import dorkbox.network.connection.IpInfo.Companion.formatCommonAddress
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTrace
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTraceInternal
import dorkbox.network.connection.session.SessionClient
import dorkbox.network.connection.session.SessionConnection
import dorkbox.network.connection.session.SessionManagerFull
import dorkbox.network.connection.session.SessionManagerNoOp
import dorkbox.network.exceptions.*
import dorkbox.network.handshake.ClientConnectionDriver
import dorkbox.network.handshake.ClientHandshake
import dorkbox.network.handshake.ClientHandshakeDriver
import dorkbox.network.ping.Ping
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.*

/**
 * The client is both SYNC and ASYNC. It starts off SYNC (blocks thread until it's done), then once it's connected to the server, it's
 * ASYNC.
 *
 * @param config these are the specific connection options
 * @param loggerName allows for a custom logger name for this endpoint (for when there are multiple endpoints)
 */
@Suppress("unused")
open class Client<CONNECTION : Connection>(config: ClientConfiguration = ClientConfiguration(), loggerName: String = Client::class.java.simpleName)
    : EndPoint<CONNECTION>(config, loggerName) {

    companion object {
        /**
         * Gets the version number.
         */
        const val version = Configuration.version

        /**
         * Ensures that the client (using the specified configuration) is NO LONGER running.
         *
         * NOTE: This method should only be used to check if a client is running for a DIFFERENT configuration than the currently running client
         *
         * By default, we will wait the [Configuration.connectionCloseTimeoutInSeconds] * 2 amount of time before returning.
         *
         * @return true if the media driver is STOPPED.
         */
        fun ensureStopped(configuration: Configuration): Boolean {
            val timeout = TimeUnit.SECONDS.toMillis(configuration.connectionCloseTimeoutInSeconds.toLong() * 2)

            val logger = LoggerFactory.getLogger(Client::class.java.simpleName)
            return AeronDriver.ensureStopped(configuration.copy(), logger, timeout)
        }

        /**
         * Checks to see if a client (using the specified configuration) is running.
         *
         * NOTE: This method should only be used to check if a client is running for a DIFFERENT configuration than the currently running client
         *
         * @return true if the media driver is active and running
         */
        fun isRunning(configuration: Configuration): Boolean {
            val logger = LoggerFactory.getLogger(Client::class.java.simpleName)
            return AeronDriver.isRunning(configuration.copy(), logger)
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

    /**
     *  - if the client is internally going to reconnect (because of a network error)
     *  - we have specified that we will run the disconnect logic
     *  - there is reconnect logic in the disconnect handler
     *
     * Then ultimately, we want to ignore the disconnect-handler reconnect (we do not want to have multiple reconnects happening concurrently)
     */
    @Volatile
    private var autoReconnect = false

    private val handshake = ClientHandshake(this, logger)

    @Volatile
    private var slowDownForException = false

    @Volatile
    private var stopConnectOnShutdown = false

    // is valid when there is a connection to the server, otherwise it is null
    @Volatile
    private var connection0: CONNECTION? = null

    private val string0: String by lazy {
        "EndPoint [Client: ${storage.publicKey.toHexString()}]"
    }

    final override fun newException(message: String, cause: Throwable?): Throwable {
        // +2 because we do not want to see the stack for the abstract `newException`
        val clientException = ClientException(message, cause)
        clientException.cleanStackTrace(2)
        return clientException
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
    fun reconnect() {
        if (autoReconnect) {
            // we must check if we should permit a MANUAL reconnect, because the auto-reconnect MIGHT ALSO re-connect!

            // autoReconnect will be "reset" when the connection closes. If in a happy state, then a manual reconnect is permitted.
            logger.info("Ignoring reconnect, auto-reconnect is in progress")
            return
        }


        if (connectionTimeoutSec == 0) {
            logger.info("Reconnecting...")
        } else {
            logger.info("Reconnecting... (timeout in $connectionTimeoutSec seconds)")
        }

        if (!isShutdown()) {
            // if we aren't closed already, close now.
            close(false)
            waitForClose()
        }

        connect(
            remoteAddress = address,
            remoteAddressString = addressString,
            remoteAddressPrettyString = addressPrettyString,
            port1 = port1,
            port2 = port2,
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
    fun connectIpc(connectionTimeoutSec: Int = 30) {
        connect(remoteAddress = null, // required!
                port1 = 0,
                port2 = 0,
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
     * @param port1 The network host port1 to connect to
     * @param port2 The network host port2 to connect to. The server uses this to work around NAT firewalls. By default, this is port1+1,
     *              but can also be configured independently. This is required, and must be different from port1.
     * @param connectionTimeoutSec wait for x seconds. 0 will wait indefinitely
     * @param reliable true if we want to create a reliable connection, can the lower-level network stack throw away data that has errors, (IE: real-time-voice traffic)
     *
     * @throws IllegalArgumentException if the remote address is invalid
     * @throws ClientTimedOutException if the client is unable to connect in x amount of time
     * @throws ClientRejectedException if the client connection is rejected
     */
     fun connect(
        remoteAddress: InetAddress,
        port1: Int,
        port2: Int = port1+1,
        connectionTimeoutSec: Int = 30,
        reliable: Boolean = true) {

        val remoteAddressString = when (remoteAddress) {
            is Inet4Address -> IPv4.toString(remoteAddress)
            is Inet6Address -> IPv6.toString(remoteAddress, true)
            else ->  throw IllegalArgumentException("Cannot connect to $remoteAddress It is an invalid address type!")
        }

        connect(remoteAddress = remoteAddress,
                remoteAddressString = remoteAddressString,
                remoteAddressPrettyString = remoteAddressString,
                port1 = port1,
                port2 = port2,
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
     * @param port1 The network host port1 to connect to
     * @param port2 The network host port2 to connect to. The server uses this to work around NAT firewalls. By default, this is port1+1,
     *              but can also be configured independently. This is required, and must be different from port1.
     * @param connectionTimeoutSec wait for x seconds. 0 will wait indefinitely
     * @param connectionTimeoutSec wait for x seconds. 0 will wait indefinitely
     * @param reliable true if we want to create a reliable connection, can the lower-level network stack throw away data that has errors, (IE: real-time-voice traffic)
     *
     * @throws IllegalArgumentException if the remote address is invalid
     * @throws ClientTimedOutException if the client is unable to connect in x amount of time
     * @throws ClientRejectedException if the client connection is rejected
     */
    fun connect(
        remoteAddress: String,
        port1: Int,
        port2: Int = port1+1,
        connectionTimeoutSec: Int = 30,
        reliable: Boolean = true) {
            fun connect(dnsResolveType: ResolvedAddressTypes) {
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
                        port1 = port1,
                        port2 = port2,
                        connectionTimeoutSec = connectionTimeoutSec,
                        reliable = reliable)
            }

            when {
                // this is default IPC settings
                remoteAddress.isEmpty() && config.enableIpc -> {
                    connect(remoteAddress = null, // required!
                            port1 = 0,
                            port2 = 0,
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
     * @param port1 The network host port1 to connect to
     * @param port2 The network host port2 to connect to. The server uses this to work around NAT firewalls. By default, this is port1+1,
     *              but can also be configured independently. This is required, and must be different from port1.
     * @param connectionTimeoutSec wait for x seconds. 0 will wait indefinitely
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
    private fun connect(
        remoteAddress: InetAddress?,
        remoteAddressString: String,
        remoteAddressPrettyString: String,
        port1: Int,
        port2: Int,
        connectionTimeoutSec: Int,
        reliable: Boolean = true)
    {
        // NOTE: it is critical to remember that Aeron DOES NOT like running from coroutines!
        if ((config.enableIPv4 || config.enableIPv6) && remoteAddress != null) {
            require(port1 != port2) { "port1 cannot be the same as port2" }
            require(port1 > 0) { "port1 must be > 0" }
            require(port2 > 0) { "port2 must be > 0" }
            require(port1 < 65535) { "port1 must be < 65535" }
            require(port2 < 65535) { "port2 must be < 65535" }
        }

        require(connectionTimeoutSec >= 0) { "connectionTimeoutSec '$connectionTimeoutSec' is invalid. It must be >=0" }

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


        // on the client, we must GUARANTEE that the disconnect/close completes before NEW connect begins.
        // we will know this if we are running inside an INTERNAL dispatch that is NOT the connect dispatcher!
        if (eventDispatch.isDispatch() && !eventDispatch.CONNECT.isDispatch()) {
            // only re-dispatch if we are on the event dispatch AND it's not the CONNECT one
            // if we are on an "outside" thread, then we don't care.
            eventDispatch.CONNECT.launch {
                connect(
                    remoteAddress = remoteAddress,
                    remoteAddressString = remoteAddressString,
                    remoteAddressPrettyString = remoteAddressPrettyString,
                    port1 = port1,
                    port2 = port2,
                    connectionTimeoutSec = connectionTimeoutSec,
                    reliable = reliable)
            }
            return
        }


        // the lifecycle of a client is the ENDPOINT (measured via the network event poller) and CONNECTION (measure from connection closed)
        // if we are reconnecting, then we do not want to wait for the ENDPOINT to close first!
        if (!waitForEndpointShutdown()) {
            if (endpointIsRunning.value) {
                listenerManager.notifyError(ServerException("Unable to start, the client is already running!"))
            } else {
                listenerManager.notifyError(ClientException("Unable to connect the client!"))
            }
            return
        }

        config as ClientConfiguration


        connection0 = null


        // we are done with initial configuration, now initialize aeron and the general state of this endpoint
        // this also makes sure that the dispatchers are still active.
        // Calling `client.close()` will shutdown the dispatchers (and a new client instance must be created)
        try {
            stopConnectOnShutdown = false
            startDriver()
            initializeState()
        } catch (e: Exception) {
            listenerManager.notifyError(ClientException("Unable to start the client!", e))
            resetOnError()
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

        this.port1 = port1
        this.port2 = port2

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
        var handshakeTimeoutNs = aeronDriver.publicationConnectionTimeoutNs() + aeronDriver.lingerNs()
        // how long before we COMPLETELY give up retrying. A '0' means try forever.
        var connectionTimoutInNs = TimeUnit.SECONDS.toNanos(connectionTimeoutSec.toLong())

        if (DEBUG_CONNECTIONS) {
            // connections are extremely difficult to diagnose when the connection timeout is short
            connectionTimoutInNs = TimeUnit.HOURS.toNanos(1)
            handshakeTimeoutNs = TimeUnit.HOURS.toNanos(1)
        }

        val startTime = System.nanoTime()
        var success = false
        while (!stopConnectOnShutdown && (connectionTimoutInNs == 0L || System.nanoTime() - startTime < connectionTimoutInNs)) {

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
                    config = config,
                    aeronDriver = aeronDriver,
                    autoChangeToIpc = autoChangeToIpc,
                    remoteAddress = remoteAddress,
                    remoteAddressString = remoteAddressString,
                    remotePort1 = port1,
                    clientListenPort = config.port,
                    remotePort2 = port2,
                    handshakeTimeoutNs = handshakeTimeoutNs,
                    reliable = reliable,
                    logger = logger
                )

                val pubSub = handshakeConnection.pubSub
                val logInfo = pubSub.getLogInfo(logger)

                if (logger.isDebugEnabled) {
                    logger.debug("Creating new handshake to $logInfo")
                } else {
                    logger.info("Creating new handshake to $logInfo")
                }

                connect0(handshake, handshakeConnection, handshakeTimeoutNs)
                success = true
                slowDownForException = false

                // once we're done with the connection process, stop trying
                break
            } catch (e: ClientRetryException) {
                if (stopConnectOnShutdown) {
                    aeronDriver.closeIfSingle()
                    break
                }

                val inSeconds = TimeUnit.NANOSECONDS.toSeconds(handshakeTimeoutNs)
                val message = if (isIPC) {
                    "Unable to connect to IPC in $inSeconds seconds, retrying..."
                } else {
                    "Unable to connect to $remoteAddressPrettyString ($port1|$port2) in $inSeconds seconds, retrying..."
                }

                if (logger.isTraceEnabled) {
                    logger.trace(message, e)
                } else {
                    logger.info(message)
                }

                // maybe the aeron driver isn't running? (or isn't running correctly?)
                aeronDriver.closeIfSingle() // if we are the ONLY instance using the media driver, stop it

                slowDownForException = true
            } catch (e: ClientRejectedException) {
                aeronDriver.closeIfSingle() // if we are the ONLY instance using the media driver, stop it

                if (stopConnectOnShutdown) {
                    break
                }

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

                if (stopConnectOnShutdown) {
                    break
                }

                val type = if (isIPC) {
                    "IPC"
                } else {
                    "$remoteAddressPrettyString:$port1:$port2"
                }


                listenerManager.notifyError(ClientException("[${handshake.connectKey}] : Un-recoverable error during handshake with $type. Aborting.", e))
                resetOnError()
                throw e
            }
        }

        if (!success) {
            endpointIsRunning.lazySet(false)

            if (stopConnectOnShutdown) {
                val exception = ClientException("Client closed during connection attempt. Aborting connection attempts.").cleanStackTrace(3)
                listenerManager.notifyError(exception)
                // if we are waiting for this connection to connect (on a different thread, for example), make sure to release it.
                closeLatch.countDown()
                throw exception
            }

            if (System.nanoTime() - startTime < connectionTimoutInNs) {
                val type = if (isIPC) {
                    "IPC"
                } else {
                    "$remoteAddressPrettyString:$port1:$port2"
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
    private fun connect0(handshake: ClientHandshake<CONNECTION>, handshakeConnection: ClientHandshakeDriver, handshakeTimeoutNs: Long) {
        // this will block until the connection timeout, and throw an exception if we were unable to connect with the server


        // throws(ConnectTimedOutException::class, ClientRejectedException::class, ClientException::class)
        val connectionInfo = handshake.hello(
            endPoint = this,
            handshakeConnection = handshakeConnection,
            handshakeTimeoutNs = handshakeTimeoutNs
        )

        // VALIDATE:: check to see if the remote connection's public key has changed!
        val validateRemoteAddress = if (handshakeConnection.pubSub.isIpc) {
            PublicKeyValidationState.VALID
        } else {
            crypto.validateRemoteAddress(address!!, addressString, connectionInfo.publicKey)
        }

        if (validateRemoteAddress == PublicKeyValidationState.INVALID) {
            handshakeConnection.close(this)

            val exception = ClientRejectedException("Connection to [$addressString] not allowed! Public key mismatch.")
            listenerManager.notifyError(exception)
            throw exception
        }


        // VALIDATE:: If the serialization DOES NOT match between the client/server, then the server will emit a log, and the
        // client will timeout. SPECIFICALLY.... we do not give class serialization/registration info to the client - in case the client
        // is rogue, we do not want to carelessly provide info.


        // NOTE: this can change depending on what the server specifies!
        // should we queue messages during a reconnect? This is important if the client/server connection is unstable
        if (connectionInfo.enableSession && sessionManager is SessionManagerNoOp) {
            sessionManager = SessionManagerFull(config, listenerManager as ListenerManager<SessionConnection>, aeronDriver, connectionInfo.sessionTimeout)
        } else if (!connectionInfo.enableSession && sessionManager is SessionManagerFull) {
            sessionManager = SessionManagerNoOp()
        }

        ///////////////
        ////   RMI
        ///////////////

        try {
            // only have ot do one
            serialization.finishClientConnect(connectionInfo.kryoRegistrationDetails)
        } catch (e: Exception) {
            handshakeConnection.close(this)

            // because we are getting the class registration details from the SERVER, this should never be the case.
            // It is still and edge case where the reconstruction of the registration details fails (maybe because of custom serializers)
            val exception = if (handshakeConnection.pubSub.isIpc) {
                ClientRejectedException("[${handshake.connectKey}] Connection to IPC has incorrect class registration details!!", e)
            } else {
                ClientRejectedException("[${handshake.connectKey}] Connection to [$addressString] has incorrect class registration details!!", e)
            }

            exception.cleanStackTraceInternal()
            listenerManager.notifyError(exception)
            throw exception
        }

        // we set up our kryo information once we connect to a server (using the server's kryo registration details)

        // every time we connect to a server, we have to reconfigure AND reassign kryo
        readKryo = serialization.newReadKryo()


        ///////////////
        ////   CONFIG THE CLIENT
        ///////////////


        // we are now connected, so we can connect to the NEW client-specific ports
        val clientConnection = ClientConnectionDriver.build(
            aeronDriver = aeronDriver,
            handshakeTimeoutNs = handshakeTimeoutNs,
            handshakeConnection = handshakeConnection,
            connectionInfo = connectionInfo,
            port2Server = port2
        )

        val pubSub = clientConnection.connectionInfo
        val logInfo = pubSub.getLogInfo(logger)
        val connectionType = if (this is SessionClient) "session connection" else "connection"
        if (logger.isDebugEnabled) {
            logger.debug("Creating new $connectionType to $logInfo")
        } else {
            logger.info("Creating new $connectionType to $logInfo")
        }

        val newConnection = newConnection(ConnectionParams(
            connectionInfo.publicKey,
            this,
            clientConnection.connectionInfo,
            validateRemoteAddress,
            connectionInfo.secretKey
        ))

        sessionManager.onNewConnection(newConnection)

        if (!handshakeConnection.pubSub.isIpc) {
            // NOTE: Client can ALWAYS connect to the server. The server makes the decision if the client can connect or not.
            val connType = if (newConnection is SessionConnection) "Session connection" else "Connection"
            if (logger.isTraceEnabled) {
                logger.trace("[${handshakeConnection.details}] (${handshake.connectKey}) $connType (${newConnection.id}) adding new signature for [$addressString -> ${connectionInfo.publicKey.toHexString()}]")
            } else if (logger.isDebugEnabled) {
                logger.debug("[${handshakeConnection.details}] $connType (${newConnection.id}) adding new signature for [$addressString -> ${connectionInfo.publicKey.toHexString()}]")
            } else if (logger.isInfoEnabled) {
                logger.info("[${handshakeConnection.details}] $connType adding new signature for [$addressString -> ${connectionInfo.publicKey.toHexString()}]")
            }

            storage.addRegisteredServerKey(address!!, connectionInfo.publicKey)
        }

        // tell the server our connection handshake is done, and the connection can now listen for data.
        // also closes the handshake (will also throw connect timeout exception)

        try {
            handshake.done(
                endPoint = this,
                handshakeConnection, clientConnection,
                handshakeTimeoutNs = handshakeTimeoutNs,
                logInfo = handshakeConnection.details
            )
        } catch (e: Exception) {
            listenerManager.notifyError(ClientHandshakeException("[${handshakeConnection.details}] (${handshake.connectKey}) Connection (${newConnection.id}) to [$addressString] error during handshake", e))
            throw e
        }

        // finished with the handshake, so always close these!
        handshakeConnection.close(this)

        val connType = if (newConnection is SessionConnection) "Session connection" else "Connection"
        if (logger.isTraceEnabled) {
            logger.debug("[${handshakeConnection.details}] (${handshake.connectKey}) $connType (${newConnection.id}) done with handshake.")
        } else if (logger.isDebugEnabled) {
            logger.debug("[${handshakeConnection.details}] $connType (${newConnection.id}) done with handshake.")
        }

        connection0 = newConnection
        newConnection.setImage()


        // in the specific case of using sessions, we don't want to call 'init' or `connect` for a connection that is resuming a session
        // when applicable - we ALSO want to restore RMI objects BEFORE the connection is fully setup!
        val newSession = sessionManager.onInit(newConnection)

        // before we finish creating the connection, we initialize it (in case there needs to be logic that happens-before `onConnect` calls
        if (newSession) {
            listenerManager.notifyInit(newConnection)
        }

        // this enables the connection to start polling for messages
        addConnection(newConnection)

        // if we shutdown/close before the poller starts, we don't want to block forever
        pollerClosedLatch = CountDownLatch(1)
        networkEventPoller.submit(
        action = object : EventActionOperator  {
            override fun invoke(): Int {
                val connection = connection0

                // if we initiate a disconnect manually, then there is no need to wait for aeron to verify it's closed
                // we only want to wait for aeron to verify it's closed if we are SUPPOSED to be connected, but there's a network blip
                return if (connection != null) {
                    if (!shutdownEventPoller && connection.canPoll()) {
                        connection.poll()
                    } else {
                        // If the connection has either been closed, or has expired, it needs to be cleaned-up/deleted.
                        logger.error("[${connection}] connection expired (cleanup). shutdownEventPoller=$shutdownEventPoller isClosed()=${connection.isClosed()} isClosedWithTimeout=${connection.isClosedWithTimeout()}")

                        if (logger.isDebugEnabled) {
                            logger.debug("[{}] connection expired (cleanup)", connection)
                        }
                        // remove ourselves from processing
                        EventPoller.REMOVE
                    }
                } else {
                    // remove ourselves from processing
                    EventPoller.REMOVE
                }
            }
        },
        onClose = object : EventCloseOperator {
            override fun invoke() {
                val connection = connection0
                if (connection == null) {
                    logger.error("Unable to continue, as the connection has been removed before event dispatch shutdown!")
                    return
                }

                val mustRestartDriverOnError = aeronDriver.internal.mustRestartDriverOnError
                val dirtyDisconnectWithSession = (this@Client is SessionClient) && !shutdownEventPoller && connection.isDirtyClose()

                autoReconnect = mustRestartDriverOnError || dirtyDisconnectWithSession

                if (mustRestartDriverOnError) {
                    logger.error("[{}] Critical driver error detected, reconnecting client", connection)
                } else if (dirtyDisconnectWithSession) {
                    logger.error("[{}] Dirty disconnect detected, reconnecting client", connection)
                }

                // this can be closed when the connection is remotely closed in ADDITION to manually closing
                if (logger.isDebugEnabled) {
                    logger.debug("[{}] Client event dispatch closing (in progress: $shutdownInProgress) ...", connection)
                }

                // we only need to run shutdown methods if there was a network outage or D/C
                if (!shutdownInProgress.value) {
                    // this is because we restart automatically on driver errors/weird timeouts
                    this@Client.close(closeEverything = false, sendDisconnectMessage = true, releaseWaitingThreads = !autoReconnect)
                }


                // we can now call connect again
                endpointIsRunning.lazySet(false)
                pollerClosedLatch.countDown()


                connection0 = null

                if (autoReconnect) {
                    // clients can reconnect automatically ONLY if there are driver errors, otherwise it's explicit!
                    eventDispatch.CLOSE.launch {
                        logger.error("MUST AUTORECONNECT STARTING ON CLOSE ***********************************")
                        waitForEndpointShutdown()

                        // also wait for everyone else to shutdown!!
                        aeronDriver.internal.endPointUsages.forEach {
                            if (it !== this@Client) {
                                it.waitForEndpointShutdown()
                            }
                        }

                        // if we restart/reconnect too fast, errors from the previous run will still be present!
                        aeronDriver.delayLingerTimeout()

                        if (connectionTimeoutSec == 0) {
                            logger.info("Reconnecting...", Exception())
                        } else {
                            logger.info("Reconnecting... (timeout in $connectionTimeoutSec seconds)", Exception())
                        }

                        connect(
                            remoteAddress = address,
                            remoteAddressString = addressString,
                            remoteAddressPrettyString = addressPrettyString,
                            port1 = port1,
                            port2 = port2,
                            connectionTimeoutSec = connectionTimeoutSec,
                            reliable = reliable,
                        )
                    }
                }
                logger.debug("[{}] Closed the Network Event Poller task.", connection)
            }
        })

        listenerManager.notifyConnect(newConnection)

        if (!newSession) {
            (newConnection as SessionConnection).sendPendingMessages()
        }
    }

    /**
     * true if the remote public key changed. This can be useful if specific actions are necessary when the key has changed.
     */
    val remoteKeyHasChanged: Boolean
        get() = connection.remoteKeyChanged

    /**
     * true if this connection is an IPC connection
     */
    val isIPC: Boolean
        get() = address == null

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
    fun send(message: Any): Boolean {
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
    fun ping(function: Ping.() -> Unit): Boolean {
        val c = connection0

        if (c != null) {
            return super.ping(c, function)
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
            if (logger.isDebugEnabled) {
                logger.debug("Deleting remote IP address key $address")
            }
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
        stopConnectOnShutdown = true
        close(closeEverything = closeEverything, sendDisconnectMessage = true, releaseWaitingThreads = true)
    }

    override fun toString(): String {
        return string0
    }

    fun <R> use(block: (Client<CONNECTION>) -> R): R {
        return try {
            block(this)
        } finally {
            close()
        }
    }
}
