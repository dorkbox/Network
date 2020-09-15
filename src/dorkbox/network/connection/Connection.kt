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
package dorkbox.network.connection

import dorkbox.network.aeron.IpcMediaDriverConnection
import dorkbox.network.aeron.UdpMediaDriverConnection
import dorkbox.network.handshake.ConnectionCounts
import dorkbox.network.handshake.RandomIdAllocator
import dorkbox.network.ping.Ping
import dorkbox.network.ping.PingFuture
import dorkbox.network.ping.PingMessage
import dorkbox.network.rmi.RemoteObject
import dorkbox.network.rmi.RemoteObjectStorage
import dorkbox.network.rmi.TimeoutException
import dorkbox.util.classes.ClassHelper
import io.aeron.FragmentAssembler
import io.aeron.Publication
import io.aeron.Subscription
import io.aeron.logbuffer.Header
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.agrona.DirectBuffer
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * This connection is established once the registration information is validated, and the various connect/filter checks have passed
 */
open class Connection(connectionParameters: ConnectionParams<*>) {
    private var messageHandler: FragmentAssembler
    private val subscription: Subscription
    private val publication: Publication

    /**
     * The publication port (used by aeron) for this connection. This is from the perspective of the server!
     */
    private val subscriptionPort: Int
    private val publicationPort: Int

    /**
     * the stream id of this connection. Can be 0 for IPC connections
     */
    private val streamId: Int

    /**
     * the session id of this connection. This value is UNIQUE
     */
    val id: Int

    /**
     * the remote address, as a string. Will be null for IPC connections
     */
    val remoteAddress: InetAddress?

    /**
     * the remote address, as a string. Will be "ipc" for IPC connections
     */
    val remoteAddressString: String

    /**
     * @return true if this connection is an IPC connection
     */
    val isIpc = connectionParameters.mediaDriverConnection is IpcMediaDriverConnection

    /**
     * @return true if this connection is a network connection
     */
    val isNetwork = connectionParameters.mediaDriverConnection is UdpMediaDriverConnection

    /**
     * the endpoint associated with this connection
     */
    internal val endPoint = connectionParameters.endPoint


    private val listenerManager = atomic<ListenerManager<Connection>?>(null)
    val logger = endPoint.logger


    internal var preCloseAction: suspend () -> Unit = {}
    internal var postCloseAction: suspend () -> Unit = {}

    // only accessed on a single thread!
    private val connectionInitTime = System.nanoTime()

    private val isClosed = atomic(false)

    /**
    //     * Returns the last calculated TCP return trip time, or -1 if or the [PingMessage] response has not yet been received.
    //     */
//    val lastRoundTripTime: Int
//        get() {
//            val pingFuture2 = pingFuture
//            return pingFuture2?.response ?: -1
//        }

    @Volatile
    private var pingFuture: PingFuture? = null

    // while on the CLIENT, if the SERVER's ecc key has changed, the client will abort and show an error.
    private val remoteKeyChanged = connectionParameters.publicKeyValidation == PublicKeyValidationState.TAMPERED

    // The IV for AES-GCM must be 12 bytes, since it's 4 (salt) + 8 (external counter) + 4 (GCM counter)
    // The 12 bytes IV is created during connection registration, and during the AES-GCM crypto, we override the last 8 with this
    // counter, which is also transmitted as an optimized int. (which is why it starts at 0, so the transmitted bytes are small)
//    private val aes_gcm_iv = atomic(0)

    // RMI support for this connection
    internal val rmiConnectionSupport = endPoint.getRmiConnectionSupport()

    // a record of how many messages are in progress of being sent. When closing the connection, this number must be 0
    private val messagesInProgress = atomic(0)

    // we customize the toString() value for this connection, and it's just better to cache it's value (since it's a modestly complex string)
    private val toString0: String

