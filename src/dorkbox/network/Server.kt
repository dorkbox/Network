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

import dorkbox.netUtil.IPv4
import dorkbox.netUtil.IPv6
import dorkbox.network.aeron.server.ServerException
import dorkbox.network.connection.Connection
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.IpcMediaDriverConnection
import dorkbox.network.connection.UdpMediaDriverConnection
import dorkbox.network.connection.connectionType.ConnectionProperties
import dorkbox.network.connection.connectionType.ConnectionRule
import dorkbox.network.handshake.ServerHandshake
import dorkbox.network.rmi.RemoteObject
import dorkbox.network.rmi.RemoteObjectStorage
import dorkbox.network.rmi.TimeoutException
import io.aeron.FragmentAssembler
import io.aeron.logbuffer.FragmentHandler
import io.aeron.logbuffer.Header
import kotlinx.coroutines.launch
import org.agrona.DirectBuffer
import java.net.InetSocketAddress
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


    private val handshake = ServerHandshake(logger, config, listenerManager)

    /**
     * Maintains a thread-safe collection of rules used to define the connection type with this server.
     */
    private val connectionRules = CopyOnWriteArrayList<ConnectionRule>()

    init {
        // have to do some basic validation of our configuration
        config.listenIpAddress = config.listenIpAddress.toLowerCase()

        // localhost/loopback IP might not always be 127.0.0.1 or ::1
        when (config.listenIpAddress) {
            "loopback", "localhost", "lo" -> config.listenIpAddress = IPv4.LOCALHOST.hostAddress
            else -> when {
                IPv4.isLoopback(config.listenIpAddress) -> config.listenIpAddress = IPv4.LOCALHOST.hostAddress
                IPv6.isLoopback(config.listenIpAddress) -> config.listenIpAddress = IPv6.LOCALHOST.hostAddress
                else -> config.listenIpAddress = "0.0.0.0" // we set this to "0.0.0.0" so that it is clear that we are trying to bind to that address.
            }
        }

        // if we are IPv4 wildcard
        if (config.listenIpAddress == "0.0.0.0") {
            // this will also fixup windows!
            config.listenIpAddress = IPv4.WILDCARD
        }

        if (IPv6.isValid(config.listenIpAddress)) {
            // "[" and "]" are valid for ipv6 addresses... we want to make sure it is so

            // if we are IPv6, the IP must be in '[]'
            if (config.listenIpAddress.count { it == '[' } < 1 &&
                config.listenIpAddress.count { it == ']' } < 1) {

                config.listenIpAddress = """[${config.listenIpAddress}]"""
            }
        }


        if (config.publicationPort <= 0) { throw ServerException("configuration port must be > 0") }
        if (config.publicationPort >= 65535) { throw ServerException("configuration port must be < 65535") }

        if (config.subscriptionPort <= 0) { throw ServerException("configuration controlPort must be > 0") }
        if (config.subscriptionPort >= 65535) { throw ServerException("configuration controlPort must be < 65535") }

        if (config.networkMtuSize <= 0) { throw ServerException("configuration networkMtuSize must be > 0") }
        if (config.networkMtuSize >= 9 * 1024) { throw ServerException("configuration networkMtuSize must be < ${9 * 1024}") }

        if (config.maxConnectionsPerIpAddress == 0) { config.maxConnectionsPerIpAddress = config.maxClientCount}
    }

    override fun newException(message: String, cause: Throwable?): Throwable {
        return ServerException(message, cause)
    }

    /**
     * Binds the server to AERON configuration
     *
     * @param blockUntilTerminate if true, will BLOCK until the server [close] method is called, and if you want to continue running code
     * after this pass in false
     */
    @JvmOverloads
    fun bind(blockUntilTerminate: Boolean = true) {
        if (bindAlreadyCalled) {
            logger.error("Unable to bind when the server is already running!")
            return
        }

        bindAlreadyCalled = true

        config as ServerConfiguration

        // setup the "HANDSHAKE" ports, for initial clients to connect.
        // The is how clients then get the new ports to connect to + other configuration options

        val handshakeDriver = UdpMediaDriverConnection(address = config.listenIpAddress,
                                                       publicationPort = config.publicationPort,
                                                       subscriptionPort = config.subscriptionPort,
                                                       streamId = UDP_HANDSHAKE_STREAM_ID,
                                                       sessionId = RESERVED_SESSION_ID_INVALID)

        handshakeDriver.buildServer(aeron)

        val handshakePublication = handshakeDriver.publication
        val handshakeSubscription = handshakeDriver.subscription

        logger.info(handshakeDriver.serverInfo())


        val ipcHandshakeDriver = IpcMediaDriverConnection(
                streamId = IPC_HANDSHAKE_STREAM_ID_PUB,
                streamIdSubscription = IPC_HANDSHAKE_STREAM_ID_SUB,
                sessionId = RESERVED_SESSION_ID_INVALID
        )
        ipcHandshakeDriver.buildServer(aeron)

        val ipcHandshakePublication = ipcHandshakeDriver.publication
        val ipcHandshakeSubscription = ipcHandshakeDriver.subscription


        /**
         * Note:
         * Reassembly has been shown to be minimal impact to latency. But not totally negligible. If the lowest latency is
         * desired, then limiting message sizes to MTU size is a good practice.
         *
         * There is a maximum length allowed for messages which is the min of 1/8th a term length or 16MB.
         * Messages larger than this should chunked using an application level chunking protocol. Chunking has better recovery
         * properties from failure and streams with mechanical sympathy.
         */
        val initialConnectionHandler = FragmentAssembler(FragmentHandler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
            actionDispatch.launch {
                handshake.receiveHandshakeMessageServer(handshakePublication, buffer, offset, length, header, this@Server)
            }
        })
        val ipcInitialConnectionHandler = FragmentAssembler(FragmentHandler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
            actionDispatch.launch {
                println("GOT MESSAGE!")
            }
        })

        actionDispatch.launch {
            val pollIdleStrategy = config.pollIdleStrategy

            try {
                var pollCount: Int

                while (!isShutdown()) {
                    pollCount = 0

                    // this checks to see if there are NEW clients on the handshake ports
                    pollCount += handshakeSubscription.poll(initialConnectionHandler, 100)

                    // this checks to see if there are NEW clients via IPC
//                    pollCount += ipcHandshakeSubscription.poll(ipcInitialConnectionHandler, 100)


                    // this manages existing clients (for cleanup + connection polling)
                    connections.forEachWithCleanup({ connection ->
                        // If the connection has either been closed, or has expired, it needs to be cleaned-up/deleted.
                        var shouldCleanupConnection = false

                        if (connection.isExpired()) {
                            logger.debug {"[${connection.sessionId}] connection expired"}
                            shouldCleanupConnection = true
                        }

                        else if (connection.isClosed()) {
                            logger.debug {"[${connection.sessionId}] connection closed"}
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
                        logger.debug {"[${connectionToClean.sessionId}] removed connection"}

                        // have to free up resources!
                        handshake.cleanup(connectionToClean)

                        connectionToClean.close()
                        listenerManager.notifyDisconnect(connectionToClean)
                    })


                    // 0 means we idle. >0 means reset and don't idle (because there are likely more poll events)
                    pollIdleStrategy.idle(pollCount)
                }
            } finally {
                handshakePublication.close()
                handshakeSubscription.close()

                ipcHandshakePublication.close()
                ipcHandshakeSubscription.close()
            }
        }


        // we now BLOCK until the stop method is called.
        if (blockUntilTerminate) {
            waitForShutdown();
        }
    }

    internal suspend fun poll(): Int {

        var pollCount = 0



        return pollCount
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
     * Safely sends objects to a destination
     */
    suspend fun send(message: Any) {
        connections.send(message)
    }

    /**
     * When called by a server, NORMALLY listeners are added at the GLOBAL level (meaning, I add one listener,
     * and ALL connections are notified of that listener.
     * <br></br>
     * It is POSSIBLE to add a server-connection 'local' listener (via connection.addListener), meaning that ONLY
     * that listener attached to the connection is notified on that event (ie, admin type listeners)
     *
     * @return a newly created listener manager for the connection
     */
//    fun addListenerManager(connection: C): ConnectionManager<C> {
//        return connectionManager.addListenerManager(connection)
//    }

    /**
     * When called by a server, NORMALLY listeners are added at the GLOBAL level (meaning, I add one listener,
     * and ALL connections are notified of that listener.
     * <br></br>
     * It is POSSIBLE to remove a server-connection 'local' listener (via connection.removeListener), meaning that ONLY
     * that listener attached to the connection is removed
     *
     *
     * This removes the listener manager for that specific connection
     */
//    fun removeListenerManager(connection: C) {
//        connectionManager.removeListenerManager(connection)
//    }



//
//
//    /**
//     * Creates a "global" remote object for use by multiple connections.
//     *
//     * @return the ID assigned to this RMI object
//     */
//    fun <T> create(objectId: Short, globalObject: T) {
//        return rmiGlobalObjects.register(globalObject)
//    }
//
//    /**
//     * Creates a "global" remote object for use by multiple connections.
//     *
//     * @return the ID assigned to this RMI object
//     */
//    fun <T> create(`object`: T): Short {
//        return rmiGlobalObjects.register(`object`) ?: 0
//    }
//
//
//





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
     * Checks to see if a server (using the specified configuration) is running.
     *
     * @return true if the server is active and running
     */
    fun isRunning(): Boolean {
        return mediaDriver.context().isDriverActive(10_000, logger::debug)
    }

    override fun close() {
        super.close()
        bindAlreadyCalled = false
    }



    /**
     * Only called by the server!
     *
     * If we are loopback or the client is a specific IP/CIDR address, then we do things differently. The LOOPBACK address will never encrypt or compress the traffic.
     */
    // after the handshake, what sort of connection do we want (NONE, COMPRESS, ENCRYPT+COMPRESS)
    fun getConnectionUpgradeType(remoteAddress: InetSocketAddress): Byte {
        val address = remoteAddress.address
        val size = connectionRules.size

        // if it's unknown, then by default we encrypt the traffic
        var connectionType = ConnectionProperties.COMPRESS_AND_ENCRYPT
        if (size == 0 && address == IPv4.LOCALHOST) {
            // if nothing is specified, then by default localhost is compression and everything else is encrypted
            connectionType = ConnectionProperties.COMPRESS
        }
        for (i in 0 until size) {
            val rule = connectionRules[i] ?: continue
            if (rule.matches(remoteAddress)) {
                connectionType = rule.ruleType()
                break
            }
        }
        logger.debug("Validating {}  Permitted type is: {}", remoteAddress, connectionType)
        return connectionType.type
    }




//    enum class STATE {
//        ERROR, WAIT, CONTINUE
//    }

//    fun verifyClassRegistration(metaChannel: MetaChannel, registration: Registration): STATE {
//        if (registration.upgradeType == UpgradeType.FRAGMENTED) {
//            val fragment = registration.payload!!
//
//            // this means that the registrations are FRAGMENTED!
//            // max size of ALL fragments is xxx * 127
//            if (metaChannel.fragmentedRegistrationDetails == null) {
//                metaChannel.remainingFragments = fragment[1]
//                metaChannel.fragmentedRegistrationDetails = ByteArray(Serialization.CLASS_REGISTRATION_VALIDATION_FRAGMENT_SIZE * fragment[1])
//            }
//            System.arraycopy(fragment, 2, metaChannel.fragmentedRegistrationDetails, fragment[0] * Serialization.CLASS_REGISTRATION_VALIDATION_FRAGMENT_SIZE, fragment.size - 2)
//
//            metaChannel.remainingFragments--
//
//            if (fragment[0] + 1 == fragment[1].toInt()) {
//                // this is the last fragment in the in byte array (but NOT necessarily the last fragment to arrive)
//                val correctSize = Serialization.CLASS_REGISTRATION_VALIDATION_FRAGMENT_SIZE * (fragment[1] - 1) + (fragment.size - 2)
//                val correctlySized = ByteArray(correctSize)
//                System.arraycopy(metaChannel.fragmentedRegistrationDetails, 0, correctlySized, 0, correctSize)
//                metaChannel.fragmentedRegistrationDetails = correctlySized
//            }
//            if (metaChannel.remainingFragments.toInt() == 0) {
//                // there are no more fragments available
//                val details = metaChannel.fragmentedRegistrationDetails
//                metaChannel.fragmentedRegistrationDetails = null
//                if (!serialization.verifyKryoRegistration(details)) {
//                    // error
//                    return STATE.ERROR
//                }
//            } else {
//                // wait for more fragments
//                return STATE.WAIT
//            }
//        } else {
//            if (!serialization.verifyKryoRegistration(registration.payload!!)) {
//                return STATE.ERROR
//            }
//        }
//        return STATE.CONTINUE
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
    fun saveGlobalObject(`object`: Any): Int {
        return rmiGlobalSupport.saveImplObject(logger, `object`)
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
    fun saveGlobalObject(`object`: Any, objectId: Int): Boolean {
        return rmiGlobalSupport.saveImplObject(logger, `object`, objectId)
    }
}
