/*
 * Copyright 2010 dorkbox, llc
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
import dorkbox.network.aeron.client.ClientException
import dorkbox.network.aeron.client.ClientTimedOutException
import dorkbox.network.aeron.server.ClientRejectedException
import dorkbox.network.connection.*
import dorkbox.network.handshake.ClientHandshake
import dorkbox.network.rmi.RemoteObject
import dorkbox.network.rmi.RemoteObjectStorage
import dorkbox.network.rmi.RmiSupportConnection
import dorkbox.network.rmi.TimeoutException
import dorkbox.util.exceptions.SecurityException
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.launch

/**
 * The client is both SYNC and ASYNC. It starts off SYNC (blocks thread until it's done), then once it's connected to the server, it's
 * ASYNC.
 */
open class Client<CONNECTION : Connection>(config: Configuration = Configuration()) : EndPoint<CONNECTION>(config) {
    companion object {
        /**
         * Gets the version number.
         */
        const val version = "5.0"

        /**
         * Split array into chunks, max of 256 chunks.
         * byte[0] = chunk ID
         * byte[1] = total chunks (0-255) (where 0->1, 2->3, 127->127 because this is indexed by a byte)
         */
        private fun divideArray(source: ByteArray, chunksize: Int): Array<ByteArray>? {
            val fragments = Math.ceil(source.size / chunksize.toDouble()).toInt()
            if (fragments > 127) {
                // cannot allow more than 127
                return null
            }

            // pre-allocate the memory
            val splitArray = Array(fragments) { ByteArray(chunksize + 2) }
            var start = 0
            for (i in splitArray.indices) {
                var length: Int
                length = if (start + chunksize > source.size) {
                    source.size - start
                } else {
                    chunksize
                }
                splitArray[i] = ByteArray(length + 2)
                splitArray[i][0] = i.toByte() // index
                splitArray[i][1] = fragments.toByte() // total number of fragments
                System.arraycopy(source, start, splitArray[i], 2, length)
                start += chunksize
            }
            return splitArray
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
    private var remoteAddress = ""

    private val isConnected = atomic(false)

    // is valid when there is a connection to the server, otherwise it is null
    private var connection: CONNECTION? = null

    @Volatile
    protected var connectionTimeoutMS: Long = 5000 // default is 5 seconds

    private val previousClosedConnectionActivity: Long = 0

    override val handshake = ClientHandshake(logger, config, listenerManager, crypto)
    private val rmiConnectionSupport = RmiSupportConnection(logger, rmiGlobalSupport, serialization, actionDispatch)

    init {
        // have to do some basic validation of our configuration
        if (config.publicationPort <= 0) { throw newException("configuration port must be > 0") }
        if (config.publicationPort >= 65535) { throw newException("configuration port must be < 65535") }

        if (config.subscriptionPort <= 0) { throw newException("configuration controlPort must be > 0") }
        if (config.subscriptionPort >= 65535) { throw newException("configuration controlPort must be < 65535") }

        if (config.networkMtuSize <= 0) { throw newException("configuration networkMtuSize must be > 0") }
        if (config.networkMtuSize >= 9 * 1024) { throw newException("configuration networkMtuSize must be < ${9 * 1024}") }

        autoClosableObjects.add(handshake)
    }

    override fun newException(message: String, cause: Throwable?): Throwable {
        return ClientException(message, cause)
    }

    /**
     * So the client class can get remote objects that are THE SAME OBJECT as if called from a connection
     */
    override fun getRmiConnectionSupport(): RmiSupportConnection {
        return rmiConnectionSupport
    }

    /**
     * Will attempt to connect to the server, with a default 30 second connection timeout and will BLOCK until completed
     *
     * For a network address, it can be:
     * - a network name ("localhost", "loopback", "lo", "bob.example.org")
     * - an IP address ("127.0.0.1", "123.123.123.123", "::1")
     *
     * For the IPC (Inter-Process-Communication) address. it must be:
     * - the IPC integer ID, "0x1337c0de", "0x12312312", etc.
     *
     * Note: Case does not matter, and "localhost" is the default. IPC address must be in HEX notation (starting with '0x')
     *
     *
     * @param remoteAddress The network or IPC address for the client to connect to
     * @param connectionTimeout wait for x milliseconds. 0 will wait indefinitely
     * @param reliable true if we want to create a reliable connection. IPC connections are always reliable
     *
     * @throws ClientTimedOutException if the client is unable to connect in x amount of time
     */
    suspend fun connect(remoteAddress: String = "", connectionTimeoutMS: Long = 30_000L, reliable: Boolean = true) {
        if (isConnected.value) {
            logger.error("Unable to connect when already connected!")
            return
        }

        this.connectionTimeoutMS = connectionTimeoutMS
        // localhost/loopback IP might not always be 127.0.0.1 or ::1
        when (remoteAddress) {
            "loopback", "localhost", "lo" -> this.remoteAddress = IPv4.LOCALHOST.hostAddress
            else -> when {
                remoteAddress.startsWith("127.") -> this.remoteAddress = IPv4.LOCALHOST.hostAddress
                remoteAddress.startsWith("::1") -> this.remoteAddress = IPv6.LOCALHOST.hostAddress
                else -> this.remoteAddress = remoteAddress
            }
        }

        // if we are IPv6, the IP must be in '[]'
        if (this.remoteAddress.count { it == ':' } > 1 &&
            this.remoteAddress.count { it == '[' } < 1 &&
            this.remoteAddress.count { it == ']' } < 1) {

            this.remoteAddress = """[${this.remoteAddress}]"""
        }

        if (this.remoteAddress == "0.0.0.0") {
            throw IllegalArgumentException("0.0.0.0 is an invalid address to connect to!")
        }

        handshake.init(this)

        if (this.remoteAddress.isEmpty()) {
            // this is an IPC address

            // When conducting IPC transfers, we MUST use the same aeron configuration as the server!
//            config.aeronLogDirectory




            // stream IDs are flipped for a client because we operate from the perspective of the server
            val handshakeConnection = IpcMediaDriverConnection(
                    streamId = IPC_HANDSHAKE_STREAM_ID_SUB,
                    streamIdSubscription = IPC_HANDSHAKE_STREAM_ID_PUB,
                    sessionId = RESERVED_SESSION_ID_INVALID
            )

            autoClosableObjects.add(handshakeConnection)


            // throws a ConnectTimedOutException if the client cannot connect for any reason to the server handshake ports
            handshakeConnection.buildClient(aeron)
//            logger.debug(handshakeConnection.clientInfo())


            println("CONASD")

            // this will block until the connection timeout, and throw an exception if we were unable to connect with the server

            // @Throws(ConnectTimedOutException::class, ClientRejectedException::class)
            val connectionInfo = handshake.handshakeHello(handshakeConnection, connectionTimeoutMS)
            println("CO23232232323NASD")
        }
        else {
            // THIS IS A NETWORK ADDRESS

            // initially we only connect to the handshake connect ports. Ports are flipped because they are in the perspective of the SERVER
            val handshakeConnection = UdpMediaDriverConnection(
                    address = this.remoteAddress,
                    subscriptionPort = config.publicationPort,
                    publicationPort = config.subscriptionPort,
                    streamId = UDP_HANDSHAKE_STREAM_ID,
                    sessionId = RESERVED_SESSION_ID_INVALID,
                    connectionTimeoutMS = connectionTimeoutMS,
                    isReliable = reliable)

            autoClosableObjects.add(handshakeConnection)

            // throws a ConnectTimedOutException if the client cannot connect for any reason to the server handshake ports
            handshakeConnection.buildClient(aeron)
            logger.debug(handshakeConnection.clientInfo())


            // this will block until the connection timeout, and throw an exception if we were unable to connect with the server

            // @Throws(ConnectTimedOutException::class, ClientRejectedException::class)
            val connectionInfo = handshake.handshakeHello(handshakeConnection, connectionTimeoutMS)


            // we are now connected, so we can connect to the NEW client-specific ports
            val reliableClientConnection = UdpMediaDriverConnection(
                    address = handshakeConnection.address,
                    subscriptionPort = connectionInfo.subscriptionPort,
                    publicationPort = connectionInfo.publicationPort,
                    streamId = connectionInfo.streamId,
                    sessionId = connectionInfo.sessionId,
                    connectionTimeoutMS = connectionTimeoutMS,
                    isReliable = handshakeConnection.isReliable)

            // VALIDATE:: check to see if the remote connection's public key has changed!
            if (!crypto.validateRemoteAddress(IPv4.toInt(this.remoteAddress), connectionInfo.publicKey)) {
                listenerManager.notifyError(ClientRejectedException("Connection to $remoteAddress not allowed! Public key mismatch."))
                return
            }

            // VALIDATE:: If the the serialization DOES NOT match between the client/server, then the server will emit a log, and the
            // client will timeout. SPECIFICALLY.... we do not give class serialization/registration info to the client (in case the client
            // is rogue, we do not want to carelessly provide info.


            // only the client connects to the server, so here we have to connect. The server (when creating the new "connection" object)
            // does not need to do anything
            //
            // throws a ConnectTimedOutException if the client cannot connect for any reason to the server-assigned client ports
            logger.debug(reliableClientConnection.clientInfo())

            val newConnection = newConnection(this, reliableClientConnection)
            autoClosableObjects.add(newConnection)

            // VALIDATE are we allowed to connect to this server (now that we have the initial server information)
            @Suppress("UNCHECKED_CAST")
            val permitConnection = listenerManager.notifyFilter(newConnection)
            if (!permitConnection) {
                listenerManager.notifyError(ClientRejectedException("Connection to $remoteAddress was not permitted!"))
                return
            }

            connection = newConnection
            handshake.addConnection(newConnection)

            // have to make a new thread to listen for incoming data!
            // SUBSCRIPTIONS ARE NOT THREAD SAFE! Only one thread at a time can poll them
            actionDispatch.launch {
                val pollIdleStrategy = config.pollIdleStrategy

                while (!isShutdown()) {
                    val pollCount = newConnection.pollSubscriptions()

                    // 0 means we idle. >0 means reset and don't idle (because there are likely more poll events)
                    pollIdleStrategy.idle(pollCount)
                }
            }

            logger.debug("Next state in logger")

            // tell the server our connection handshake is done, and the connection can now listen for data.
            val canFinishConnecting = handshake.handshakeDone(handshakeConnection, connectionTimeoutMS)
            if (canFinishConnecting) {
                isConnected.lazySet(true)
                listenerManager.notifyConnect(newConnection)
            } else {
                close()
            }
        }
    }

    /**
     * Checks to see if this client has connected yet or not.
     *
     * @return true if we are connected, false otherwise.
     */
    override fun isConnected(): Boolean {
        return isConnected.value
    }

//    override fun hasRemoteKeyChanged(): Boolean {
//        return connection!!.hasRemoteKeyChanged()
//    }
//
//    /**
//     * @return the remote address, as a string.
//     */
//    override fun getRemoteHost(): String {
//        return connection!!.remoteHost
//    }
//
//    /**
//     * @return true if this connection is established on the loopback interface
//     */
//    override fun isLoopback(): Boolean {
//        return connection!!.isLoopback
//    }
//
//    override fun isIPC(): Boolean {
//        return false
//    }

//    /**
//     * @return true if this connection is a network connection
//     */
//    override fun isNetwork(): Boolean {
//        return false
//    }
//
//    /**
//     * @return the connection (TCP or LOCAL) id of this connection.
//     */
//    override fun id(): Int {
//        return connection!!.id()
//    }
//
//    /**
//     * @return the connection (TCP or LOCAL) id of this connection as a HEX string.
//     */
//    override fun idAsHex(): String {
//        return connection!!.idAsHex()
//    }

    /**
     * Tells the remote connection to create a new global object that implements the specified interface.
     *
     * The methods on the returned object will remotely execute on the (remotely) created object
     * The callback will be notified when the remote object has been created.
     *
     * If you want to create a connection specific remote object, call [Connection.create(Int, RemoteObjectCallback<Iface>)] on a connection
     * The callback will be notified when the remote object has been created.
     *
     * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side
     * will have the proxy object replaced with the registered (non-proxy) object.
     *
     *
     * If one wishes to change the remote object behavior, cast the object [RemoteObject] to access the different methods, for example:
     * ie:  `val remoteObject = test as RemoteObject`
     *
     * @see RemoteObject
     */
    suspend inline fun <reified Iface> createObject(noinline callback: suspend (Iface) -> Unit) {
        val classId = serialization.getClassId(Iface::class.java)
        rmiSupport.createGlobalRemoteObject(getConnection(), classId, callback)
    }

    /**
     * Gets a global remote object via the ID.
     *
     * Global remote objects are accessible to ALL connections, where as a connection specific remote object is only accessible/visible
     * to the connection.
     *
     * If you want to access a connection specific remote object, call [Connection.get(Int, RemoteObjectCallback<Iface>)] on a connection
     * The callback will be notified when the remote object has been created.
     *
     * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side
     * will have the proxy object replaced with the registered (non-proxy) object.
     *
     * If one wishes to change the remote object behavior, cast the object to a [RemoteObject] to access the different methods, for example:
     * ie:  `val remoteObject = test as RemoteObject`
     *
     * @see RemoteObject
     */
    fun <Iface> getObject(objectId: Int, interfaceClass: Class<Iface>): Iface {
        return rmiSupport.getGlobalRemoteObject(getConnection(), this, objectId, interfaceClass)
    }

    /**
     * Fetches the connection used by the client.
     *
     *
     * Make **sure** that you only call this **after** the client connects!
     *
     *
     * This is preferred to [EndPoint.getConnections], as it properly does some error checking
     */
    fun getConnection(): C {
        return connection as C
    }

    @Throws(ClientException::class)
    suspend fun send(message: Any) {
        val c = connection
        if (c != null) {
            c.send(message)
        } else {
            throw ClientException("Cannot send a message when there is no connection!")
        }
    }

    @Throws(ClientException::class)
    suspend fun send(message: Any, priority: Byte) {
        val c = connection
        if (c != null) {
            c.send(message, priority)
        } else {
            throw ClientException("Cannot send a message when there is no connection!")
        }
    }

    @Throws(ClientException::class)
    suspend fun ping(): Ping {
        val c = connection
        if (c != null) {
            return c.ping()
        } else {
            throw ClientException("Cannot ping a connection when there is no connection!")
        }
    }

    @Throws(SecurityException::class)
    fun removeRegisteredServerKey(hostAddress: Int) {
        val savedPublicKey = settingsStore.getRegisteredServerKey(hostAddress)
        if (savedPublicKey != null) {
            val logger2 = logger
            if (logger2.isDebugEnabled) {
                logger2.debug("Deleting remote IP address key ${NetworkUtil.IP.toString(hostAddress)}")
            }
            settingsStore.removeRegisteredServerKey(hostAddress)
        }
    }

//    fun initClassRegistration(channel: Channel, registration: Registration): Boolean {
//        val details = serialization.getKryoRegistrationDetails()
//        val length = details.size
//        if (length > Serialization.CLASS_REGISTRATION_VALIDATION_FRAGMENT_SIZE) {
//            // it is too large to send in a single packet
//
//            // child arrays have index 0 also as their 'index' and 1 is the total number of fragments
//            val fragments = divideArray(details, Serialization.CLASS_REGISTRATION_VALIDATION_FRAGMENT_SIZE)
//            if (fragments == null) {
//                logger.error("Too many classes have been registered for Serialization. Please report this issue")
//                return false
//            }
//            val allButLast = fragments.size - 1
//            for (i in 0 until allButLast) {
//                val fragment = fragments[i]
//                val fragmentedRegistration = Registration.hello(registration.oneTimePad, config.settingsStore.getPublicKey())
//                fragmentedRegistration.payload = fragment
//
//                // tell the server we are fragmented
//                fragmentedRegistration.upgradeType = UpgradeType.FRAGMENTED
//
//                // tell the server we are upgraded (it will bounce back telling us to connect)
//                fragmentedRegistration.upgraded = true
//                channel.writeAndFlush(fragmentedRegistration)
//            }
//
//            // now tell the server we are done with the fragments
//            val fragmentedRegistration = Registration.hello(registration.oneTimePad, config.settingsStore.getPublicKey())
//            fragmentedRegistration.payload = fragments[allButLast]
//
//            // tell the server we are fragmented
//            fragmentedRegistration.upgradeType = UpgradeType.FRAGMENTED
//
//            // tell the server we are upgraded (it will bounce back telling us to connect)
//            fragmentedRegistration.upgraded = true
//            channel.writeAndFlush(fragmentedRegistration)
//        } else {
//            registration.payload = details
//
//            // tell the server we are upgraded (it will bounce back telling us to connect)
//            registration.upgraded = true
//            channel.writeAndFlush(registration)
//        }
//        return true
//    }

    // /**
    //  * Closes all connections ONLY (keeps the client running).  To STOP the client, use stop().
    //  * <p/>
    //  * This is used, for example, when reconnecting to a server.
    //  */
    // protected
    // void closeConnection() {
    //     if (isConnected.get()) {
    //         // make sure we're not waiting on registration
    //         stopRegistration();
    //
    //         // for the CLIENT only, we clear these connections! (the server only clears them on shutdown)
    //
    //         // stop does the same as this + more.  Only keep the listeners for connections IF we are the client. If we remove listeners as a client,
    //         // ALL of the client logic will be lost. The server is reactive, so listeners are added to connections as needed (instead of before startup)
    //         connectionManager.closeConnections(true);
    //
    //         // Sometimes there might be "lingering" connections (ie, halfway though registration) that need to be closed.
    //         registrationWrapper.clearSessions();
    //
    //
    //         closeConnections(true);
    //         shutdownAllChannels();
    //         // shutdownEventLoops();  we don't do this here!
    //
    //         connection = null;
    //         isConnected.set(false);
    //
    //         previousClosedConnectionActivity = System.nanoTime();
    //     }
    // }
//    /**
//     * Internal call to abort registration if the shutdown command is issued during channel registration.
//     */
//    @Suppress("unused")
//    fun abortRegistration() {
//        // make sure we're not waiting on registration
////        stopRegistration()
//    }
}
