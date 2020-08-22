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

import dorkbox.netUtil.IPv4
import dorkbox.network.Server
import dorkbox.network.connection.ping.PingFuture
import dorkbox.network.connection.ping.PingMessage
import dorkbox.network.rmi.RemoteObject
import dorkbox.network.rmi.RemoteObjectStorage
import dorkbox.network.rmi.TimeoutException
import dorkbox.network.serialization.KryoExtra
import dorkbox.util.classes.ClassHelper
import io.aeron.FragmentAssembler
import io.aeron.Publication
import io.aeron.Subscription
import io.aeron.logbuffer.FragmentHandler
import io.aeron.logbuffer.Header
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.agrona.BitUtil
import org.agrona.BufferUtil
import org.agrona.DirectBuffer
import org.agrona.concurrent.UnsafeBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.SecretKey

/**
 * This connection is established once the registration information is validated, and the various connect/filter checks have passed
 */
open class Connection(connectionParameters: ConnectionParams<*>) {
    private val subscription: Subscription
    private val publication: Publication

    /**
     * The publication port (used by aeron) for this connection. This is from the perspective of the server!
     */
    internal val subscriptionPort: Int
    internal val publicationPort: Int

    /**
     * the stream id of this connection.
     */
    internal val streamId: Int

    /**
     * the session id of this connection. This value is UNIQUE
     */
    internal val sessionId: Int

    /**
     * the id of this connection. This value is UNIQUE
     */
    val id: Int
        get() = sessionId

    /**
     * the remote address, as a string.
     */
    val remoteAddress: String

    /**
     * the remote address, as an integer.
     */
    val remoteAddressInt: Int






    /**
     * @return true if this connection is established on the loopback interface
     */
    val isLoopback: Boolean
        get() = TODO("Not yet implemented")

    /**
     * @return true if this connection is an IPC connection
     */
    val isIPC: Boolean
        get() = TODO("Not yet implemented")

    /**
     * @return true if this connection is a network connection
     */
    val isNetwork: Boolean
        get() = TODO("Not yet implemented")






    /**
     * Returns the last calculated TCP return trip time, or -1 if or the [PingMessage] response has not yet been received.
     */
    val lastRoundTripTime: Int
        get() {
            val pingFuture2 = pingFuture
            return pingFuture2?.response ?: -1
        }

    private val endPoint = connectionParameters.endPoint
    private val listenerManager = atomic<ListenerManager<Connection>?>(null)

    private val serialization = endPoint.config.serialization
    private val sendIdleStrategy = endPoint.config.sendIdleStrategy.clone()

    private val expirationTime = System.currentTimeMillis() +
                                 TimeUnit.SECONDS.toMillis(endPoint.config.connectionCleanupTimeoutInSeconds.toLong())

    val logger = endPoint.logger


    //    private val needsLock = AtomicBoolean(false)
//    private val writeSignalNeeded = AtomicBoolean(false)
//    private val writeLock = Any()
//    private val closeInProgress = AtomicBoolean(false)
    private val isClosed = atomic(false)
//    private val channelIsClosed = AtomicBoolean(false)
//    private val messageInProgressLock = Any()
//    private val messageInProgress = AtomicBoolean(false)


    @Volatile
    private var pingFuture: PingFuture? = null

    // used to store connection local listeners (instead of global listeners). Only possible on the server.
//    @Volatile
//    private var localListenerManager: ConnectionManager<*>? = null

    // while on the CLIENT, if the SERVER's ecc key has changed, the client will abort and show an error.
    private var remoteKeyChanged = connectionParameters.publicKeyValidation == PublicKeyValidationState.TAMPERED

    // The IV for AES-GCM must be 12 bytes, since it's 4 (salt) + 8 (external counter) + 4 (GCM counter)
    // The 12 bytes IV is created during connection registration, and during the AES-GCM crypto, we override the last 8 with this
    // counter, which is also transmitted as an optimized int. (which is why it starts at 0, so the transmitted bytes are small)
    private val aes_gcm_iv = AtomicLong(0)

    // when closing this connection, HOW MANY endpoints need to be closed?
    private var closeLatch: CountDownLatch? = null

    // RMI support for this connection
    internal val rmiConnectionSupport = endPoint.getRmiConnectionSupport()


    var messageHandler: FragmentAssembler

    val buffer = UnsafeBuffer(BufferUtil.allocateDirectAligned(10000, BitUtil.CACHE_LINE_LENGTH))


    init {
        val mediaDriverConnection = connectionParameters.mediaDriverConnection

        // we have to construct how the connection will communicate!
        if (endPoint is Server<*>) {
            mediaDriverConnection.buildServer(endPoint.aeron)
        } else {
            runBlocking {
                mediaDriverConnection.buildClient(endPoint.aeron)
            }
        }

        logger.trace {
            "Creating new connection $mediaDriverConnection"
        }

        // can only get this AFTER we have built the sub/pub
        subscription = mediaDriverConnection.subscription
        publication = mediaDriverConnection.publication

        subscriptionPort = mediaDriverConnection.subscriptionPort
        publicationPort = mediaDriverConnection.publicationPort
        remoteAddress = mediaDriverConnection.address
        remoteAddressInt = IPv4.toInt(remoteAddress)
        streamId = mediaDriverConnection.streamId // NOTE: this is UNIQUE per server!
        sessionId = mediaDriverConnection.sessionId // NOTE: this is UNIQUE per server!

        messageHandler = FragmentAssembler(FragmentHandler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
            // small problem... If we expect IN ORDER messages (ie: setting a value, then later reading the value), multiple threads
            // don't work.
            endPoint.actionDispatch.launch {
                endPoint.readMessage(buffer, offset, length, header, this@Connection)
            }
        })

