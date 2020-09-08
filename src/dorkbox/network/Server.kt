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
import dorkbox.network.aeron.AeronPoller
import dorkbox.network.aeron.IpcMediaDriverConnection
import dorkbox.network.aeron.UdpMediaDriverConnection
import dorkbox.network.connection.Connection
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.ListenerManager
import dorkbox.network.connectionType.ConnectionRule
import dorkbox.network.exceptions.ServerException
import dorkbox.network.handshake.ServerHandshake
import dorkbox.network.other.coroutines.SuspendWaiter
import dorkbox.network.rmi.RemoteObject
import dorkbox.network.rmi.RemoteObjectStorage
import dorkbox.network.rmi.TimeoutException
import io.aeron.Aeron
import io.aeron.FragmentAssembler
import io.aeron.Image
import io.aeron.logbuffer.Header
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.agrona.DirectBuffer
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The server can only be accessed in an ASYNC manner. This means that the server can only be used in RESPONSE to events. If you access the
 * server OUTSIDE of events, you will get inaccurate information from the server (such as getConnections())
 *
 *
 * To put it bluntly, ONLY have the server do work inside of a listener!
 */
open class Server<CONNECTION : Connection>(config: ServerConfiguration = ServerConfiguration()) : EndPoint<CONNECTION>(config) {
    companion object {
        /**
         * Gets the version number.
         */
        const val version = "5.0"

        /**
         * Checks to see if a server (using the specified configuration) is running.
         *
         * @return true if the configuration matches and can connect (but not verify) to the TCP control socket.
         */
        fun isRunning(configuration: ServerConfiguration): Boolean {
            val server = Server<Connection>(configuration)

            val running = server.isRunning()
            server.close()

            return running
        }
    }

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

    internal val listenIPv4Address: InetAddress?
    internal val listenIPv6Address: InetAddress?

