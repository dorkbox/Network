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
import dorkbox.network.aeron.IpcMediaDriverConnection
import dorkbox.network.aeron.UdpMediaDriverConnection
import dorkbox.network.connection.Connection
import dorkbox.network.connection.ConnectionParams
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.ListenerManager
import dorkbox.network.connection.PublicKeyValidationState
import dorkbox.network.exceptions.ClientException
import dorkbox.network.exceptions.ClientRejectedException
import dorkbox.network.exceptions.ClientTimedOutException
import dorkbox.network.handshake.ClientHandshake
import dorkbox.network.other.coroutines.SuspendWaiter
import dorkbox.network.rmi.RemoteObject
import dorkbox.network.rmi.RemoteObjectStorage
import dorkbox.network.rmi.RmiManagerConnections
import dorkbox.network.rmi.TimeoutException
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

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
    private var remoteAddress0: InetAddress? = IPv4.LOCALHOST

    @Volatile
    private var isConnected = false

    // is valid when there is a connection to the server, otherwise it is null
    private var connection0: CONNECTION? = null

    private val previousClosedConnectionActivity: Long = 0

    private val rmiConnectionSupport = RmiManagerConnections(logger, rmiGlobalSupport, serialization)

    private val lockStepForReconnect = atomic<SuspendWaiter?>(null)

    init {
        // have to do some basic validation of our configuration
        if (config.publicationPort <= 0) { throw ClientException("configuration port must be > 0") }
        if (config.publicationPort >= 65535) { throw ClientException("configuration port must be < 65535") }

        if (config.subscriptionPort <= 0) { throw ClientException("configuration controlPort must be > 0") }
        if (config.subscriptionPort >= 65535) { throw ClientException("configuration controlPort must be < 65535") }

        if (config.networkMtuSize <= 0) { throw ClientException("configuration networkMtuSize must be > 0") }
        if (config.networkMtuSize >= 9 * 1024) { throw ClientException("configuration networkMtuSize must be < ${9 * 1024}") }
    }

    override fun newException(message: String, cause: Throwable?): Throwable {
        return ClientException(message, cause)
    }

    /**
     * So the client class can get remote objects that are THE SAME OBJECT as if called from a connection
     */
    override fun getRmiConnectionSupport(): RmiManagerConnections<CONNECTION> {
        return rmiConnectionSupport
    }

    /**
     * Will attempt to connect to the server, with a default 30 second connection timeout and will block until completed.
     *
     * Default connection is to localhost
     *
     * ### For a network address, it can be:
     *  - a network name ("localhost", "loopback", "lo", "bob.example.org")
     *  - an IP address ("127.0.0.1", "123.123.123.123", "::1")
     *  - an InetAddress address
     *
     * ### For the IPC (Inter-Process-Communication) it must be:
     * - EMPTY.
     *  - `connect()`
     *  - `connect("")`
     *
     * ### Case does not matter, and "localhost" is the default. IPC address must be in HEX notation (starting with '0x')
     *
     * @param remoteAddress The network or if localhost, IPC address for the client to connect to
     * @param connectionTimeoutMS wait for x milliseconds. 0 will wait indefinitely
     * @param reliable true if we want to create a reliable connection. IPC connections are always reliable
     *
     * @throws IllegalArgumentException if the remote address is invalid
     * @throws ClientTimedOutException if the client is unable to connect in x amount of time
     * @throws ClientRejectedException if the client connection is rejected
     */
    suspend fun connect(remoteAddress: String,
                        connectionTimeoutMS: Long = 30_000L, reliable: Boolean = true) {
        when {
            // this is default IPC settings
            remoteAddress.isEmpty() -> connect(connectionTimeoutMS = connectionTimeoutMS)

            IPv4.isPreferred -> connect(remoteAddress = Inet4Address.getAllByName(remoteAddress)[0],
                                        connectionTimeoutMS = connectionTimeoutMS,
                                        reliable = reliable)

            else -> connect(remoteAddress = Inet4Address.getAllByName(remoteAddress)[0],
                            connectionTimeoutMS = connectionTimeoutMS,
                            reliable = reliable)
        }
    }

    /**
     * Will attempt to connect to the server, with a default 30 second connection timeout and will block until completed.
     *
     * Default connection is to localhost
     *
     * ### For a network address, it can be:
     *  - a network name ("localhost", "loopback", "lo", "bob.example.org")
     *  - an IP address ("127.0.0.1", "123.123.123.123", "::1")
     *  - an InetAddress address
     *
     * ### For the IPC (Inter-Process-Communication) it must be:
     * - EMPTY.
     *  - `connect()`
     *  - `connect("")`
     *
     * ### Case does not matter, and "localhost" is the default. IPC address must be in HEX notation (starting with '0x')
     *
     * @param remoteAddress The network or if localhost, IPC address for the client to connect to
     * @param connectionTimeoutMS wait for x milliseconds. 0 will wait indefinitely
     * @param reliable true if we want to create a reliable connection. IPC connections are always reliable
     *
     * @throws IllegalArgumentException if the remote address is invalid
     * @throws ClientTimedOutException if the client is unable to connect in x amount of time
     * @throws ClientRejectedException if the client connection is rejected
     */
    suspend fun connect(remoteAddress: InetAddress,
                        connectionTimeoutMS: Long = 30_000L, reliable: Boolean = true) {
        // Default IPC ports are flipped because they are in the perspective of the SERVER
        connect(remoteAddress = remoteAddress,
                ipcPublicationId = IPC_HANDSHAKE_STREAM_ID_SUB,
                ipcSubscriptionId = IPC_HANDSHAKE_STREAM_ID_PUB,
                connectionTimeoutMS = connectionTimeoutMS,
                reliable = reliable)
    }

    /**
     * Will attempt to connect to the server via IPC, with a default 30 second connection timeout and will block until completed.
     *
     * @param ipcPublicationId The IPC publication address for the client to connect to
     * @param ipcSubscriptionId The IPC subscription address for the client to connect to
     * @param connectionTimeoutMS wait for x milliseconds. 0 will wait indefinitely.
     *
     * @throws IllegalArgumentException if the remote address is invalid
     * @throws ClientTimedOutException if the client is unable to connect in x amount of time
     * @throws ClientRejectedException if the client connection is rejected
     */
    @Suppress("DuplicatedCode")
    suspend fun connect(ipcPublicationId: Int = IPC_HANDSHAKE_STREAM_ID_SUB,
                        ipcSubscriptionId: Int = IPC_HANDSHAKE_STREAM_ID_PUB,
                        connectionTimeoutMS: Long = 30_000L) {
        // Default IPC ports are flipped because they are in the perspective of the SERVER

        require(ipcPublicationId != ipcSubscriptionId) { "IPC publication and subscription ports cannot be the same! The must match the server's configuration." }

        connect(remoteAddress = null, // required!
                ipcPublicationId = ipcPublicationId,
                ipcSubscriptionId = ipcSubscriptionId,
                connectionTimeoutMS = connectionTimeoutMS)
    }

    /**
     * Will attempt to connect to the server, with a default 30 second connection timeout and will block until completed.
     *
     * Default connection is to localhost
     *
     * ### For a network address, it can be:
     *  - a network name ("localhost", "loopback", "lo", "bob.example.org")
     *  - an IP address ("127.0.0.1", "123.123.123.123", "::1")
     *
     * ### For the IPC (Inter-Process-Communication) it must be:
     * - EMPTY. ie: just call `connect()`
     * - Specified EMPTY. ie: just call `connect()`
     *
     * ### Case does not matter, and "localhost" is the default. IPC address must be in HEX notation (starting with '0x')
     *
     * @param remoteAddress The network or if localhost, IPC address for the client to connect to
     * @param ipcPublicationId The IPC publication address for the client to connect to
     * @param ipcSubscriptionId The IPC subscription address for the client to connect to
     * @param connectionTimeoutMS wait for x milliseconds. 0 will wait indefinitely.
     * @param reliable true if we want to create a reliable connection. IPC connections are always reliable
     *
     * @throws IllegalArgumentException if the remote address is invalid
     * @throws ClientTimedOutException if the client is unable to connect in x amount of time
     * @throws ClientRejectedException if the client connection is rejected
     */
    @Suppress("DuplicatedCode")
    private suspend fun connect(remoteAddress: InetAddress? = null,
                                // Default IPC ports are flipped because they are in the perspective of the SERVER
                                ipcPublicationId: Int = IPC_HANDSHAKE_STREAM_ID_SUB,
                                ipcSubscriptionId: Int = IPC_HANDSHAKE_STREAM_ID_PUB,
                                connectionTimeoutMS: Long = 30_000L, reliable: Boolean = true) {
        // this will exist ONLY if we are reconnecting via a "disconnect" callback
        lockStepForReconnect.value?.doWait()

        if (isConnected) {
            logger.error("Unable to connect when already connected!")
            return
        }

        lockStepForReconnect.lazySet(null)
        connection0 = null

        // we are done with initial configuration, now initialize aeron and the general state of this endpoint
        val aeron = initEndpointState()

        // only change LOCALHOST -> IPC if the media driver is ALREADY running LOCALLY!
        val canAutoChangeToIpc = config.enableIpcForLoopback && isRunning()
        if (canAutoChangeToIpc) {
            logger.info("Media driver is already running. Support for auto-switch LOCALHOST -> IPC is enabled")
        }

        // only try to connect via IPv4 if we have a network interface that supports it!
        if (remoteAddress is Inet4Address && !IPv4.isAvailable) {
            require(false) { "Unable to connect to the IPv4 address $remoteAddress, there are no IPv4 interfaces available!"}
        }

        // only try to connect via IPv6 if we have a network interface that supports it!
        if (remoteAddress is Inet6Address && !IPv6.isAvailable) {
            require(false) { "Unable to connect to the IPv6 address $remoteAddress, there are no IPv6 interfaces available!"}
        }


        // NETWORK OR IPC ADDRESS
        // if we connect to "loopback", then MAYBE we substitute if for IPC (with log message)

        // localhost/loopback IP might not always be 127.0.0.1 or ::1
        if (remoteAddress == null) {
            this.remoteAddress0 = null
        } else if (remoteAddress.isAnyLocalAddress) {
            throw IllegalArgumentException("Cannot connect to $remoteAddress It is an invalid address!")
        } else if (canAutoChangeToIpc && remoteAddress.isLoopbackAddress) {
            logger.info { "Auto-changing network connection from $remoteAddress -> IPC" }
            this.remoteAddress0 = null
        } else {
            this.remoteAddress0 = remoteAddress
        }


        val handshake = ClientHandshake(logger, config, crypto, this)


        val handshakeConnection = if (this.remoteAddress0 == null) {
            IpcMediaDriverConnection(streamIdSubscription = ipcSubscriptionId,
                                     streamId = ipcPublicationId,
                                     sessionId = RESERVED_SESSION_ID_INVALID)
        }
        else {
            UdpMediaDriverConnection(address = this.remoteAddress0!!,
                                     publicationPort = config.subscriptionPort,
                                     subscriptionPort = config.publicationPort,
                                     streamId = UDP_HANDSHAKE_STREAM_ID,
                                     sessionId = RESERVED_SESSION_ID_INVALID,
                                     connectionTimeoutMS = connectionTimeoutMS,
                                     isReliable = reliable)
        }


        // throws a ConnectTimedOutException if the client cannot connect for any reason to the server handshake ports
        handshakeConnection.buildClient(aeron, logger)
        logger.info(handshakeConnection.clientInfo())


        // this will block until the connection timeout, and throw an exception if we were unable to connect with the server

        // @Throws(ConnectTimedOutException::class, ClientRejectedException::class)
        val connectionInfo = handshake.handshakeHello(handshakeConnection, connectionTimeoutMS)


        // VALIDATE:: check to see if the remote connection's public key has changed!
        val validateRemoteAddress = if (this.remoteAddress0 == null) {
            PublicKeyValidationState.VALID
        } else {
            crypto.validateRemoteAddress(this.remoteAddress0!!, connectionInfo.publicKey)
        }

        if (validateRemoteAddress == PublicKeyValidationState.INVALID) {
            handshakeConnection.close()
            val exception = ClientRejectedException("Connection to $remoteAddress not allowed! Public key mismatch.")
            listenerManager.notifyError(exception)
            throw exception
        }


        // VALIDATE:: If the the serialization DOES NOT match between the client/server, then the server will emit a log, and the
        // client will timeout. SPECIFICALLY.... we do not give class serialization/registration info to the client (in case the client
        // is rogue, we do not want to carelessly provide info.


        // we are now connected, so we can connect to the NEW client-specific ports
        val reliableClientConnection = if (this.remoteAddress0 == null) {
            IpcMediaDriverConnection(sessionId = connectionInfo.sessionId,
                                     // NOTE: pub/sub must be switched!
                                     streamIdSubscription = connectionInfo.publicationPort,
                                     streamId = connectionInfo.subscriptionPort,
                                     connectionTimeoutMS = connectionTimeoutMS)
        }
        else {
            UdpMediaDriverConnection(address = handshakeConnection.address!!,
                                     // NOTE: pub/sub must be switched!
                                     subscriptionPort = connectionInfo.publicationPort,
                                     publicationPort = connectionInfo.subscriptionPort,
                                     streamId = connectionInfo.streamId,
                                     sessionId = connectionInfo.sessionId,
                                     connectionTimeoutMS = connectionTimeoutMS,
                                     isReliable = handshakeConnection.isReliable)
        }

        // we have to construct how the connection will communicate!
        reliableClientConnection.buildClient(aeron, logger)

        // only the client connects to the server, so here we have to connect. The server (when creating the new "connection" object)
        // does not need to do anything
        //
        // throws a ConnectTimedOutException if the client cannot connect for any reason to the server-assigned client ports
        logger.info(reliableClientConnection.clientInfo())


        ///////////////
        ////   RMI
        ///////////////

        // we setup our kryo information once we connect to a server (using the server's kryo registration details)
        if (!serialization.finishInit(type, settingsStore, connectionInfo.kryoRegistrationDetails)) {
            handshakeConnection.close()

            // because we are getting the class registration details from the SERVER, this should never be the case.
            // It is still and edge case where the reconstruction of the registration details fails (maybe because of custom serializers)
            val exception = ClientRejectedException("Connection to $remoteAddress has incorrect class registration details!!")
            listenerManager.notifyError(exception)
            throw exception
        }


        val newConnection = if (this.remoteAddress0 == null) {
            newConnection(ConnectionParams(this, reliableClientConnection, PublicKeyValidationState.VALID))
        } else {
            newConnection(ConnectionParams(this, reliableClientConnection, validateRemoteAddress))
        }

        // VALIDATE are we allowed to connect to this server (now that we have the initial server information)
        @Suppress("UNCHECKED_CAST")
        val permitConnection = listenerManager.notifyFilter(newConnection)
        if (!permitConnection) {
            handshakeConnection.close()
            val exception = ClientRejectedException("Connection to $remoteAddress was not permitted!")
            listenerManager.notifyError(exception)
            throw exception
        }

        //////////////
        ///  Extra Close action
        //////////////
        newConnection.preCloseAction = {
            // this is called whenever connection.close() is called by the framework or via client.close()
            if (!lockStepForReconnect.compareAndSet(null, SuspendWaiter())) {
                listenerManager.notifyError(connection, IllegalStateException("lockStep for reconnect was in the wrong state!"))
            }
        }
        newConnection.postCloseAction = {
            // this is called whenever connection.close() is called by the framework or via client.close()

            // make sure to call our client.notifyDisconnect() callbacks

            // manually call it.
            // this always has to be on a new dispatch, otherwise we can have weird logic loops if we reconnect within a disconnect callback
            actionDispatch.launch {
                listenerManager.notifyDisconnect(connection)
            }

            // in case notifyDisconnect called client.connect().... cancel them waiting
            isConnected = false
            lockStepForReconnect.value?.cancel()
        }

        connection0 = newConnection
        connections.add(newConnection)

        // tell the server our connection handshake is done, and the connection can now listen for data.
        val canFinishConnecting = handshake.handshakeDone(handshakeConnection, connectionTimeoutMS)

        if (canFinishConnecting) {
            isConnected = true

            // have to make a new thread to listen for incoming data!
            // SUBSCRIPTIONS ARE NOT THREAD SAFE! Only one thread at a time can poll them
            actionDispatch.launch {
                listenerManager.notifyConnect(newConnection)
            }

            // these have to be in two SEPARATE actionDispatch.launch commands.... otherwise...
            // if something inside of notifyConnect is blocking or suspends, then polling will never happen!
            actionDispatch.launch {
                val pollIdleStrategy = config.pollIdleStrategy

                while (!isShutdown()) {
                    // If the connection has either been closed, or has expired, it needs to be cleaned-up/deleted.
                    var shouldCleanupConnection = false

                    if (newConnection.isExpired()) {
                        logger.debug {"[${newConnection.id}] connection expired"}
                        shouldCleanupConnection = true
                    }

                    else if (newConnection.isClosed()) {
                        logger.debug {"[${newConnection.id}] connection closed"}
                        shouldCleanupConnection = true
                    }


                    if (shouldCleanupConnection) {
                        close()
                        return@launch
                    }
                    else {
                        //  Polls the AERON media driver subscription channel for incoming messages
                        val pollCount = newConnection.pollSubscriptions()

                        // 0 means we idle. >0 means reset and don't idle (because there are likely more poll events)
                        pollIdleStrategy.idle(pollCount)
                    }
                }
            }
        } else {
            close()
            val exception = ClientRejectedException("Unable to connect with server ${handshakeConnection.clientInfo()}")
            ListenerManager.cleanStackTrace(exception)
            listenerManager.notifyError(exception)
            throw exception
        }
    }

    /**
     * true if the remote public key changed. This can be useful if specific actions are necessary when the key has changed.
     */
    val remoteKeyHasChanged: Boolean
        get() = connection.hasRemoteKeyChanged()

    /**
     * the remote address
     */
    val remoteAddress: InetAddress?
        get() = remoteAddress0

    /**
     * the remote address, as a string.
     */
    val remoteAddressString: String
        get() = remoteAddress0?.hostAddress ?: "ipc"

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
     * @throws ClientException when a message cannot be sent
     */
    suspend fun send(message: Any) {
        val c = connection0
        if (c != null) {
            c.send(message)
        } else {
            throw ClientException("Cannot send a message when there is no connection!")
        }
    }

    /**
     * @throws ClientException when a ping cannot be sent
     */