        // when closing this connection, HOW MANY endpoints need to be closed?
        closeLatch = CountDownLatch(1)
    }



    /**
     * Has the remote ECC public key changed. This can be useful if specific actions are necessary when the key has changed.
     */
    fun hasRemoteKeyChanged(): Boolean {
        return remoteKeyChanged
    }



    /**
     * @return the endpoint associated with this connection
     */
    internal fun endPoint(): EndPoint<*> {
        return endPoint
    }

    /**
     * This is the per-message sequence number.
     *
     * The IV for AES-GCM must be 12 bytes, since it's 4 (salt) + 4 (external counter) + 4 (GCM counter)
     * The 12 bytes IV is created during connection registration, and during the AES-GCM crypto, we override the last 8 with this
     * counter, which is also transmitted as an optimized int. (which is why it starts at 0, so the transmitted bytes are small)
     */
    fun nextGcmSequence(): Long {
        return aes_gcm_iv.getAndIncrement()
    }

    /**
     * @return the AES key. key=32 byte, iv=12 bytes (AES-GCM implementation).
     */
    fun cryptoKey(): SecretKey {
        TODO()
//        return channelWrapper.cryptoKey()
    }




    /**
     * Polls the AERON media driver subscription channel for incoming messages
     */
    fun pollSubscriptions(): Int {
        return subscription.poll(messageHandler, 1024)
    }

    /**
     * Safely sends objects to a destination.
     */
    suspend fun send(message: Any) {
        // The sessionId is globally unique, and is assigned by the server.
        logger.trace {
            "[${publication.sessionId()}] send: $message"
        }

        val kryo: KryoExtra = serialization.takeKryo()
        try {
            kryo.write(this, message)

            val buffer = kryo.writerBuffer
            val objectSize = buffer.position()
            val internalBuffer = buffer.internalBuffer

            var result: Long
            while (true) {
                result = publication.offer(internalBuffer, 0, objectSize)
                // success!
                if (result > 0) {
                    return
                }

                if (result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION) {
                    // we should retry.
                    sendIdleStrategy.idle()
                    continue
                }

                // more critical error sending the message. we shouldn't retry or anything.
                logger.error("Error sending message. ${EndPoint.errorCodeName(result)}")

                return
            }
        } catch (e: Exception) {
            logger.error("Error serializing message $message", e)
        } finally {
            sendIdleStrategy.reset()
            serialization.returnKryo(kryo)
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
     * @param now The current time
     *
     * @return `true` if this connection has no subscribers and the current time `now` is after the expriation date
     */
    fun isExpired(now: Long): Boolean {
        return subscription.imageCount() == 0 && now > expirationTime
    }


    /**
     * @return `true` if this connection has been closed
     */
    fun isClosed(): Boolean {
        return isClosed.value
    }


    /**
     * Closes the connection, and removes all connection specific listeners
     */
    internal suspend fun close() {
        if (isClosed.compareAndSet(expect = false, update = true)) {
            subscription.close()
            publication.close()

            // a connection might have also registered for disconnect events
            notifyDisconnect()
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
     * Invoked when a connection is disconnected from the remote endpoint
     */
    internal suspend fun notifyDisconnect() {
        listenerManager.value?.notifyDisconnect(this)
    }

    /**
     * Invoked when a message object was received from a remote peer.
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
        return "$remoteAddress $publicationPort/$subscriptionPort ID: $sessionId"
    }

    override fun hashCode(): Int {
        return sessionId
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
        return sessionId == other1.sessionId
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
    fun saveObject(`object`: Any): Int {
        val rmiId = rmiConnectionSupport.saveImplObject(`object`)
        if (rmiId == RemoteObjectStorage.INVALID_RMI) {
            logger.error("Invalid RMI ID for saving object: $`object`")
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
    fun saveObject(`object`: Any, objectId: Int): Boolean {
        val success = rmiConnectionSupport.saveImplObject(`object`, objectId)
        if (!success) {
            logger.error("Unable to save object $`object` with RMI ID $objectId")
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
        return rmiConnectionSupport.getRemoteObject(this, endPoint, objectId, Iface::class.java)
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
        return rmiConnectionSupport.rmiGlobalSupport.getGlobalRemoteObject(this, endPoint, objectId, Iface::class.java)
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
        val interfaceClassId = endPoint.serialization.getClassId(iFaceClass)

        @Suppress("UNCHECKED_CAST")
        objectParameters as Array<Any?>

        rmiConnectionSupport.createRemoteObject(this, interfaceClassId, objectParameters, callback)
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
        val interfaceClassId = endPoint.serialization.getClassId(iFaceClass)

        rmiConnectionSupport.createRemoteObject(this, interfaceClassId, null, callback)
    }
}
