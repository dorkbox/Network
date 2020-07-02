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

import dorkbox.network.aeron.client.ClientException
import dorkbox.network.aeron.client.ClientTimedOutException
import dorkbox.network.connection.*
import dorkbox.util.exceptions.SecurityException
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * The client is both SYNC and ASYNC. It starts off SYNC (blocks thread until it's done), then once it's connected to the server, it's
 * ASYNC.
 */
open class Client<C : Connection>(config: Configuration = Configuration()) : EndPoint<C>(config) {
    companion object {
        /**
         * Gets the version number.
         */
        const val version = "4.1"

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
    private var connection: C? = null

    @Volatile
    protected var connectionTimeoutMS: Long = 5000 // default is 5 seconds

    private val previousClosedConnectionActivity: Long = 0

    // we don't verify anything on the CLIENT. We only verify on the server.
    // we don't support registering NEW classes after the client starts.

    // ALSO make sure to verify registration details

    // we don't verify anything on the CLIENT. We only verify on the server.
    // we don't support registering NEW classes after the client starts.
//    if (!registrationWrapper.initClassRegistration(channel, registration))
//    {
//        // abort if something messed up!
//        shutdown(channel, registration.sessionID)
//    }

    override val connectionManager = ConnectionManagerClient<C>(logger, config)

    init {
        // have to do some basic validation of our configuration
        if (config.publicationPort <= 0) { throw ClientException("configuration port must be > 0") }
        if (config.publicationPort >= 65535) { throw ClientException("configuration port must be < 65535") }

        if (config.subscriptionPort <= 0) { throw ClientException("configuration controlPort must be > 0") }
        if (config.subscriptionPort >= 65535) { throw ClientException("configuration controlPort must be < 65535") }

        if (config.networkMtuSize <= 0) { throw ClientException("configuration networkMtuSize must be > 0") }
        if (config.networkMtuSize >= 9 * 1024) { throw ClientException("configuration networkMtuSize must be < ${9 * 1024}") }

        closables.add(connectionManager)
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
     * @throws IOException if the client is unable to connect in x amount of time
     */
    @JvmOverloads
    @Throws(IOException::class, ClientTimedOutException::class)
    suspend fun connect(remoteAddress: String = "localhost", connectionTimeoutMS: Long = 30_000L, reliable: Boolean = true) {
        if (isConnected.value) {
            throw IOException("Unable to connect when already connected!");
        }

        this.connectionTimeoutMS = connectionTimeoutMS
        // localhost/loopback IP might not always be 127.0.0.1 or ::1
        when (remoteAddress) {
            "loopback", "localhost", "lo" -> this.remoteAddress = NetUtil.LOCALHOST.hostAddress
            else -> when {
                remoteAddress.startsWith("127.") -> this.remoteAddress = NetUtil.LOCALHOST.hostAddress
                remoteAddress.startsWith("::1") -> this.remoteAddress = NetUtil.LOCALHOST6.hostAddress
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


        // this is an IPC address
        if (this.remoteAddress.startsWith("0x")) {
            val ipcAddress: Long
            try {
                ipcAddress = remoteAddress.toLong(radix = 16)
            } catch (e: Exception) {
                throw IOException(e)
            }

            // val connectionType3 = ConnectionType(config.remoteAddress, config.controlPort, config.port, false, MediaDriverType.IPC, IPC_STREAM_ID)
        }
        else {
            // this is a network address

            // initially we only connect to the client connect ports. Ports are flipped because they are in the perspective of the SERVER
            val handshakeConnection = UdpMediaDriverConnection(
                    this.remoteAddress, config.publicationPort, config.subscriptionPort,
                    UDP_HANDSHAKE_STREAM_ID, RESERVED_SESSION_ID_INVALID,
                    connectionTimeoutMS, reliable)

            closables.add(handshakeConnection)

            // throws a ConnectTimedOutException if the client cannot connect for any reason to the server handshake ports
            handshakeConnection.buildClient(aeron)
            logger.debug(handshakeConnection.clientInfo())


            // this will block until the connection timeout, and throw an exception if we were unable to connect with the server

            // @Throws(ConnectTimedOutException::class, ClientRejectedException::class)
            val connectionInfo = connectionManager.initHandshake(handshakeConnection, connectionTimeoutMS, this@Client)


            // we are now connected, so we can connect to the NEW client-specific ports
            val reliableClientConnection = UdpMediaDriverConnection(handshakeConnection.address, connectionInfo.subscriptionPort, connectionInfo.publicationPort,
                    connectionInfo.streamId, connectionInfo.sessionId, connectionTimeoutMS, handshakeConnection.isReliable)

            // VALIDATE:: check to see if the remote connection's public key has changed!
            if (!validateRemoteAddress(NetworkUtil.IP.toInt(this.remoteAddress), connectionInfo.publicKey)) {
                // TODO: this should provide info to a callback
                println("connection not allowed! public key mismatch")
                return
            }

            // VALIDATE TODO: make sure the serialization matches between the client/server! ?? One the client, it will just time out i
            //  think, because we don't want to give validation information to the client... (so clients cannot probe what the registrations
            //  are)




            // only the client connects to the server, so here we have to connect. The server (when creating the new "connection" object)
            // does not need to do anything
            //
            // throws a ConnectTimedOutException if the client cannot connect for any reason to the server-assigned client ports
            logger.debug(reliableClientConnection.clientInfo())

            val newConnection = newConnection(this, reliableClientConnection)
            closables.add(newConnection)
            connection = newConnection

            // VALIDATE are we allowed to connect to this server (now that we have the initial server information)
            @Suppress("UNCHECKED_CAST")
            val permitConnection = connectionManager.notifyFilter(newConnection)
            if (!permitConnection) {
                // TODO: this should provide info to a callback
                println("connection not allowed!")
                return
            }

            // have to make a new thread to listen for incoming data!
            // SUBSCRIPTIONS ARE NOT THREAD SAFE!
            actionDispatch.launch {
                val pollIdleStrategy = config.pollIdleStrategy

                while (!isShutdown()) {
                    val pollCount = newConnection.pollSubscriptions()

                    // 0 means we idle. >0 means reset and don't idle (because there are likely more poll events)
                    pollIdleStrategy.idle(pollCount)
                }
            }

            isConnected.lazySet(true)
            connectionManager.notifyConnect(newConnection)
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
     * Tells the remote connection to create a new proxy object that implements the specified interface. The methods on this object "map"
     * to an object that is created remotely.
     *
     *
     * The callback will be notified when the remote object has been created.
     *
     *
     *
     *
     * Methods that return a value will throw [TimeoutException] if the response is not received with the
     * [response timeout][RemoteObject.setResponseTimeout].
     *
     *
     * If [non-blocking][RemoteObject.setAsync] is false (the default), then methods that return a value must
     * not be called from the update thread for the connection. An exception will be thrown if this occurs. Methods with a
     * void return value can be called on the update thread.
     *
     *
     * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side
     * will have the proxy object replaced with the registered (non-proxy) object.
     *
     *
     * If one wishes to change the default behavior, cast the object to access the different methods.
     * ie:  `RemoteObject remoteObject = (RemoteObject) test;`
     *
     * @see RemoteObject
     */
//    override fun <Iface> createRemoteObject(interfaceClass: Class<Iface>, callback: RemoteObjectCallback<Iface>) {
//        try {
//            connection!!.createRemoteObject(interfaceClass, callback)
//        } catch (e: NullPointerException) {
//            logger.error("Error creating remote object!", e)
//        }
//    }

    /**
     * Tells the remote connection to create a new proxy object that implements the specified interface. The methods on this object "map"
     * to an object that is created remotely.
     *
     *
     * The callback will be notified when the remote object has been created.
     *
     *
     *
     *
     * Methods that return a value will throw [TimeoutException] if the response is not received with the
     * [response timeout][RemoteObject.setResponseTimeout].
     *
     *
     * If [non-blocking][RemoteObject.setAsync] is false (the default), then methods that return a value must
     * not be called from the update thread for the connection. An exception will be thrown if this occurs. Methods with a
     * void return value can be called on the update thread.
     *
     *
     * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side
     * will have the proxy object replaced with the registered (non-proxy) object.
     *
     *
     * If one wishes to change the default behavior, cast the object to access the different methods.
     * ie:  `RemoteObject remoteObject = (RemoteObject) test;`
     *
     * @see RemoteObject
     */
//    override fun <Iface> getRemoteObject(objectId: Int, callback: RemoteObjectCallback<Iface>) {
//        try {
//            connection!!.getRemoteObject(objectId, callback)
//        } catch (e: NullPointerException) {
//            logger.error("Error getting remote object!", e)
//        }
//    }


    /**
     * Fetches the connection used by the client.
     *
     *
     * Make **sure** that you only call this **after** the client connects!
     *
     *
     * This is preferred to [EndPoint.getConnections], as it properly does some error checking
     */
    // can =just use super.get connection?
//    override var connection: C = TODO()
//        get() = field
//        set(connection) {
//            super.connection = connection
//        }



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
