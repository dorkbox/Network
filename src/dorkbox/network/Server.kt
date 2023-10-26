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

import dorkbox.hex.toHexString
import dorkbox.network.aeron.*
import dorkbox.network.connection.*
import dorkbox.network.connection.IpInfo.Companion.IpListenType
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTrace
import dorkbox.network.connection.session.SessionManagerFull
import dorkbox.network.connection.session.SessionServer
import dorkbox.network.connectionType.ConnectionRule
import dorkbox.network.exceptions.ServerException
import dorkbox.network.handshake.ServerHandshake
import dorkbox.network.handshake.ServerHandshakePollers
import dorkbox.network.ipFilter.IpFilterRule
import dorkbox.network.rmi.RmiSupportServer
import org.slf4j.LoggerFactory
import java.util.concurrent.*

/**
 * The server can only be accessed in an ASYNC manner. This means that the server can only be used in RESPONSE to events. If you access the
 * server OUTSIDE of events, you will get inaccurate information from the server (such as getConnections())
 *
 * To put it bluntly, ONLY have the server do work inside a listener!
 *
 * @param config these are the specific connection options
 * @param connectionFunc allows for custom connection implementations defined as a unit function
 * @param loggerName allows for a custom logger name for this endpoint (for when there are multiple endpoints)
 */