    init {
        // have to do some basic validation of our configuration
        config.listenIpAddress = config.listenIpAddress.toLowerCase()

        require(config.listenIpAddress.isNotBlank()) { "Blank listen IP address, cannot continue"}

        // localhost/loopback IP might not always be 127.0.0.1 or ::1
        // We want to listen on BOTH IPv4 and IPv6 (config option lets us configure this)
        listenIPv4Address = if (!config.enableIPv4) {
            null
        } else {
            when (config.listenIpAddress) {
                "loopback", "localhost", "lo" -> IPv4.LOCALHOST
                "0", "::", "0.0.0.0", "*" -> {
                    // this is the "wildcard" address. Windows has problems with this.
                    InetAddress.getByAddress("", byteArrayOf(0, 0, 0, 0))
                }
                else -> Inet4Address.getAllByName(config.listenIpAddress)[0]
            }
        }

        listenIPv6Address = if (!config.enableIPv6) {
            null
        } else {
            when (config.listenIpAddress) {
                "loopback", "localhost", "lo" -> IPv6.LOCALHOST
                "0", "::", "0.0.0.0", "*" -> {
                    // this is the "wildcard" address. Windows has problems with this.
                    InetAddress.getByAddress("", byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
                }
                else -> Inet6Address.getAllByName(config.listenIpAddress)[0]
            }
        }

        if (config.publicationPort <= 0) { throw ServerException("configuration port must be > 0") }
        if (config.publicationPort >= 65535) { throw ServerException("configuration port must be < 65535") }

        if (config.subscriptionPort <= 0) { throw ServerException("configuration controlPort must be > 0") }
        if (config.subscriptionPort >= 65535) { throw ServerException("configuration controlPort must be < 65535") }

        if (config.networkMtuSize <= 0) { throw ServerException("configuration networkMtuSize must be > 0") }
        if (config.networkMtuSize >= 9 * 1024) { throw ServerException("configuration networkMtuSize must be < ${9 * 1024}") }

        if (config.maxConnectionsPerIpAddress == 0) { config.maxConnectionsPerIpAddress = config.maxClientCount}

        // we are done with initial configuration, now finish serialization
        serialization.finishInit(type, settingsStore)
    }

    override fun newException(message: String, cause: Throwable?): Throwable {
        return ServerException(message, cause)
    }


    private fun getIpcPoller(aeron: Aeron, config: ServerConfiguration): AeronPoller {
        val poller = if (config.enableIPC) {
            val driver = IpcMediaDriverConnection(streamIdSubscription = config.ipcSubscriptionId,
                                                  streamId = config.ipcPublicationId,
                                                  sessionId = RESERVED_SESSION_ID_INVALID)
            driver.buildServer(aeron, logger)
            val publication = driver.publication
            val subscription = driver.subscription

            object : AeronPoller {
                val handler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
                    // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!

                    // The sessionId is unique within a Subscription and unique across all Publication's from a sourceIdentity.
                    // for the handshake, the sessionId IS NOT GLOBALLY UNIQUE
                    val sessionId = header.sessionId()

                    val message = readHandshakeMessage(buffer, offset, length, header)
                    handshake.processIpcHandshakeMessageServer(this@Server,
                                                               publication,
                                                               sessionId,
                                                               message,
                                                               aeron)
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

    private fun getIpv4Poller(aeron: Aeron, config: ServerConfiguration): AeronPoller {
        val poller = if (config.enableIPv4) {
            val driver = UdpMediaDriverConnection(address = listenIPv4Address!!,
                                                  publicationPort = config.publicationPort,
                                                  subscriptionPort = config.subscriptionPort,
                                                  streamId = UDP_HANDSHAKE_STREAM_ID,
                                                  sessionId = RESERVED_SESSION_ID_INVALID)

            driver.buildServer(aeron, logger)
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
                    val clientAddress = IPv4.getByNameUnsafe(clientAddressString)


                    val message = readHandshakeMessage(buffer, offset, length, header)
                    handshake.processUdpHandshakeMessageServer(this@Server,
                                                               publication,
                                                               sessionId,
                                                               clientAddressString,
                                                               clientAddress,
                                                               message,
                                                               aeron,
                                                               false)
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

    private fun getIpv6Poller(aeron: Aeron, config: ServerConfiguration): AeronPoller {
        val poller = if (config.enableIPv6) {
            val driver = UdpMediaDriverConnection(address = listenIPv6Address!!,
                                                  publicationPort = config.publicationPort,
                                                  subscriptionPort = config.subscriptionPort,
                                                  streamId = UDP_HANDSHAKE_STREAM_ID,
                                                  sessionId = RESERVED_SESSION_ID_INVALID)

            driver.buildServer(aeron, logger)
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
                    val clientAddress = IPv6.getByName(clientAddressString)!!


                    val message = readHandshakeMessage(buffer, offset, length, header)
                    handshake.processUdpHandshakeMessageServer(this@Server,
                                                               publication,
                                                               sessionId,
                                                               clientAddressString,
                                                               clientAddress,
                                                               message,
                                                               aeron,
                                                               false)
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

    private fun getIpv6WildcardPoller(aeron: Aeron, config: ServerConfiguration): AeronPoller {
        val poller = if (config.enableIPv6) {
            val driver = UdpMediaDriverConnection(address = listenIPv6Address!!,
                                                  publicationPort = config.publicationPort,
                                                  subscriptionPort = config.subscriptionPort,
                                                  streamId = UDP_HANDSHAKE_STREAM_ID,
                                                  sessionId = RESERVED_SESSION_ID_INVALID)

            driver.buildServer(aeron, logger)
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
                    // maybe IPv4, maybe IPv6!!!
                    val clientAddress = IP.getByName(clientAddressString)!!


                    val message = readHandshakeMessage(buffer, offset, length, header)
                    handshake.processUdpHandshakeMessageServer(this@Server,
                                                               publication,
                                                               sessionId,
                                                               clientAddressString,
                                                               clientAddress,
                                                               message,
                                                               aeron,
                                                               true)
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

    /**
     * Binds the server to AERON configuration
     */
    @Suppress("DuplicatedCode")
    fun bind() {
        if (bindAlreadyCalled) {
            logger.error("Unable to bind when the server is already running!")
            return
        }

        // we are done with initial configuration, now initialize aeron and the general state of this endpoint
        val aeron = initEndpointState()
        bindAlreadyCalled = true

        config as ServerConfiguration


        val ipcPoller: AeronPoller = getIpcPoller(aeron, config)

        // if we are binding to WILDCARD, then we have to do something special if BOTH IPv4 and IPv6 are enabled!
        val isWildcard = listenIPv4Address == IPv4.WILDCARD || listenIPv6Address != IPv6.WILDCARD
        val ipv4Poller: AeronPoller
        val ipv6Poller: AeronPoller

        if (isWildcard) {
            // IPv6 will bind to IPv4 wildcard as well!!
            if (config.enableIPv4 && config.enableIPv6) {
                ipv4Poller = object : AeronPoller {
                    override fun poll(): Int { return 0 }
                    override fun close() {}
                    override fun serverInfo(): String { return "IPv4 Disabled" }
                }
                ipv6Poller = getIpv6WildcardPoller(aeron, config)
            } else {
                // only 1 will be a real poller
                ipv4Poller = getIpv4Poller(aeron, config)
                ipv6Poller = getIpv6Poller(aeron, config)
            }
        } else {
            ipv4Poller = getIpv4Poller(aeron, config)
            ipv6Poller = getIpv6Poller(aeron, config)
        }


        actionDispatch.launch {
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


                    // this manages existing clients (for cleanup + connection polling)
                    connections.forEachWithCleanup({ connection ->
                        // If the connection has either been closed, or has expired, it needs to be cleaned-up/deleted.
                        var shouldCleanupConnection = false

                        if (connection.isExpired()) {
                            logger.trace {"[${connection.id}] connection expired"}
                            shouldCleanupConnection = true
                        }

                        else if (connection.isClosed()) {
                            logger.trace {"[${connection.id}] connection closed"}
                            shouldCleanupConnection = true
                        }


                        if (shouldCleanupConnection) {
                            // remove this connection so there won't be an attempt to poll it again
                            true
                        }
                        else {
                            // Otherwise, poll the connection for messages
                            pollCount += connection.pollSubscriptions()
                            false
                        }
                    }, { connectionToClean ->
                        logger.info {"[${connectionToClean.id}] cleaned-up connection"}

                        // have to free up resources!
                        handshake.cleanup(connectionToClean)

                        // there are 2 ways to call close.
                        //   MANUALLY
                        //   when a connection is disconnected via a timeout/expire.
                        // the compareAndSet is used to make sure that if we call close() MANUALLY, when the auto-cleanup/disconnect is called -- it doesn't
                        // try to do it again.
                        connectionToClean.close()

                        // have to manually notify the server-listenerManager that this connection was closed
                        // if the connection was MANUALLY closed (via calling connection.close()), then the connection-listenermanager is
                        // instantly notified and on cleanup, the server-listenermanager is called
                        listenerManager.notifyDisconnect(connectionToClean)
                    })


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

                    // this always has to be on a new dispatch, otherwise we can have weird logic loops if we reconnect within a disconnect callback
                    val job = actionDispatch.launch {
                        listenerManager.notifyDisconnect(connection)
                    }
                    jobs.add(job)
                }

                // reset all of the handshake info
                handshake.clear()

                // when we close a client or a server, we want to make sure that ALL notifications are finished.
                // when it's just a connection getting closed, we don't care about this. We only care when it's "global" shutdown
                jobs.forEach { it.join() }

            } finally {
                ipv4Poller.close()
                ipv6Poller.close()
                ipcPoller.close()

                // finish closing -- this lets us make sure that we don't run into race conditions on the thread that calls close()
                shutdownEventWaiter.doNotify()
            }
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
     * Safely sends objects to a destination.
     */
    suspend fun send(message: Any) {
        connections.forEach {
            it.send(message)
        }
    }

    /**
     * TODO: when adding a "custom" connection, it's super important to not have to worry about the sessionID (which is what we key off of)
     * Adds a custom connection to the server.
     *
     * This should only be used in situations where there can be DIFFERENT types of connections (such as a 'web-based' connection) and
     * you want *this* server instance to manage listeners + message dispatch
     *
     * @param connection the connection to add
     */
    fun addConnection(connection: CONNECTION) {
        connections.add(connection)
    }

    /**
     * TODO: when adding a "custom" connection, it's super important to not have to worry about the sessionID (which is what we key off of)
     * Removes a custom connection to the server.
     *
     *
     * This should only be used in situations where there can be DIFFERENT types of connections (such as a 'web-based' connection) and
     * you want *this* server instance to manage listeners + message dispatch
     *
     * @param connection the connection to remove
     */
    fun removeConnection(connection: CONNECTION) {
        connections.remove(connection)
    }


    /**
     * Closes the server and all it's connections. After a close, you may call 'bind' again.
     */
    override fun close0() {
        bindAlreadyCalled = false

        // when we call close, it will shutdown the polling mechanism then wait for us to tell it to cleanup connections.
        //
        // Aeron + the Media Driver will have already been shutdown at this point.
        runBlocking {
            // These are run in lock-step
            shutdownPollWaiter.doNotify()
            shutdownEventWaiter.doWait()
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


    // RMI notes (in multiple places, copypasta, because this is confusing if not written down)
    //
    // only server can create a global object (in itself, via save)
    // server
    //  -> saveGlobal (global)
    //
    // client
    //  -> save (connection)
    //  -> get (connection)
    //  -> create (connection)
    //  -> saveGlobal (global)
    //  -> getGlobal (global)
    //
    // connection
    //  -> save (connection)
    //  -> get (connection)
    //  -> getGlobal (global)
    //  -> create (connection)


    //
    //
    // RMI
    //
    //



    /**
     * Tells us to save an an already created object, GLOBALLY, so a remote connection can get it via [Connection.getObject]
     *
     * FOR REMOTE CONNECTIONS:
     * Methods that return a value will throw [TimeoutException] if the response is not received with the
     * response timeout [RemoteObject.responseTimeout].
     *
     * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side
     * will have the proxy object replaced with the registered (non-proxy) object.
     *
     * If one wishes to change the default behavior, cast the object to access the different methods.
     * ie:  `val remoteObject = test as RemoteObject`
     *
     *
     * @return the newly registered RMI ID for this object. [RemoteObjectStorage.INVALID_RMI] means it was invalid (an error log will be emitted)
     *
     * @see RemoteObject
     */
    @Suppress("DuplicatedCode")
    fun saveGlobalObject(`object`: Any): Int {
        val rmiId = rmiGlobalSupport.saveImplObject(`object`)
        if (rmiId == RemoteObjectStorage.INVALID_RMI) {
            val exception = Exception("RMI implementation '${`object`::class.java}' could not be saved! No more RMI id's could be generated")
            ListenerManager.cleanStackTrace(exception)
            listenerManager.notifyError(exception)
            return rmiId
        }
        return rmiId
    }

    /**
     * Tells us to save an an already created object, GLOBALLY using the specified ID, so a remote connection can get it via [Connection.getObject]
     *
     * FOR REMOTE CONNECTIONS:
     * Methods that return a value will throw [TimeoutException] if the response is not received with the
     * response timeout [RemoteObject.responseTimeout].
     *
     * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side
     * will have the proxy object replaced with the registered (non-proxy) object.
     *
     * If one wishes to change the default behavior, cast the object to access the different methods.
     * ie:  `val remoteObject = test as RemoteObject`
     *
     * @return true if the object was successfully saved for the specified ID. If false, an error log will be emitted
     *
     * @see RemoteObject
     */
    @Suppress("DuplicatedCode")
    fun saveGlobalObject(`object`: Any, objectId: Int): Boolean {
        val success = rmiGlobalSupport.saveImplObject(`object`, objectId)
        if (!success) {
            val exception = Exception("RMI implementation '${`object`::class.java}' could not be saved! No more RMI id's could be generated")
            ListenerManager.cleanStackTrace(exception)
            listenerManager.notifyError(exception)
        }
        return success
    }
}