    init {
        val mediaDriverConnection = connectionParameters.mediaDriverConnection

        // can only get this AFTER we have built the sub/pub
        subscription = mediaDriverConnection.subscription
        publication = mediaDriverConnection.publication

        remoteAddress = mediaDriverConnection.address // this can be the IP address or "ipc" word
        id = mediaDriverConnection.sessionId // NOTE: this is UNIQUE per server!

        if (mediaDriverConnection is IpcMediaDriverConnection) {
            streamId = 0 // this is because with IPC, we have stream sub/pub (which are replaced as port sub/pub)
            subscriptionPort = mediaDriverConnection.streamIdSubscription
            publicationPort = mediaDriverConnection.streamId
            remoteAddressString = "ipc"

            toString0 = "[$id] IPC [$subscriptionPort|$publicationPort]"
        } else {
            mediaDriverConnection as UdpMediaDriverConnection

            streamId = mediaDriverConnection.streamId // NOTE: this is UNIQUE per server!
            subscriptionPort = mediaDriverConnection.subscriptionPort
            publicationPort = mediaDriverConnection.publicationPort

            remoteAddressString = mediaDriverConnection.addressString

            toString0 = "[$id] $remoteAddressString [$publicationPort|$subscriptionPort]"
        }


        messageHandler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
            // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!

            // NOTE: subscriptions (ie: reading from buffers, etc) are not thread safe!  Because it is ambiguous HOW EXACTLY they are unsafe,
            //  we exclusively read from the DirectBuffer on a single thread.

            endPoint.processMessage(buffer, offset, length, header, this@Connection)
        }
    }



    /**
     * @return true if the remote public key changed. This can be useful if specific actions are necessary when the key has changed.
     */
    fun hasRemoteKeyChanged(): Boolean {
        return remoteKeyChanged
    }