//    suspend fun ping(): Ping {
//        val c = connection
//        if (c != null) {
//            return c.ping()
//        } else {
//            throw ClientException("Cannot ping a connection when there is no connection!")
//        }
//    }

    /**
     * Removes the specified host address from the list of registered server keys.
     */
    fun removeRegisteredServerKey(address: InetAddress) {
        val savedPublicKey = settingsStore.getRegisteredServerKey(address)
        if (savedPublicKey != null) {
            logger.debug { "Deleting remote IP address key $address" }
            settingsStore.removeRegisteredServerKey(address)
        }
    }

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
    // RMI - connection
    //
    //

    /**
     * Tells us to save an an already created object in the CONNECTION scope, so a remote connection can get it via [Connection.getObject]
     *
     * - This object is NOT THREAD SAFE, and is meant to ONLY be used from a single thread!
     *
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
    fun saveObject(`object`: Any): Int {
        val rmiId = rmiConnectionSupport.saveImplObject(`object`)
        if (rmiId == RemoteObjectStorage.INVALID_RMI) {
            val exception = Exception("RMI implementation '${`object`::class.java}' could not be saved! No more RMI id's could be generated")
            ListenerManager.cleanStackTrace(exception)
            listenerManager.notifyError(exception)
            return rmiId
        }

        return rmiId
    }

    /**
     * Tells us to save an an already created object in the CONNECTION scope using the specified ID, so a remote connection can get it via [Connection.getObject]
     *
     *
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
    fun saveObject(`object`: Any, objectId: Int): Boolean {
        val success = rmiConnectionSupport.saveImplObject(`object`, objectId)
        if (!success) {
            val exception = Exception("RMI implementation '${`object`::class.java}' could not be saved! No more RMI id's could be generated")
            ListenerManager.cleanStackTrace(exception)
            listenerManager.notifyError(exception)
        }
        return success
    }

    /**
     * Get a CONNECTION scope REMOTE object via the ID.
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
    inline fun <reified Iface> getObject(objectId: Int): Iface {
        // NOTE: It's not possible to have reified inside a virtual function
        // https://stackoverflow.com/questions/60037849/kotlin-reified-generic-in-virtual-function
        val kryoId = serialization.getKryoIdForRmiClient(Iface::class.java)

        @Suppress("NON_PUBLIC_CALL_FROM_PUBLIC_INLINE")
        return rmiConnectionSupport.getProxyObject(connection, kryoId, objectId, Iface::class.java)
    }

    /**
     * Tells the remote connection to create a new proxy object that implements the specified interface in the CONNECTION scope.
     *
     * The methods on this object "map" to an object that is created remotely.
     *
     * The callback will be notified when the remote object has been created.
     *
     * Methods that return a value will throw [TimeoutException] if the response is not received with the
     * response timeout [RemoteObject.responseTimeout].
     *
     * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side
     * will have the proxy object replaced with the registered (non-proxy) object.
     *
     * If one wishes to change the default behavior, cast the object to access the different methods.
     * ie:  `val remoteObject = test as RemoteObject`
     *
     * @see RemoteObject
     */
    suspend inline fun <reified Iface> createObject(vararg objectParameters: Any?, noinline callback: suspend (Int, Iface) -> Unit) {
        // NOTE: It's not possible to have reified inside a virtual function
        // https://stackoverflow.com/questions/60037849/kotlin-reified-generic-in-virtual-function
        val kryoId = serialization.getKryoIdForRmiClient(Iface::class.java)

        @Suppress("UNCHECKED_CAST")
        objectParameters as Array<Any?>

        @Suppress("NON_PUBLIC_CALL_FROM_PUBLIC_INLINE")
        rmiConnectionSupport.createRemoteObject(connection, kryoId, objectParameters, callback)
    }

    /**
     * Tells the remote connection to create a new proxy object that implements the specified interface in the CONNECTION scope.
     *
     * The methods on this object "map" to an object that is created remotely.
     *
     * The callback will be notified when the remote object has been created.
     *
     * Methods that return a value will throw [TimeoutException] if the response is not received with the
     * response timeout [RemoteObject.responseTimeout].
     *
     * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side
     * will have the proxy object replaced with the registered (non-proxy) object.
     *
     * If one wishes to change the default behavior, cast the object to access the different methods.
     * ie:  `val remoteObject = test as RemoteObject`
     *
     * @see RemoteObject
     */
    suspend inline fun <reified Iface> createObject(noinline callback: suspend (Int, Iface) -> Unit) {
        // NOTE: It's not possible to have reified inside a virtual function
        // https://stackoverflow.com/questions/60037849/kotlin-reified-generic-in-virtual-function
        val kryoId = serialization.getKryoIdForRmiClient(Iface::class.java)

        @Suppress("NON_PUBLIC_CALL_FROM_PUBLIC_INLINE")
        rmiConnectionSupport.createRemoteObject(connection, kryoId, null, callback)
    }

    //
    //
    // RMI - global
    //
    //

    /**
     * Tells us to save an an already created object in the GLOBAL scope, so a remote connection can get it via [Connection.getGlobalObject]
     *
     *
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
        }
        return rmiId
    }

    /**
     * Tells us to save an an already created object in the GLOBAL scope using the specified ID, so a remote connection can get it via [Connection.getGlobalObject]
     *
     *
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

    /**
     * Get a GLOBAL scope remote object via the ID.
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
    inline fun <reified Iface> getGlobalObject(objectId: Int): Iface {
        // NOTE: It's not possible to have reified inside a virtual function
        // https://stackoverflow.com/questions/60037849/kotlin-reified-generic-in-virtual-function
        @Suppress("NON_PUBLIC_CALL_FROM_PUBLIC_INLINE")
        return rmiGlobalSupport.getGlobalRemoteObject(connection, objectId, Iface::class.java)
    }
}
