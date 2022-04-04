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

import dorkbox.netUtil.IP
import dorkbox.netUtil.IPv4
import dorkbox.netUtil.IPv6
import dorkbox.netUtil.Inet4
import dorkbox.netUtil.Inet6
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.AeronPoller
import dorkbox.network.aeron.IpcMediaDriverConnection
import dorkbox.network.aeron.UdpMediaDriverServerConnection
import dorkbox.network.connection.Connection
import dorkbox.network.connection.ConnectionParams
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.eventLoop
import dorkbox.network.connectionType.ConnectionRule
import dorkbox.network.coroutines.SuspendWaiter
import dorkbox.network.exceptions.ServerException
import dorkbox.network.handshake.HandshakeMessage
import dorkbox.network.handshake.ServerHandshake
import dorkbox.network.rmi.RmiSupportServer
import io.aeron.FragmentAssembler
import io.aeron.Image
import io.aeron.logbuffer.Header
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.agrona.DirectBuffer
import java.net.InetAddress
import java.util.concurrent.*

/**
 * The server can only be accessed in an ASYNC manner. This means that the server can only be used in RESPONSE to events. If you access the
 * server OUTSIDE of events, you will get inaccurate information from the server (such as getConnections())
 *
 * To put it bluntly, ONLY have the server do work inside of a listener!
 *
 * @param config these are the specific connection options
 * @param connectionFunc allows for custom connection implementations defined as a unit function
 */