//    /**
//     * This is the per-message sequence number.
//     *
//     * The IV for AES-GCM must be 12 bytes, since it's 4 (salt) + 4 (external counter) + 4 (GCM counter)
//     * The 12 bytes IV is created during connection registration, and during the AES-GCM crypto, we override the last 8 with this
//     * counter, which is also transmitted as an optimized int. (which is why it starts at 0, so the transmitted bytes are small)
//     */
//    fun nextGcmSequence(): Long {
//        return aes_gcm_iv.getAndIncrement()
//    }
//
//    /**
//     * @return the AES key. key=32 byte, iv=12 bytes (AES-GCM implementation).
//     */
//    fun cryptoKey(): SecretKey {
//        TODO()
////        return channelWrapper.cryptoKey()
//    }




    /**
     * Polls the AERON media driver subscription channel for incoming messages
     */
    internal fun pollSubscriptions(): Int {
        // NOTE: regarding fragment limit size. Repeated calls to '.poll' will reassemble a fragment.
        //   `.poll(handler, 4)` == `.poll(handler, 2)` + `.poll(handler, 2)`
        return subscription.poll(messageHandler, 1)
    }

    /**
     * Safely sends objects to a destination.
     */
    suspend fun send(message: Any) {
        messagesInProgress.getAndIncrement()
        endPoint.send(message, publication, this)
        messagesInProgress.getAndDecrement()
    }

    /**
     * Safely sends objects to a destination.
     */
    fun sendBlocking(message: Any) {
        runBlocking {
            send(message)
        }
    }

    /**
     * Updates the ping times for this connection (called when this connection gets a REPLY ping message).
     */
    fun updatePingResponse(ping: PingMessage?) {
        if (pingFuture != null) {
            pingFuture!!.setSuccess(this, ping)
        }
    }

    /**
     * Sends a "ping" packet, trying UDP then TCP (in that order) to measure **ROUND TRIP** time to the remote connection.
     *
     * @return Ping can have a listener attached, which will get called when the ping returns.
     */
    suspend fun ping(): Ping {
        // TODO: USE AERON FOR THIS
//        val pingFuture2 = pingFuture
//        if (pingFuture2 != null && !pingFuture2.isSuccess) {
//            pingFuture2.cancel()
//        }
//        val newPromise: Promise<PingTuple<out Connection?>>
//        newPromise = if (channelWrapper.udp() != null) {
//            channelWrapper.udp()
//                    .newPromise()
//        } else {
//            channelWrapper.tcp()
//                    .newPromise()
//        }
//        pingFuture = PingFuture(newPromise)
//        val ping = PingMessage()
//        ping.id = pingFuture!!.id
//        ping0(ping)
//        return pingFuture!!
        TODO()
    }

    /**
     * INTERNAL USE ONLY. Used to initiate a ping, and to return a ping.
     *
     * Sends a ping message attempted in the following order: UDP, TCP,LOCAL
     */
    fun ping0(ping: PingMessage) {
//        if (channelWrapper.udp() != null) {
//            UDP(ping)
//        } else if (channelWrapper.tcp() != null) {
//            TCP(ping)
//        } else {
//            self(ping)
//        }
        TODO()
    }


    /**
     * A message in progress means that we have requested to to send an object over the network, but it hasn't finished sending over the network
     *
     * @return the number of messages in progress for this connection.
     */
    fun messagesInProgress(): Int {
        return messagesInProgress.value
    }

    /**
     * @return `true` if this connection has been closed
     */
    fun isClosed(): Boolean {
        val hasNoImages = subscription.hasNoImages()
        if (hasNoImages) {
            // 1) connections take a little bit of time from polling -> connecting (because of how we poll connections before 'connecting' them).
            return System.nanoTime() - connectionInitTime >= TimeUnit.SECONDS.toNanos(1)
        }

        return hasNoImages
    }

    /**
     * Closes the connection, and removes all connection specific listeners
     */
    suspend fun close() {
        // there are 2 ways to call close.
        //   MANUALLY
        //   when a connection is disconnected via a timeout/expire.
        // the compareAndSet is used to make sure that if we call close() MANUALLY, when the auto-cleanup/disconnect is called -- it doesn't
        // try to do it again.

        // the server 'handshake' connection info is cleaned up with the disconnect via timeout/expire.
        if (isClosed.compareAndSet(expect = false, update = true)) {
            logger.info {"[$id] closed connection"}

            subscription.close()

            val timeOut = TimeUnit.SECONDS.toMillis(endPoint.config.connectionCloseTimeoutInSeconds.toLong())
            var closeTimeoutTime = System.currentTimeMillis() + timeOut

            // we do not want to close until AFTER all publications have been sent. Calling this WITHOUT waiting will instantly stop everything
            // we want a timeout-check, otherwise this will run forever
            while (messagesInProgress.value != 0 && System.currentTimeMillis() < closeTimeoutTime) {
                delay(100)
            }

            // on close, we want to make sure this file is DELETED!
            val logFile = endPoint.getMediaDriverPublicationFile(publication.registrationId())
            publication.close()

            closeTimeoutTime = System.currentTimeMillis() + timeOut
            while (logFile.exists() && System.currentTimeMillis() < closeTimeoutTime) {
                if (logFile.delete()) {
                    break
                }
                delay(100)
            }

            if (logFile.exists()) {
                listenerManager.value?.notifyError(this, IOException("Unable to delete aeron publication log on close: $logFile"))
            }

            rmiConnectionSupport.clearProxyObjects()

            endPoint.removeConnection(this)


            // This is set by the client so if there is a "connect()" call in the the disconnect callback, we can have proper
            // lock-stop ordering for how disconnect and connect work with each-other
            preCloseAction()

            // this always has to be on a new dispatch, otherwise we can have weird logic loops if we reconnect within a disconnect callback
            endPoint.actionDispatch.launch(start = CoroutineStart.UNDISPATCHED) {
                // NOTE: UNDISPATCHED means that this coroutine will start as an event loop, instead of concurrently
                //   we want this behavior INSTEAD OF automatically starting this on a new thread.
                // a connection might have also registered for disconnect events
                listenerManager.value?.notifyDisconnect(this@Connection)
            }

            // This is set by the client so if there is a "connect()" call in the the disconnect callback, we can have proper
            // lock-stop ordering for how disconnect and connect work with each-other
            postCloseAction()
        }
    }

    /**
     * Adds a function that will be called when a client/server "disconnects" with
     * each other
     *
     * For a server, this function will be called for ALL clients.
     *
     * It is POSSIBLE to add a server CONNECTION only (ie, not global) listener
     * (via connection.addListener), meaning that ONLY that listener attached to
     * the connection is notified on that event (ie, admin type listeners)
     */
    suspend fun onDisconnect(function: suspend (Connection) -> Unit) {
        // make sure we atomically create the listener manager, if necessary
        listenerManager.getAndUpdate { origManager ->
            origManager ?: ListenerManager()
        }

        listenerManager.value!!.onDisconnect(function)
    }

    /**
     * Adds a function that will be called only for this connection, when a client/server receives a message
     */
    suspend fun <MESSAGE> onMessage(function: suspend (Connection, MESSAGE) -> Unit) {
        // make sure we atomically create the listener manager, if necessary
        listenerManager.getAndUpdate { origManager ->
            origManager ?: ListenerManager()
        }

        listenerManager.value!!.onMessage(function)
    }

    /**
     * Invoked when a message object was received from a remote peer.
     *
     * This is ALWAYS called on a new dispatch
     */
    internal suspend fun notifyOnMessage(message: Any): Boolean {
        return listenerManager.value?.notifyOnMessage(this, message) ?: false
    }


    //
    //
    // Generic object methods
    //
    //
    override fun toString(): String {
        return toString0
    }

    override fun hashCode(): Int {
        return id
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other == null) {
            return false
        }
        if (javaClass != other.javaClass) {
            return false
        }

        val other1 = other as Connection
        return id == other1.id
    }

    // cleans up the connection information
    internal fun cleanup(connectionsPerIpCounts: ConnectionCounts, sessionIdAllocator: RandomIdAllocator, streamIdAllocator: RandomIdAllocator) {
        // note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
        if (isIpc) {
            sessionIdAllocator.free(subscriptionPort)
            sessionIdAllocator.free(publicationPort)
            streamIdAllocator.free(streamId)
        } else {
            connectionsPerIpCounts.decrementSlow(remoteAddress!!)
            sessionIdAllocator.free(id)
            streamIdAllocator.free(streamId)
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
    // RMI methods
    //
    //

    /**
     * Tells us to save an an already created object in the CONNECTION scope, so a remote connection can get it via [Connection.getObject]
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
    fun saveObject(`object`: Any): Int {
        val rmiId = rmiConnectionSupport.saveImplObject(`object`)
        if (rmiId == RemoteObjectStorage.INVALID_RMI) {
            val exception = Exception("RMI implementation '${`object`::class.java}' could not be saved! No more RMI id's could be generated")
            ListenerManager.cleanStackTrace(exception)
            listenerManager.value?.notifyError(this, exception)
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
            listenerManager.value?.notifyError(this, exception)
        }
        return success
    }

    /**
     * Gets a CONNECTION scope remote object via the ID.
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
        @Suppress("NON_PUBLIC_CALL_FROM_PUBLIC_INLINE")
        val kryoId = endPoint.serialization.getKryoIdForRmiClient(Iface::class.java)

        @Suppress("NON_PUBLIC_CALL_FROM_PUBLIC_INLINE")
        return rmiConnectionSupport.getProxyObject(this, kryoId, objectId, Iface::class.java)
    }

    /**
     * Gets a global REMOTE object via the ID.
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
        return rmiConnectionSupport.rmiGlobalSupport.getGlobalRemoteObject(this, objectId, Iface::class.java)
    }

    /**
     * Tells the remote connection to create a new proxy object that implements the specified interface. The methods on this object "map"
     * to an object that is created remotely.
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
    suspend fun <Iface> createObject(vararg objectParameters: Any?, callback: suspend (Int, Iface) -> Unit) {
        val iFaceClass = ClassHelper.getGenericParameterAsClassForSuperClass(Function2::class.java, callback.javaClass, 1)
        val kryoId = endPoint.serialization.getKryoIdForRmiClient(iFaceClass)

        @Suppress("UNCHECKED_CAST")
        objectParameters as Array<Any?>

        rmiConnectionSupport.createRemoteObject(this, kryoId, objectParameters, callback)
    }

    /**
     * Tells the remote connection to create a new proxy object that implements the specified interface. The methods on this object "map"
     * to an object that is created remotely.
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
    suspend fun <Iface> createObject(callback: suspend (Int, Iface) -> Unit) {
        val iFaceClass = ClassHelper.getGenericParameterAsClassForSuperClass(Function2::class.java, callback.javaClass, 1)
        val kryoId = endPoint.serialization.getKryoIdForRmiClient(iFaceClass)

        rmiConnectionSupport.createRemoteObject(this, kryoId, null, callback)
    }
}
