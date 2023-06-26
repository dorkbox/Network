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

import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.AeronPoller
import dorkbox.network.aeron.EventPoller
import dorkbox.network.connection.Connection
import dorkbox.network.connection.ConnectionParams
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.IpInfo
import dorkbox.network.connection.IpInfo.Companion.IpListenType
import dorkbox.network.connectionType.ConnectionRule
import dorkbox.network.exceptions.ServerException
import dorkbox.network.handshake.ServerHandshake
import dorkbox.network.handshake.ServerHandshakePollers
import dorkbox.network.ipFilter.IpFilterRule
import dorkbox.network.rmi.RmiSupportServer
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
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
open class Server<CONNECTION : Connection>(
        config: ServerConfiguration = ServerConfiguration(),
        connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION,
        loggerName: String = Server::class.java.simpleName)
    : EndPoint<CONNECTION>(config, connectionFunc, loggerName) {

    /**
     * The server can only be accessed in an ASYNC manner. This means that the server can only be used in RESPONSE to events. If you access the
     * server OUTSIDE of events, you will get inaccurate information from the server (such as getConnections())
     *
     * To put it bluntly, ONLY have the server do work inside a listener!
     *
     * @param config these are the specific connection options
     * @param loggerName allows for a custom logger name for this endpoint (for when there are multiple endpoints)
     * @param connectionFunc allows for custom connection implementations defined as a unit function
     */
    constructor(config: ServerConfiguration,
                loggerName: String,
                connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION)
            : this(config, connectionFunc, loggerName)


    /**
     * The server can only be accessed in an ASYNC manner. This means that the server can only be used in RESPONSE to events. If you access the
     * server OUTSIDE of events, you will get inaccurate information from the server (such as getConnections())
     *
     * To put it bluntly, ONLY have the server do work inside of a listener!
     *
     * @param config these are the specific connection options
     * @param connectionFunc allows for custom connection implementations defined as a unit function
     */
    constructor(config: ServerConfiguration,
                connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION)
            : this(config, connectionFunc, Server::class.java.simpleName)


    /**
     * The server can only be accessed in an ASYNC manner. This means that the server can only be used in RESPONSE to events. If you access the
     * server OUTSIDE of events, you will get inaccurate information from the server (such as getConnections())
     *
     * To put it bluntly, ONLY have the server do work inside a listener!
     *
     * @param config these are the specific connection options
     * @param loggerName allows for a custom logger name for this endpoint (for when there are multiple endpoints)
     */
    constructor(config: ServerConfiguration,
                loggerName: String = Server::class.java.simpleName)
            : this(config,
                   {
                        @Suppress("UNCHECKED_CAST")
                        Connection(it) as CONNECTION
                   },
                   loggerName)

    /**
     * The server can only be accessed in an ASYNC manner. This means that the server can only be used in RESPONSE to events. If you access the
     * server OUTSIDE of events, you will get inaccurate information from the server (such as getConnections())
     *
     * To put it bluntly, ONLY have the server do work inside a listener!
     *
     * @param config these are the specific connection options
     */
    constructor(config: ServerConfiguration)
            : this(config,
                   {
                        @Suppress("UNCHECKED_CAST")
                        Connection(it) as CONNECTION
                   },
                   Server::class.java.simpleName)


    companion object {
        /**
         * Gets the version number.
         */
        const val version = "6.4"

        /**
         * Ensures that an endpoint (using the specified configuration) is NO LONGER running.
         *
         * NOTE: This method should only be used to check if a server is running for a DIFFERENT configuration than the currently running server
         *
         * By default, we will wait the [Configuration.connectionCloseTimeoutInSeconds] * 2 amount of time before returning.
         *
         * @return true if the media driver is STOPPED.
         */
        fun ensureStopped(configuration: ServerConfiguration): Boolean = runBlocking {
            val timeout = TimeUnit.SECONDS.toMillis(configuration.connectionCloseTimeoutInSeconds.toLong() * 2)

            val logger = KotlinLogging.logger(Server::class.java.simpleName)
            AeronDriver.ensureStopped(configuration, logger, timeout)
        }

        /**
         * Checks to see if a server (using the specified configuration) is running.
         *
         * NOTE: This method should only be used to check if a server is running for a DIFFERENT configuration than the currently running server
         *
         * @return true if the media driver is active and running
         */
        fun isRunning(configuration: ServerConfiguration): Boolean = runBlocking {
            val logger = KotlinLogging.logger(Server::class.java.simpleName)
            AeronDriver.isRunning(configuration, logger)
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

    /**
     * The machine port that the server will listen for connections on
     */
    @Volatile
    var port: Int = 0
        private set

    final override fun newException(message: String, cause: Throwable?): Throwable {
        return ServerException(message, cause)
    }

    init {
        verifyState()
    }

    /**
     * Binds the server to AERON configuration
     *
     * @param port this is the network port which will be listening for incoming connections
     */
    @Suppress("DuplicatedCode")
    fun bind(port: Int = 0)  = runBlocking {
        // NOTE: it is critical to remember that Aeron DOES NOT like running from coroutines!

        require(port > 0 || config.enableIpc) { "port must be > 0" }
        require(port < 65535) { "port must be < 65535" }

        // the lifecycle of a server is the ENDPOINT (measured via the network event poller)
        if (endpointIsRunning.value) {
            listenerManager.notifyError(ServerException("Unable to start, the server is already running!"))
            return@runBlocking
        }

        if (!waitForClose()) {
            listenerManager.notifyError(ServerException("Unable to start the server!"))
            return@runBlocking
        }

        try {
            startDriver()
            verifyState()
            initializeState()
        } catch (e: Exception) {
            resetOnError()
            listenerManager.notifyError(ServerException("Unable to start the server!", e))
            return@runBlocking
        }

        this@Server.port = port

        config as ServerConfiguration

        // we are done with initial configuration, now initialize aeron and the general state of this endpoint

        val server = this@Server
        handshake = ServerHandshake(config, listenerManager, aeronDriver)

        val ipcPoller: AeronPoller = if (config.enableIpc) {
            ServerHandshakePollers.ipc(server, handshake)
        } else {
            ServerHandshakePollers.disabled("IPC Disabled")
        }

        val ipPoller = when (ipInfo.ipType) {
            // IPv6 will bind to IPv4 wildcard as well, so don't bind both!
            IpListenType.IPWildcard   -> ServerHandshakePollers.ip6Wildcard(server, handshake)
            IpListenType.IPv4Wildcard -> ServerHandshakePollers.ip4(server, handshake)
            IpListenType.IPv6Wildcard -> ServerHandshakePollers.ip6(server, handshake)
            IpListenType.IPv4 -> ServerHandshakePollers.ip4(server, handshake)
            IpListenType.IPv6 -> ServerHandshakePollers.ip6(server, handshake)
            IpListenType.IPC  -> ServerHandshakePollers.disabled("IPv4/6 Disabled")
        }

        logger.info { ipcPoller.info }
        logger.info { ipPoller.info }

        networkEventPoller.submit(
        action = {
            if (!shutdownEventPoller) {
                // NOTE: regarding fragment limit size. Repeated calls to '.poll' will reassemble a fragment.
                //   `.poll(handler, 4)` == `.poll(handler, 2)` + `.poll(handler, 2)`

                // this checks to see if there are NEW clients to handshake with
                var pollCount = ipcPoller.poll() + ipPoller.poll()

                // this manages existing clients (for cleanup + connection polling). This has a concurrent iterator,
                // so we can modify this as we go
                connections.forEach { connection ->
                    if (!connection.isClosedViaAeron()) {
                        // Otherwise, poll the connection for messages
                        pollCount += connection.poll()
                    } else {
                        // If the connection has either been closed, or has expired, it needs to be cleaned-up/deleted.
                        logger.debug { "[${connection}] connection expired (cleanup)" }

                        // the connection MUST be removed in the same thread that is processing events (it will be removed again in close, and that is expected)
                        removeConnection(connection)

                        // we already removed the connection, we can call it again without side affects
                        connection.close()
                    }
                }

                pollCount
            } else {
                // remove ourselves from processing
                EventPoller.REMOVE
            }
        },
        onShutdown = {
            logger.debug { "Server event dispatch closing..." }

            ipcPoller.close()
            ipPoller.close()

            // clear all the handshake info
            handshake.clear()

            // we can now call bind again
            endpointIsRunning.lazySet(false)
            pollerClosedLatch.countDown()

            logger.debug { "Closed the Network Event Poller..." }
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
        runBlocking {
            listenerManager.filter(ipFilterRule)
        }
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
        runBlocking {
            listenerManager.filter(function)
        }
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
        runBlocking {
            closeSuspending(closeEverything)
        }
    }

    override fun toString(): String {
        return "EndPoint [Server]"
    }

    /**
     * Enable
     */
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
