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
import dorkbox.network.connection.EventDispatcher
import dorkbox.network.connection.EventDispatcher.Companion.EVENT
import dorkbox.network.connection.IpInfo
import dorkbox.network.connection.IpInfo.Companion.IpListenType
import dorkbox.network.connectionType.ConnectionRule
import dorkbox.network.exceptions.AllocationException
import dorkbox.network.exceptions.ServerException
import dorkbox.network.handshake.ServerHandshake
import dorkbox.network.handshake.ServerHandshakePollers
import dorkbox.network.ipFilter.IpFilterRule
import dorkbox.network.rmi.RmiSupportServer
import kotlinx.atomicfu.atomic
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
            AeronDriver(configuration, logger).use {
                it.ensureStopped(timeout, 500)
            }
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
            AeronDriver(configuration, logger).use {
                it.isRunning()
            }
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
     * @return true if this server has successfully bound to an IP address and is running
     */
    private var bindAlreadyCalled = atomic(false)

    /**
     * These are run in lock-step to shutdown/close the server. Afterwards, bind() can be called again
     */
    @Volatile
    private var shutdownPollLatch = dorkbox.util.sync.CountDownLatch(0 )

    @Volatile
    private var shutdownEventLatch = dorkbox.util.sync.CountDownLatch(0)

    /**
     * Maintains a thread-safe collection of rules used to define the connection type with this server.
     */
    private val connectionRules = CopyOnWriteArrayList<ConnectionRule>()

    /**
     * the IP address information, if available.
     */
    internal val ipInfo = IpInfo(config)

    final override fun newException(message: String, cause: Throwable?): Throwable {
        return ServerException(message, cause)
    }

    init {
        verifyState()
    }

    /**
     * Binds the server to AERON configuration
     */
    @Suppress("DuplicatedCode")
    fun bind()  = runBlocking {
        // NOTE: it is critical to remember that Aeron DOES NOT like running from coroutines!

        if (bindAlreadyCalled.getAndSet(true)) {
            logger.error { "Unable to bind when the server is already running!" }
            return@runBlocking
        }

        try {
            startDriver()
            verifyState()
            initializeLatch()
        } catch (e: Exception) {
            logger.error(e) { "Unable to start the network driver" }
            return@runBlocking
        }

        shutdownPollLatch = dorkbox.util.sync.CountDownLatch(1)
        shutdownEventLatch = dorkbox.util.sync.CountDownLatch(1)

        config as ServerConfiguration


        // we are done with initial configuration, now initialize aeron and the general state of this endpoint

        val server = this@Server
        val handshake = ServerHandshake(logger, config, listenerManager, aeronDriver)

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
            IpListenType.IPC  -> ServerHandshakePollers.disabled("IP Disabled")
        }

        logger.info { ipcPoller.info }
        logger.info { ipPoller.info }

        // additionally, if we have MULTIPLE clients on the same machine, we are limited by the CPU core count. Ideally we want to share this among ALL clients within the same JVM so that we can support multiple clients/servers
        networkEventPoller.submit(
            action = {
            if (!isShutdown()) {
                var pollCount = 0

                // NOTE: regarding fragment limit size. Repeated calls to '.poll' will reassemble a fragment.
                //   `.poll(handler, 4)` == `.poll(handler, 2)` + `.poll(handler, 2)`

                // this checks to see if there are NEW clients on the handshake ports
                pollCount += ipPoller.poll()

                // this checks to see if there are NEW clients via IPC
                pollCount += ipcPoller.poll()

                // this manages existing clients (for cleanup + connection polling). This has a concurrent iterator,
                // so we can modify this as we go
                connections.forEach { connection ->
                    if (!connection.isClosedViaAeron()) {
                        // Otherwise, poll the connection for messages
                        pollCount += connection.poll()
                    } else {
                        // If the connection has either been closed, or has expired, it needs to be cleaned-up/deleted.
                        logger.debug { "[${connection.details}] connection expired (cleanup)" }

                        // the connection MUST be removed in the same thread that is processing events
                        removeConnection(connection)

                        // this will call removeConnection again, but that is ok
                        EventDispatcher.launch(EVENT.CLOSE) {
                            // we already removed the connection
                            connection.close(enableRemove = false)

                            // have to manually notify the server-listenerManager that this connection was closed
                            // if the connection was MANUALLY closed (via calling connection.close()), then the connection-listener-manager is
                            // instantly notified and on cleanup, the server-listener-manager is called

                            // this always has to be on event dispatch, otherwise we can have weird logic loops if we reconnect within a disconnect callback
                            EventDispatcher.launch(EVENT.DISCONNECT) {
                                listenerManager.notifyDisconnect(connection)
                            }
                        }
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

            // we want to process **actual** close cleanup events on this thread as well, otherwise we will have threading problems
            shutdownPollLatch.await()

            // we want to clear all the connections FIRST (since we are shutting down)
            val cons = mutableListOf<CONNECTION>()
            connections.forEach { cons.add(it) }
            connections.clear()


            // when we close a client or a server, we want to make sure that ALL notifications are finished.
            // when it's just a connection getting closed, we don't care about this. We only care when it's "global" shutdown

            // we have to manually clean-up the connections and call server-notifyDisconnect because otherwise this will never get called
            try {
                cons.forEach { connection ->
                    logger.info { "[${connection.details}] Connection cleanup and close" }
                    // make sure the connection is closed (close can only happen once, so a duplicate call does nothing!)

                    EventDispatcher.launch(EVENT.CLOSE) {
                        connection.close(enableRemove = true)

                        // have to manually notify the server-listenerManager that this connection was closed
                        // if the connection was MANUALLY closed (via calling connection.close()), then the connection-listenermanager is
                        // instantly notified and on cleanup, the server-listenermanager is called
                        // NOTE: this must be the LAST thing happening!

                        // the SERVER cannot re-connect to clients, only clients can call 'connect'.
                        EventDispatcher.launch(EVENT.DISCONNECT) {
                            listenerManager.notifyDisconnect(connection)
                        }
                    }
                }
            } finally {
                ipcPoller.close()
                ipPoller.close()

                // clear all the handshake info
                handshake.clear()

                try {
                    AeronDriver.checkForMemoryLeaks()

                    // make sure that we have de-allocated all connection data
                    handshake.checkForMemoryLeaks()
                } catch (e: AllocationException) {
                    logger.error(e) { "Error during server cleanup" }
                }

                // finish closing -- this lets us make sure that we don't run into race conditions on the thread that calls close()
                try {
                    shutdownEventLatch.countDown()
                } catch (ignored: Exception) {
                }
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

    fun close() {
        close(false) {}
    }

    fun close(onCloseFunction: () -> Unit = {}) {
        close(false, onCloseFunction)
    }

    final override fun close(shutdownEndpoint: Boolean, onCloseFunction: () -> Unit) {
        super.close(shutdownEndpoint, onCloseFunction)
    }

    /**
     * Closes the server and all it's connections. After a close, you may call 'bind' again.
     */
    final override suspend fun close0() {
        // when we call close, it will shutdown the polling mechanism, then wait for us to tell it to clean-up connections.
        //
        // Aeron + the Media Driver will have already been shutdown at this point.
        if (bindAlreadyCalled.getAndSet(false)) {
            // These are run in lock-step
            shutdownPollLatch.countDown()
            shutdownEventLatch.await()
        }
    }

    /**
     * Enable
     */
    fun <R> use(block: (Server<CONNECTION>) -> R): R {
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