open class Server<CONNECTION : Connection>(config: ServerConfiguration = ServerConfiguration(), loggerName: String = Server::class.java.simpleName)
    : EndPoint<CONNECTION>(config, loggerName) {

    companion object {
        /**
         * Gets the version number.
         */
        const val version = Configuration.version

        /**
         * Ensures that an endpoint (using the specified configuration) is NO LONGER running.
         *
         * NOTE: This method should only be used to check if a server is running for a DIFFERENT configuration than the currently running server
         *
         * By default, we will wait the [Configuration.connectionCloseTimeoutInSeconds] * 2 amount of time before returning.
         *
         * @return true if the media driver is STOPPED.
         */
        fun ensureStopped(configuration: ServerConfiguration): Boolean {
            val timeout = TimeUnit.SECONDS.toMillis(configuration.connectionCloseTimeoutInSeconds.toLong() * 2)

            val logger = LoggerFactory.getLogger(Server::class.java.simpleName)
            return AeronDriver.ensureStopped(configuration.copy(), logger, timeout)
        }

        /**
         * Checks to see if a server (using the specified configuration) is running.
         *
         * NOTE: This method should only be used to check if a server is running for a DIFFERENT configuration than the currently running server
         *
         * @return true if the media driver is active and running
         */
        fun isRunning(configuration: ServerConfiguration): Boolean {
            val logger = LoggerFactory.getLogger(Server::class.java.simpleName)
            return AeronDriver.isRunning(configuration.copy(), logger)
        }

        init {
            // Add this project to the updates system, which verifies this class + UUID + version information
            dorkbox.updates.Updates.add(Server::class.java, "90a2c3b1e4fa41ea90d31fbdf8b2c6ef", version)
        }
    }

    /**
     * Methods supporting Remote Method Invocation and Objects for GLOBAL scope objects (different than CONNECTION scope objects)
     */
    val rmiGlobal = RmiSupportServer(logger, rmiGlobalSupport)

    /**
     * Maintains a thread-safe collection of rules used to define the connection type with this server.
     */
    private val connectionRules = CopyOnWriteArrayList<ConnectionRule>()

    /**
     * the IP address information, if available.
     */
    internal val ipInfo = IpInfo(config)

    @Volatile
    internal lateinit var handshake: ServerHandshake<CONNECTION>

    private val string0: String by lazy {
        "EndPoint [Server: ${storage.publicKey.toHexString()}]"
    }

    init {
        if (this is SessionServer) {
             // only set this if we need to
             sessionManager = SessionManagerFull(config, aeronDriver, config.sessionTimeoutSeconds)
        }
    }

    final override fun newException(message: String, cause: Throwable?): Throwable {
        // +2 because we do not want to see the stack for the abstract `newException`
        val serverException = ServerException(message, cause)
        serverException.cleanStackTrace(2)
        return serverException
    }

    /**
     * Binds the server IPC only, using the previously set AERON configuration
     */
    fun bindIpc() {
        if (!config.enableIpc) {
            logger.warn("IPC explicitly requested, but not enabled. Enabling IPC...")
            // we explicitly requested IPC, make sure it's enabled
            config.contextDefined = false
            config.enableIpc = true
            config.contextDefined = true
        }

        if (config.enableIPv4) { logger.warn("IPv4 is enabled, but only IPC will be used.") }
        if (config.enableIPv6) { logger.warn("IPv6 is enabled, but only IPC will be used.") }

        bind(0, 0, true)
    }

    /**
     * Binds the server to UDP ports, using the previously set AERON configuration
     *
     * @param port1 this is the network port which will be listening for incoming connections
     * @param port2 this is the network port that the server will use to work around NAT firewalls. By default, this is port1+1, but
     *              can also be configured independently. This is required, and must be different from port1.
     */
    @Suppress("DuplicatedCode")
    fun bind(port1: Int, port2: Int = port1+1) {
        if (config.enableIPv4 || config.enableIPv6) {
            require(port1 != port2) { "port1 cannot be the same as port2" }
            require(port1 > 0) { "port1 must be > 0" }
            require(port2 > 0) { "port2 must be > 0" }
            require(port1 < 65535) { "port1 must be < 65535" }
            require(port2 < 65535) { "port2 must be < 65535" }
        }

        bind(port1, port2, false)
    }

    @Suppress("DuplicatedCode")
    private fun bind(port1: Int, port2: Int, onlyBindIpc: Boolean) {
        // the lifecycle of a server is the ENDPOINT (measured via the network event poller)
        if (endpointIsRunning.value) {
            listenerManager.notifyError(ServerException("Unable to start, the server is already running!"))
            return
        }

        if (!waitForEndpointShutdown()) {
            listenerManager.notifyError(ServerException("Unable to start the server!"))
            return
        }

        try {
            startDriver()
            initializeState()
        }
        catch (e: Exception) {
            resetOnError()
            listenerManager.notifyError(ServerException("Unable to start the server!", e))
            return
        }

        this@Server.port1 = port1
        this@Server.port2 = port2

        config as ServerConfiguration

        // we are done with initial configuration, now initialize aeron and the general state of this endpoint

        val server = this@Server
        handshake = ServerHandshake(config, listenerManager, aeronDriver)

        val ipcPoller: AeronPoller = if (config.enableIpc || onlyBindIpc) {
            ServerHandshakePollers.ipc(server, handshake)
        } else {
            ServerHandshakePollers.disabled("IPC Disabled")
        }


        val ipPoller = if (onlyBindIpc) {
            ServerHandshakePollers.disabled("IPv4/6 Disabled")
        } else {
            when (ipInfo.ipType) {
                // IPv6 will bind to IPv4 wildcard as well, so don't bind both!
                IpListenType.IPWildcard   -> ServerHandshakePollers.ip6Wildcard(server, handshake)
                IpListenType.IPv4Wildcard -> ServerHandshakePollers.ip4(server, handshake)
                IpListenType.IPv6Wildcard -> ServerHandshakePollers.ip6(server, handshake)
                IpListenType.IPv4 -> ServerHandshakePollers.ip4(server, handshake)
                IpListenType.IPv6 -> ServerHandshakePollers.ip6(server, handshake)
                IpListenType.IPC  -> ServerHandshakePollers.disabled("IPv4/6 Disabled")
            }
        }


        logger.info(ipcPoller.info)
        logger.info(ipPoller.info)

        // if we shutdown/close before the poller starts, we don't want to block forever
        pollerClosedLatch = CountDownLatch(1)
        networkEventPoller.submit(
        action = object : EventActionOperator {
            override fun invoke(): Int {
                return if (!shutdownEventPoller) {
                    // NOTE: regarding fragment limit size. Repeated calls to '.poll' will reassemble a fragment.
                    //   `.poll(handler, 4)` == `.poll(handler, 2)` + `.poll(handler, 2)`

                    // this checks to see if there are NEW clients to handshake with
                    var pollCount = ipcPoller.poll() + ipPoller.poll()

                    // this manages existing clients (for cleanup + connection polling). This has a concurrent iterator,
                    // so we can modify this as we go
                    connections.forEach { connection ->
                        if (!(connection.isClosed() || connection.isClosedWithTimeout()) ) {
                            // Otherwise, poll the connection for messages
                            pollCount += connection.poll()
                        } else {
                            // If the connection has either been closed, or has expired, it needs to be cleaned-up/deleted.
                            if (logger.isDebugEnabled) {
                                logger.debug("[${connection}] connection expired (cleanup)")
                            }

                            // the connection MUST be removed in the same thread that is processing events (it will be removed again in close, and that is expected)
                            removeConnection(connection)

                            // we already removed the connection, we can call it again without side effects
                            connection.close()
                        }
                    }

                    pollCount
                } else {
                    // remove ourselves from processing
                    EventPoller.REMOVE
                }
            }
        },
        onClose = object : EventCloseOperator {
            override fun invoke() {
                val mustRestartDriverOnError = aeronDriver.internal.mustRestartDriverOnError
                logger.debug("Server event dispatch closing...")

                ipcPoller.close()
                ipPoller.close()

                // clear all the handshake info
                handshake.clear()


                // we only need to run shutdown methods if there was a network outage or D/C
                if (!shutdownInProgress.value) {
                    // this is because we restart automatically on driver errors
                    val standardClose = !mustRestartDriverOnError
                    this@Server.close(
                        closeEverything = false,
                        sendDisconnectMessage = standardClose,
                        notifyDisconnect = standardClose,
                        releaseWaitingThreads = standardClose
                    )
                }


                if (mustRestartDriverOnError) {
                    logger.error("Critical driver error detected, restarting server.")

                    eventDispatch.CLOSE.launch {
                        waitForEndpointShutdown()

                        // also wait for everyone else to shutdown!!
                        aeronDriver.internal.endPointUsages.forEach {
                            if (it !== this@Server) {
                                it.waitForEndpointShutdown()
                            }
                        }


                        // if we restart/reconnect too fast, errors from the previous run will still be present!
                        aeronDriver.delayLingerTimeout()

                        val p1 = this@Server.port1
                        val p2 = this@Server.port2

                        if (p1 == 0 && p2 == 0) {
                            bindIpc()
                        } else {
                            bind(p1, p2)
                        }
                    }
                }

                // we can now call bind again
                endpointIsRunning.lazySet(false)
                pollerClosedLatch.countDown()

                logger.debug("Closed the Network Event Poller...")
            }
        })
    }


    /**
     * Adds an IP+subnet rule that defines what type of connection this IP+subnet should have.
     * - NOTHING : Nothing happens to the in/out bytes
     * - COMPRESS: The in/out bytes are compressed with LZ4-fast
     * - COMPRESS_AND_ENCRYPT: The in/out bytes are compressed (LZ4-fast) THEN encrypted (AES-256-GCM)
     *
     * If no rules are defined, then for LOOPBACK, it will always be `COMPRESS` and for everything else it will always be `COMPRESS_AND_ENCRYPT`.
     *
     * If rules are defined, then everything by default is `COMPRESS_AND_ENCRYPT`.
     *
     * The compression algorithm is LZ4-fast, so there is a small performance impact for a very large gain
     * Compress   :       6.210 micros/op;  629.0 MB/s (output: 55.4%)
     * Uncompress :       0.641 micros/op; 6097.9 MB/s
     */
    fun addConnectionRules(vararg rules: ConnectionRule) {
        connectionRules.addAll(listOf(*rules))
    }

    /**
     * Adds an IP+subnet rule that defines if that IP+subnet is allowed/denied connectivity to this server.
     *
     * By default, if there are no filter rules, then all connections are allowed to connect
     * If there are filter rules - then ONLY connections for the filter that returns true are allowed to connect (all else are denied)
     *
     * If ANY filter rule that is applied returns true, then the connection is permitted
     *
     * This function will be called for **only** network clients (IPC client are excluded)
     */
    fun filter(ipFilterRule: IpFilterRule) {
        listenerManager.filter(ipFilterRule)
    }

    /**
     * Adds a function that will be called BEFORE a client/server "connects" with each other, and used to determine if a connection
     * should be allowed
     *
     * By default, if there are no filter rules, then all connections are allowed to connect
     * If there are filter rules - then ONLY connections for the filter that returns true are allowed to connect (all else are denied)
     *
     * It is the responsibility of the custom filter to write the error, if there is one
     *
     * If the function returns TRUE, then the connection will continue to connect.
     * If the function returns FALSE, then the other end of the connection will
     *   receive a connection error
     *
     *
     * If ANY filter rule that is applied returns true, then the connection is permitted
     *
     * This function will be called for **only** network clients (IPC client are excluded)
     */
    fun filter(function: CONNECTION.() -> Boolean)  {
        listenerManager.filter(function)
    }

    /**
     * Runs an action for each connection
     */
    fun forEachConnection(function: (connection: CONNECTION) -> Unit) {
        connections.forEach {
            function(it)
        }
    }

    /**
     * Will throw an exception if there are resources that are still in use
     */
    fun checkForMemoryLeaks() {
        AeronDriver.checkForMemoryLeaks()

        // make sure that we have de-allocated all connection data
        handshake.checkForMemoryLeaks()
    }

    /**
     * By default, if you call close() on the server, it will shut down all parts of the endpoint (listeners, driver, event polling, etc).
     *
     * @param closeEverything if true, all parts of the server will be closed (listeners, driver, event polling, etc)
     */
    fun close(closeEverything: Boolean = true) {
        close(
            closeEverything = closeEverything,
            sendDisconnectMessage = true,
            notifyDisconnect = true,
            releaseWaitingThreads = true
        )
    }

    override fun toString(): String {
       return string0
    }

    fun <R> use(block: (Server<CONNECTION>) -> R): R {
        return try {
            block(this)
        } finally {
            close()
        }
    }

//    /**
//     * Only called by the server!
//     *
//     * If we are loopback or the client is a specific IP/CIDR address, then we do things differently. The LOOPBACK address will never encrypt or compress the traffic.
//     */
//    // after the handshake, what sort of connection do we want (NONE, COMPRESS, ENCRYPT+COMPRESS)
//    fun getConnectionUpgradeType(remoteAddress: InetSocketAddress): Byte {
//        val address = remoteAddress.address
//        val size = connectionRules.size
//
//        // if it's unknown, then by default we encrypt the traffic
//        var connectionType = ConnectionProperties.COMPRESS_AND_ENCRYPT
//        if (size == 0 && address == IPv4.LOCALHOST) {
//            // if nothing is specified, then by default localhost is compression and everything else is encrypted
//            connectionType = ConnectionProperties.COMPRESS
//        }
//        for (i in 0 until size) {
//            val rule = connectionRules[i] ?: continue
//            if (rule.matches(remoteAddress)) {
//                connectionType = rule.ruleType()
//                break
//            }
//        }
//        logger.debug("Validating {}  Permitted type is: {}", remoteAddress, connectionType)
//        return connectionType.type
//    }
}