open class Server<CONNECTION : Connection>(
    config: ServerConfiguration = ServerConfiguration(),
    connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION = {
        @Suppress("UNCHECKED_CAST")
        Connection(it) as CONNECTION
    })
    : EndPoint<CONNECTION>(config, connectionFunc) {

    companion object {
        /**
         * Gets the version number.
         */
        const val version = "5.10"

        /**
         * Checks to see if a server (using the specified configuration) is running.
         *
         * This method should only be used to check if a server is running for a DIFFERENT configuration than the currently running server
         */
        fun isRunning(configuration: ServerConfiguration): Boolean {
            return AeronDriver(configuration).isRunning()
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
    @Volatile
    private var bindAlreadyCalled = false

    /**
     * These are run in lock-step to shutdown/close the server. Afterwards, bind() can be called again
     */
    private val shutdownPollWaiter = SuspendWaiter()
    private val shutdownEventWaiter = SuspendWaiter()


    /**
     * Used for handshake connections
     */
    private val handshake = ServerHandshake(logger, config, listenerManager)

    /**
     * Maintains a thread-safe collection of rules used to define the connection type with this server.
     */
    private val connectionRules = CopyOnWriteArrayList<ConnectionRule>()

    /**
     * true if the following network stacks are available for use
     */
    private val canUseIPv4 = config.enableIPv4 && IPv4.isAvailable
    private val canUseIPv6 = config.enableIPv6 && IPv6.isAvailable


    // localhost/loopback IP might not always be 127.0.0.1 or ::1
    // We want to listen on BOTH IPv4 and IPv6 (config option lets us configure this)
    internal val listenIPv4Address: InetAddress? =
        if (canUseIPv4) {
            when (config.listenIpAddress) {
                "loopback", "localhost", "lo" -> IPv4.LOCALHOST
                "0", "::", "0.0.0.0", "*" -> {
                    // this is the "wildcard" address. Windows has problems with this.
                    IPv4.WILDCARD
                }
                else -> Inet4.toAddress(config.listenIpAddress) // Inet4Address.getAllByName(config.listenIpAddress)[0]
            }
        }
        else {
            null
        }


    internal val listenIPv6Address: InetAddress? =
        if (canUseIPv6) {
            when (config.listenIpAddress) {
                "loopback", "localhost", "lo" -> IPv6.LOCALHOST
                "0", "::", "0.0.0.0", "*" -> {
                    // this is the "wildcard" address. Windows has problems with this.
                    IPv6.WILDCARD
                }
                else -> Inet6.toAddress(config.listenIpAddress)
            }
        }
        else {
            null
        }

    init {
        // we are done with initial configuration, now finish serialization
        serialization.finishInit(type)
    }

    final override fun newException(message: String, cause: Throwable?): Throwable {
        return ServerException(message, cause)
    }

    private fun getIpcPoller(aeronDriver: AeronDriver, config: ServerConfiguration): AeronPoller {
        val poller = if (config.enableIpc) {
            val driver = IpcMediaDriverConnection(streamIdSubscription = config.ipcSubscriptionId,
                                                  streamId = config.ipcPublicationId,
                                                  sessionId = AeronDriver.RESERVED_SESSION_ID_INVALID)
            driver.buildServer(aeronDriver, logger)

            val publication = driver.publication
            val subscription = driver.subscription

            object : AeronPoller {
                val handler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
                    // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!

                    // The sessionId is unique within a Subscription and unique across all Publication's from a sourceIdentity.
                    // for the handshake, the sessionId IS NOT GLOBALLY UNIQUE
                    val sessionId = header.sessionId()

                    val message = readHandshakeMessage(buffer, offset, length, header)

                    // VALIDATE:: a Registration object is the only acceptable message during the connection phase
                    if (message !is HandshakeMessage) {
                        logger.error("[$sessionId] Connection from IPC not allowed! Invalid connection request")

                        try {
                            writeHandshakeMessage(publication, HandshakeMessage.error("Invalid connection request"))
                        } catch (e: Exception) {
                            logger.error("Handshake error!", e)
                        }
                        return@FragmentAssembler
                    }

                    handshake.processIpcHandshakeMessageServer(this@Server,
                                                               rmiConnectionSupport,
                                                               publication,
                                                               sessionId,
                                                               message,
                                                               aeronDriver,
                        connectionFunc,
                                                               logger)
                }

                override fun poll(): Int { return subscription.poll(handler, 1) }
                override fun close() { driver.close() }
                override fun serverInfo(): String { return driver.serverInfo() }
            }
        } else {
            object : AeronPoller {
                override fun poll(): Int { return 0 }
                override fun close() {}
                override fun serverInfo(): String { return "IPC Disabled" }
            }
        }

        logger.info(poller.serverInfo())
        return poller
    }

    @Suppress("DuplicatedCode")
    private fun getIpv4Poller(aeronDriver: AeronDriver, config: ServerConfiguration): AeronPoller {
        val poller = if (canUseIPv4) {
            val driver = UdpMediaDriverServerConnection(
                    listenAddress = listenIPv4Address!!,
                    publicationPort = config.publicationPort,
                    subscriptionPort = config.subscriptionPort,
                    streamId = AeronDriver.UDP_HANDSHAKE_STREAM_ID,
                    sessionId = AeronDriver.RESERVED_SESSION_ID_INVALID,
                    connectionTimeoutSec = config.connectionCloseTimeoutInSeconds)

            driver.buildServer(aeronDriver, logger)

            val publication = driver.publication
            val subscription = driver.subscription

            object : AeronPoller {
                /**
                 * Note:
                 * Reassembly has been shown to be minimal impact to latency. But not totally negligible. If the lowest latency is
                 * desired, then limiting message sizes to MTU size is a good practice.
                 *
                 * There is a maximum length allowed for messages which is the min of 1/8th a term length or 16MB.
                 * Messages larger than this should chunked using an application level chunking protocol. Chunking has better recovery
                 * properties from failure and streams with mechanical sympathy.
                 */
                val handler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
                    // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!

                    // The sessionId is unique within a Subscription and unique across all Publication's from a sourceIdentity.
                    // for the handshake, the sessionId IS NOT GLOBALLY UNIQUE
                    val sessionId = header.sessionId()

                    // note: this address will ALWAYS be an IP:PORT combo  OR  it will be aeron:ipc  (if IPC, it will be a different handler!)
                    val remoteIpAndPort = (header.context() as Image).sourceIdentity()

                    // split
                    val splitPoint = remoteIpAndPort.lastIndexOf(':')
                    val clientAddressString = remoteIpAndPort.substring(0, splitPoint)
                    // val port = remoteIpAndPort.substring(splitPoint+1)

                    // this should never be null, because we are feeding it a valid IP address from aeron
                    val clientAddress = IPv4.toAddressUnsafe(clientAddressString)


                    val message = readHandshakeMessage(buffer, offset, length, header)

                    // VALIDATE:: a Registration object is the only acceptable message during the connection phase
                    if (message !is HandshakeMessage) {
                        logger.error("[$sessionId] Connection from $clientAddressString not allowed! Invalid connection request")

                        try {
                            writeHandshakeMessage(publication, HandshakeMessage.error("Invalid connection request"))
                        } catch (e: Exception) {
                            logger.error("Handshake error!", e)
                        }
                        return@FragmentAssembler
                    }

                    handshake.processUdpHandshakeMessageServer(this@Server,
                                                               rmiConnectionSupport,
                                                               publication,
                                                               sessionId,
                                                               clientAddressString,
                                                               clientAddress,
                                                               message,
                                                               aeronDriver,
                                                               false,
                                                               connectionFunc,
                                                               logger)
                }

                override fun poll(): Int { return subscription.poll(handler, 1) }
                override fun close() { driver.close() }
                override fun serverInfo(): String { return driver.serverInfo() }
            }
        } else {
            object : AeronPoller {
                override fun poll(): Int { return 0 }
                override fun close() {}
                override fun serverInfo(): String { return "IPv4 Disabled" }
            }
        }

        logger.info(poller.serverInfo())
        return poller
    }

    @Suppress("DuplicatedCode")
    private fun getIpv6Poller(aeronDriver: AeronDriver, config: ServerConfiguration): AeronPoller {
        val poller = if (canUseIPv6) {
            val driver = UdpMediaDriverServerConnection(
                    listenAddress = listenIPv6Address!!,
                    publicationPort = config.publicationPort,
                    subscriptionPort = config.subscriptionPort,
                    streamId = AeronDriver.UDP_HANDSHAKE_STREAM_ID,
                    sessionId = AeronDriver.RESERVED_SESSION_ID_INVALID,
                    connectionTimeoutSec = config.connectionCloseTimeoutInSeconds)

            driver.buildServer(aeronDriver, logger)

            val publication = driver.publication
            val subscription = driver.subscription

            object : AeronPoller {
                /**
                 * Note:
                 * Reassembly has been shown to be minimal impact to latency. But not totally negligible. If the lowest latency is
                 * desired, then limiting message sizes to MTU size is a good practice.
                 *
                 * There is a maximum length allowed for messages which is the min of 1/8th a term length or 16MB.
                 * Messages larger than this should chunked using an application level chunking protocol. Chunking has better recovery
                 * properties from failure and streams with mechanical sympathy.
                 */
                val handler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
                    // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!

                    // The sessionId is unique within a Subscription and unique across all Publication's from a sourceIdentity.
                    // for the handshake, the sessionId IS NOT GLOBALLY UNIQUE
                    val sessionId = header.sessionId()

                    // note: this address will ALWAYS be an IP:PORT combo  OR  it will be aeron:ipc  (if IPC, it will be a different handler!)
                    val remoteIpAndPort = (header.context() as Image).sourceIdentity()

                    // split
                    val splitPoint = remoteIpAndPort.lastIndexOf(':')
                    val clientAddressString = remoteIpAndPort.substring(0, splitPoint)
                    // val port = remoteIpAndPort.substring(splitPoint+1)

                    // this should never be null, because we are feeding it a valid IP address from aeron
                    val clientAddress = IPv6.toAddress(clientAddressString)!!


                    val message = readHandshakeMessage(buffer, offset, length, header)

                    // VALIDATE:: a Registration object is the only acceptable message during the connection phase
                    if (message !is HandshakeMessage) {
                        logger.error("[$sessionId] Connection from $clientAddressString not allowed! Invalid connection request")

                        try {
                            writeHandshakeMessage(publication, HandshakeMessage.error("Invalid connection request"))
                        } catch (e: Exception) {
                            logger.error("Handshake error!", e)
                        }
                        return@FragmentAssembler
                    }

                    handshake.processUdpHandshakeMessageServer(this@Server,
                                                               rmiConnectionSupport,
                                                               publication,
                                                               sessionId,
                                                               clientAddressString,
                                                               clientAddress,
                                                               message,
                                                               aeronDriver,
                                                               false,
                                                               connectionFunc,
                                                               logger)
                }

                override fun poll(): Int { return subscription.poll(handler, 1) }
                override fun close() { driver.close() }
                override fun serverInfo(): String { return driver.serverInfo() }
            }
        } else {
            object : AeronPoller {
                override fun poll(): Int { return 0 }
                override fun close() {}
                override fun serverInfo(): String { return "IPv6 Disabled" }
            }
        }

        logger.info(poller.serverInfo())
        return poller
    }

    @Suppress("DuplicatedCode")
    private fun getIpv6WildcardPoller(aeronDriver: AeronDriver, config: ServerConfiguration): AeronPoller {
        val driver = UdpMediaDriverServerConnection(
                listenAddress = listenIPv6Address!!,
                publicationPort = config.publicationPort,
                subscriptionPort = config.subscriptionPort,
                streamId = AeronDriver.UDP_HANDSHAKE_STREAM_ID,
                sessionId = AeronDriver.RESERVED_SESSION_ID_INVALID,
                connectionTimeoutSec = config.connectionCloseTimeoutInSeconds)

        driver.buildServer(aeronDriver, logger)

        val publication = driver.publication
        val subscription = driver.subscription

        val poller = object : AeronPoller {
            /**
             * Note:
             * Reassembly has been shown to be minimal impact to latency. But not totally negligible. If the lowest latency is
             * desired, then limiting message sizes to MTU size is a good practice.
             *
             * There is a maximum length allowed for messages which is the min of 1/8th a term length or 16MB.
             * Messages larger than this should chunked using an application level chunking protocol. Chunking has better recovery
             * properties from failure and streams with mechanical sympathy.
             */
            val handler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
                // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!

                // The sessionId is unique within a Subscription and unique across all Publication's from a sourceIdentity.
                // for the handshake, the sessionId IS NOT GLOBALLY UNIQUE
                val sessionId = header.sessionId()

                // note: this address will ALWAYS be an IP:PORT combo  OR  it will be aeron:ipc  (if IPC, it will be a different handler!)
                val remoteIpAndPort = (header.context() as Image).sourceIdentity()

                // split
                val splitPoint = remoteIpAndPort.lastIndexOf(':')
                val clientAddressString = remoteIpAndPort.substring(0, splitPoint)
                // val port = remoteIpAndPort.substring(splitPoint+1)

                // this should never be null, because we are feeding it a valid IP address from aeron
                // maybe IPv4, maybe IPv6! This is slower than if we ALREADY know what it is.
                val clientAddress = IP.toAddress(clientAddressString)!!


                val message = readHandshakeMessage(buffer, offset, length, header)

                // VALIDATE:: a Registration object is the only acceptable message during the connection phase
                if (message !is HandshakeMessage) {
                    logger.error("[$sessionId] Connection from $clientAddressString not allowed! Invalid connection request")

                    try {
                        writeHandshakeMessage(publication, HandshakeMessage.error("Invalid connection request"))
                    } catch (e: Exception) {
                        logger.error("Handshake error!", e)
                    }
                    return@FragmentAssembler
                }

                handshake.processUdpHandshakeMessageServer(this@Server,
                                                           rmiConnectionSupport,
                                                           publication,
                                                           sessionId,
                                                           clientAddressString,
                                                           clientAddress,
                                                           message,
                                                           aeronDriver,
                                                           true,
                                                           connectionFunc,
                                                           logger)
           }

           override fun poll(): Int { return subscription.poll(handler, 1) }
           override fun close() { driver.close() }
           override fun serverInfo(): String { return driver.serverInfo() }
        }

        logger.info(poller.serverInfo())
        return poller
    }

    /**
     * Binds the server to AERON configuration
     */
    @Suppress("DuplicatedCode")
    fun bind() {
        if (bindAlreadyCalled) {
            logger.error("Unable to bind when the server is already running!")
            return
        }

        try {
            runBlocking {
                initEndpointState()
            }
        } catch (e: Exception) {
            logger.error("Unable to initialize the endpoint state", e)
            return
        }

        config as ServerConfiguration

        // we are done with initial configuration, now initialize aeron and the general state of this endpoint
        bindAlreadyCalled = true

        // this forces the current thread to WAIT until poll system has started
        val waiter = SuspendWaiter()

        val ipcPoller: AeronPoller = getIpcPoller(aeronDriver, config)

        // if we are binding to WILDCARD, then we have to do something special if BOTH IPv4 and IPv6 are enabled!
        val isWildcard = listenIPv4Address == IPv4.WILDCARD || listenIPv6Address != IPv6.WILDCARD
        val ipv4Poller: AeronPoller
        val ipv6Poller: AeronPoller

        if (isWildcard) {
            // IPv6 will bind to IPv4 wildcard as well!!
            if (canUseIPv4 && canUseIPv6) {
                ipv4Poller = object : AeronPoller {
                    override fun poll(): Int { return 0 }
                    override fun close() {}
                    override fun serverInfo(): String { return "IPv4 Disabled" }
                }
                ipv6Poller = getIpv6WildcardPoller(aeronDriver, config)
            } else {
                // only 1 will be a real poller
                ipv4Poller = getIpv4Poller(aeronDriver, config)
                ipv6Poller = getIpv6Poller(aeronDriver, config)
            }
        } else {
            ipv4Poller = getIpv4Poller(aeronDriver, config)
            ipv6Poller = getIpv6Poller(aeronDriver, config)
        }

        actionDispatch.launch {
            waiter.doNotify()

            val pollIdleStrategy = config.pollIdleStrategy
            try {
                var pollCount: Int

                while (!isShutdown()) {
                    pollCount = 0

                    // NOTE: regarding fragment limit size. Repeated calls to '.poll' will reassemble a fragment.
                    //   `.poll(handler, 4)` == `.poll(handler, 2)` + `.poll(handler, 2)`

                    // this checks to see if there are NEW clients on the handshake ports
                    pollCount += ipv4Poller.poll()
                    pollCount += ipv6Poller.poll()

                    // this checks to see if there are NEW clients via IPC
                    pollCount += ipcPoller.poll()


                    // this manages existing clients (for cleanup + connection polling). This has a concurrent iterator,
                    // so we can modify this as we go
                    connections.forEach { connection ->
                        if (connection.isClosedViaAeron()) {
                            // If the connection has either been closed, or has expired, it needs to be cleaned-up/deleted.
                            logger.debug { "[${connection.id}] connection expired" }

                            // have to free up resources!
                            handshake.cleanup(connection)

                            removeConnection(connection)

                            // this will call removeConnection again, but that is ok
                            connection.close()

                            // have to manually notify the server-listenerManager that this connection was closed
                            // if the connection was MANUALLY closed (via calling connection.close()), then the connection-listenermanager is
                            // instantly notified and on cleanup, the server-listenermanager is called

                            // this always has to be on event dispatch, otherwise we can have weird logic loops if we reconnect within a disconnect callback
                            actionDispatch.eventLoop {
                                listenerManager.notifyDisconnect(connection)
                            }
                        }
                        else {
                            // Otherwise, poll the connection for messages
                            pollCount += connection.pollSubscriptions()
                        }
                    }

                    // 0 means we idle. >0 means reset and don't idle (because there are likely more poll events)
                    pollIdleStrategy.idle(pollCount)
                }

                // we want to process **actual** close cleanup events on this thread as well, otherwise we will have threading problems
                shutdownPollWaiter.doWait()

                // we have to manually cleanup the connections and call server-notifyDisconnect because otherwise this will never get called
                val jobs = mutableListOf<Job>()

                // we want to clear all the connections FIRST (since we are shutting down)
                val cons = mutableListOf<CONNECTION>()
                connections.forEach { cons.add(it) }
                connections.clear()

                cons.forEach { connection ->
                    logger.error("${connection.id} cleanup")
                    // have to free up resources!
                    // NOTE: This can only occur on the polling dispatch thread!!
                    handshake.cleanup(connection)

                    // make sure the connection is closed (close can only happen once, so a duplicate call does nothing!)
                    connection.close()

                    // have to manually notify the server-listenerManager that this connection was closed
                    // if the connection was MANUALLY closed (via calling connection.close()), then the connection-listenermanager is
                    // instantly notified and on cleanup, the server-listenermanager is called
                    // NOTE: this must be the LAST thing happening!

                    // this always has to be on event dispatch, otherwise we can have weird logic loops if we reconnect within a disconnect callback
                    val job = actionDispatch.eventLoop {
                        listenerManager.notifyDisconnect(connection)
                    }
                    jobs.add(job)
                }

                // when we close a client or a server, we want to make sure that ALL notifications are finished.
                // when it's just a connection getting closed, we don't care about this. We only care when it's "global" shutdown
                jobs.forEach { it.join() }
            } catch (e: Exception) {
                logger.error("Unexpected error during server message polling!", e)
            } finally {
                ipv4Poller.close()
                ipv6Poller.close()
                ipcPoller.close()

                // clear all the handshake info
                handshake.clear()

                // finish closing -- this lets us make sure that we don't run into race conditions on the thread that calls close()
                shutdownEventWaiter.doNotify()
            }
        }

        runBlocking {
            waiter.doWait()
        }
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
     * Runs an action for each connection
     */
    fun forEachConnection(function: (connection: CONNECTION) -> Unit) {
        connections.forEach {
            function(it)
        }
    }

    /**
     * Closes the server and all it's connections. After a close, you may call 'bind' again.
     */
    final override fun close0() {
        // when we call close, it will shutdown the polling mechanism then wait for us to tell it to cleanup connections.
        //
        // Aeron + the Media Driver will have already been shutdown at this point.
        if (bindAlreadyCalled) {
            bindAlreadyCalled = false

            runBlocking {
                // These are run in lock-step
                shutdownPollWaiter.doNotify()
                shutdownEventWaiter.doWait()
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
