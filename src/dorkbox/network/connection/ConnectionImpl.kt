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
package dorkbox.network.connection

import dorkbox.network.Server
import dorkbox.network.connection.ping.PingFuture
import dorkbox.network.connection.ping.PingMessage
import dorkbox.network.other.NetworkUtil
import dorkbox.network.rmi.RmiSupportConnection
import dorkbox.util.classes.ClassHelper
import io.aeron.FragmentAssembler
import io.aeron.Publication
import io.aeron.Subscription
import io.aeron.logbuffer.FragmentHandler
import io.aeron.logbuffer.Header
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.agrona.BitUtil
import org.agrona.BufferUtil
import org.agrona.DirectBuffer
import org.agrona.concurrent.UnsafeBuffer
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.SecretKey

/**
 * The "network connection" is established once the registration is validated for TCP/UDP
 */
open class ConnectionImpl(val endPoint: EndPoint<*>, mediaDriverConnection: MediaDriverConnection)
    : Connection_, Listeners<Connection> {

    private val subscription: Subscription
    private val publication: Publication

    final override val subscriptionPort: Int
    final override val publicationPort: Int
    final override val remoteAddressInt: Int
    final override val remoteAddress: String
    final override val streamId: Int
    final override val sessionId: Int


    private val serialization = endPoint.config.serialization
    private val sendIdleStrategy = endPoint.config.sendIdleStrategy.clone()

    private val expirationTime = System.currentTimeMillis() +
                                 TimeUnit.SECONDS.toMillis(endPoint.config.connectionCleanupTimeoutInSeconds.toLong())

    private val logger = endPoint.logger

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
    private var remoteKeyChanged = false

    // The IV for AES-GCM must be 12 bytes, since it's 4 (salt) + 8 (external counter) + 4 (GCM counter)
    // The 12 bytes IV is created during connection registration, and during the AES-GCM crypto, we override the last 8 with this
    // counter, which is also transmitted as an optimized int. (which is why it starts at 0, so the transmitted bytes are small)
    private val aes_gcm_iv = AtomicLong(0)

    // when closing this connection, HOW MANY endpoints need to be closed?
    private var closeLatch: CountDownLatch? = null

    // RMI support for this connection
    private val rmiSupportConnection: RmiSupportConnection<Connection_>


    var messageHandler: FragmentAssembler

    val buffer = UnsafeBuffer(BufferUtil.allocateDirectAligned(10000, BitUtil.CACHE_LINE_LENGTH))


    init {
        // we have to construct how the connection will communicate!
        if (endPoint is Server) {
            mediaDriverConnection.buildServer(endPoint.aeron)
        } else {
            runBlocking {
                mediaDriverConnection.buildClient(endPoint.aeron)
            }
        }

        logger.debug("creating new connection $mediaDriverConnection")

        // can only get this AFTER we have built the sub/pub
        subscription = mediaDriverConnection.subscription
        publication = mediaDriverConnection.publication

        subscriptionPort = mediaDriverConnection.subscriptionPort
        publicationPort = mediaDriverConnection.publicationPort
        remoteAddress = mediaDriverConnection.address
        remoteAddressInt = NetworkUtil.IP.toInt(remoteAddress)
        streamId = mediaDriverConnection.streamId // NOTE: this is UNIQUE per server!
        sessionId = mediaDriverConnection.sessionId

        messageHandler = FragmentAssembler(FragmentHandler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
            // small problem... If we expect IN ORDER messages (ie: setting a value, then later reading the value), multiple threads
            // don't work.
            endPoint.actionDispatch.launch {
                endPoint.readMessage(buffer, offset, length, header, this@ConnectionImpl)
            }
        })

        rmiSupportConnection = RmiSupportConnection(logger, endPoint.rmiSupport, endPoint.serialization, endPoint.actionDispatch)

        // when closing this connection, HOW MANY endpoints need to be closed?
        closeLatch = CountDownLatch(1)
    }

    /**
     * @param now The current time
     *
     * @return `true` if this connection has no subscribers and the current time `now` is after the expriation date
     */
    override fun isExpired(now: Long): Boolean {
        return subscription.imageCount() == 0 && now > expirationTime
    }

    override fun pollSubscriptions(): Int {
        return subscription.poll(messageHandler, 1024)
    }

    /**
     * @return the AES key. key=32 byte, iv=12 bytes (AES-GCM implementation).
     */
    override fun cryptoKey(): SecretKey {
        TODO()
//        return channelWrapper.cryptoKey()
    }

    /**
     * This is the per-message sequence number.
     *
     * The IV for AES-GCM must be 12 bytes, since it's 4 (salt) + 4 (external counter) + 4 (GCM counter)
     * The 12 bytes IV is created during connection registration, and during the AES-GCM crypto, we override the last 8 with this
     * counter, which is also transmitted as an optimized int. (which is why it starts at 0, so the transmitted bytes are small)
     */
    override fun nextGcmSequence(): Long {
        return aes_gcm_iv.getAndIncrement()
    }

    /**
     * Has the remote ECC public key changed. This can be useful if specific actions are necessary when the key has changed.
     */
    override fun hasRemoteKeyChanged(): Boolean {
        return remoteKeyChanged
    }

    override val isLoopback: Boolean
        get() = TODO("Not yet implemented")
    override val isIPC: Boolean
        get() = TODO("Not yet implemented")
    override val isNetwork: Boolean
        get() = TODO("Not yet implemented")







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
    override suspend fun ping(): Ping {
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
     * Returns the last calculated TCP return trip time, or -1 if or the [PingMessage] response has not yet been received.
     */
    val lastRoundTripTime: Int
        get() {
            val pingFuture2 = pingFuture
            return pingFuture2?.response ?: -1
        }

    /**
     * needed to place back-pressure when writing too much data to the connection.
     *
     * This blocks until we are writable again
     */
    // TODO: remove this!?!? use idle backoff strategy?!?
//    fun controlBackPressure(c: ConnectionPoint) {
//        while (!closeInProgress.get() && !c.isWritable) {
//            needsLock.set(true)
//            writeSignalNeeded.set(true)
//            synchronized(writeLock) {
//                if (needsLock.get()) {
//                    try {
//                        // waits 1 second maximum per check. This is to guarantee that eventually (in the case of deadlocks, which i've seen)
//                        // it will get released. The while loop makes sure it will exit when the channel is writable
////                        writeLock.wait(1000)
//                    } catch (e: InterruptedException) {
//                        e.printStackTrace()
//                    }
//                }
//            }
//        }
//    }

    /**
     * Send the given message to the given publication. If the publication fails to accept the message, the method will retry `5` times,
     * waiting `100` milliseconds each time, before throwing an exception.
     *
     * @param pub The publication
     * @param buffer A buffer that will hold the message for sending
     * @param message The message
     *
     * @return The new publication stream position
     *
     * @throws IOException If the message cannot be sent
     */

    /**
     * Safely sends objects to a destination (such as a custom object or a standard ping).
     */
    override suspend fun send(message: Any) {
        // The sessionId is globally unique, and is assigned by the server.
        logger.debug("[{}] send: {}", publication.sessionId(), message)

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


    override suspend fun send(message: Any, priority: Byte) {

    }


    /**
     * Closes the connection, and removes all connection specific listeners
     */
    override fun close() {
        if (isClosed.compareAndSet(expect = false, update = true)) {
            subscription.close()
            publication.close()
        }

        // only close if we aren't already in the middle of closing.
//        if (closeInProgress.compareAndSet(false, true)) {
//            val idleTimeoutMs = 2000
//
//            // if we are in the middle of a message, hold off.
////            synchronized(messageInProgressLock) {
////                // while loop is to prevent spurious wakeups!
////                while (messageInProgress.get()) {
////                    try {
//////                        messageInProgressLock.wait(idleTimeoutMs.toLong())
////                    } catch (ignored: InterruptedException) {
////                    }
////                }
////            }
//
//
//            // close out the ping future
//            val pingFuture2 = pingFuture
//            pingFuture2?.cancel()
//            pingFuture = null
//
////            synchronized(channelIsClosed) {
////                if (!channelIsClosed.get()) {
////                    // this will have netty call "channelInactive()"
//////                    channelWrapper.close(this, sessionManager, false)
////
////                    // want to wait for the "channelInactive()" method to FINISH ALL TYPES before allowing our current thread to continue!
////                    try {
////                        closeLatch!!.await(idleTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
////                    } catch (ignored: InterruptedException) {
////                    }
////                }
////            }
//
//            // remove all listeners AFTER we close the channel.
//            if (!keepListeners) {
//                removeAll()
//            }
//
//
//            // remove all RMI listeners
//            rmiSupport!!.close()
//        }

        // remove all listeners AFTER we close the channel.
//        if (!keepListeners) {
            removeAll()
//        }


        // remove all RMI listeners
//        rmiSupport.close() // TODO
    }


//    @Throws(Exception::class)
//    override fun exceptionCaught(context: ChannelHandlerContext, cause: Throwable) {
//        val channel = context.channel()
//        if (cause !is IOException) {
//            // safe to ignore, since it's thrown when we try to interact with a closed socket. Race conditions cause this, and
//            // it is still safe to ignore.
//            logger!!.error("Unexpected exception while receiving data from {}", channel.remoteAddress(), cause)
//
//            // the ONLY sockets that can call this are:
//            // CLIENT TCP or UDP
//            // SERVER TCP
//            if (channel.isOpen) {
//                channel.close()
//            }
//        } else {
//            // it's an IOException, just log it!
//            logger!!.error("Unexpected exception while communicating with {}!", channel.remoteAddress(), cause)
//        }
//    }

//    /**
//     * Expose methods to modify the connection listeners.
//     */
//    override fun listeners(): Listeners<Connection> {
//        return this
//    }

    /**
     * Adds a listener to this connection/endpoint to be notified of
     * connect/disconnect/idle/receive(object) events.
     *
     *
     * If the listener already exists, it is not added again.
     *
     *
     * When called by a server, NORMALLY listeners are added at the GLOBAL level
     * (meaning, I add one listener, and ALL connections are notified of that
     * listener.
     *
     *
     * It is POSSIBLE to add a server connection ONLY (ie, not global) listener
     * (via connection.addListener), meaning that ONLY that listener attached to
     * the connection is notified on that event (ie, admin type listeners)
     */


    override fun filter(function: (Connection) -> Boolean): Listeners<Connection> {
        return this
    }

    override fun onConnect(function: (Connection) -> Unit): Listeners<Connection> {
        return this
    }

    override fun onDisconnect(function: (Connection) -> Unit): Listeners<Connection> {
        return this
    }

    override fun onError(function: (Connection, throwable: Throwable) -> Unit): Listeners<Connection> {
        return this
    }

    override fun <M : Any> onMessage(function: (Connection, M) -> Unit): Listeners<Connection> {
        return this
    }

//    override fun add(listener: OnConnected<Connection>): Listeners<Connection> {
//        if (endPoint is EndPointServer) {
//            // when we are a server, NORMALLY listeners are added at the GLOBAL level
//            // meaning --
//            //   I add one listener, and ALL connections are notified of that listener.
//            //
//            // HOWEVER, it is also POSSIBLE to add a local listener (via connection.addListener), meaning that ONLY
//            // that listener is notified on that event (ie, admin type listeners)
//
//            // synchronized because this should be VERY uncommon, and we want to make sure that when the manager
//            // is empty, we can remove it from this connection.
////            synchronized(this) {
////                if (localListenerManager == null) {
////                    localListenerManager = endPoint.addListenerManager(this)
////                }
////                localListenerManager!!.add(listener)
////            }
//        } else {
////            endPoint.listeners()
////                    .add(listener)
//        }
//        return this
//    }

    /**
     * Removes a listener from this connection/endpoint to NO LONGER be notified
     * of connect/disconnect/idle/receive(object) events.
     *
     *
     * When called by a server, NORMALLY listeners are added at the GLOBAL level
     * (meaning, I add one listener, and ALL connections are notified of that
     * listener.
     *
     *
     * It is POSSIBLE to remove a server-connection 'non-global' listener (via
     * connection.removeListener), meaning that ONLY that listener attached to
     * the connection is removed
     */
    override fun remove(listener: OnConnected<Connection>): Listeners<Connection> {
        if (endPoint is Server) {
            // when we are a server, NORMALLY listeners are added at the GLOBAL level
            // meaning --
            //   I add one listener, and ALL connections are notified of that listener.
            //
            // HOWEVER, it is also POSSIBLE to add a local listener (via connection.addListener), meaning that ONLY
            // that listener is notified on that event (ie, admin type listeners)

            // synchronized because this should be uncommon, and we want to make sure that when the manager
            // is empty, we can remove it from this connection.
//            synchronized(this) {
//                val local = localListenerManager
//                if (local != null) {
//                    local.remove(listener)
//                    if (!local.hasListeners()) {
//                        endPoint.removeListenerManager(this)
//                    }
//                }
//            }
        } else {
//            endPoint.listeners()
//                    .remove(listener)
        }
        return this
    }

    /**
     * Removes all registered listeners from this connection/endpoint to NO
     * LONGER be notified of connect/disconnect/idle/receive(object) events.
     *
     * This includes all proxy listeners
     */
    override fun removeAll(): Listeners<Connection> {
//        rmiSupport.removeAllListeners() // TODO

        if (endPoint is Server) {
            // when we are a server, NORMALLY listeners are added at the GLOBAL level
            // meaning --
            //   I add one listener, and ALL connections are notified of that listener.
            //
            // HOWEVER, it is also POSSIBLE to add a local listener (via connection.addListener), meaning that ONLY
            // that listener is notified on that event (ie, admin type listeners)

            // synchronized because this should be uncommon, and we want to make sure that when the manager
            // is empty, we can remove it from this connection.
//            synchronized(this) {
//                if (localListenerManager != null) {
//                    localListenerManager?.removeAll()
//                    localListenerManager = null
//                    endPoint.removeListenerManager(this)
//                }
//            }
        } else {
//            endPoint.listeners()
//                    .removeAll()
        }

        return this
    }

    /**
     * Removes all registered listeners (of the object type) from this connection/endpoint to NO LONGER be notified of
     * connect/disconnect/idle/receive(object) events.
     */
    override fun removeAll(classType: Class<*>): Listeners<Connection> {
        if (endPoint is Server) {
            // when we are a server, NORMALLY listeners are added at the GLOBAL level
            // meaning --
            //   I add one listener, and ALL connections are notified of that listener.
            //
            // HOWEVER, it is also POSSIBLE to add a local listener (via connection.addListener), meaning that ONLY
            // that listener is notified on that event (ie, admin type listeners)

            // synchronized because this should be uncommon, and we want to make sure that when the manager
            // is empty, we can remove it from this connection.
//            synchronized(this) {
//                val local = localListenerManager
//                if (local != null) {
//                    local.removeAll(classType)
//                    if (!local.hasListeners()) {
//                        localListenerManager = null
//                        endPoint.removeListenerManager(this)
//                    }
//                }
//            }
        } else {
//            endPoint.listeners()
//                    .removeAll(classType)
        }
        return this
    }


    override fun isClosed(): Boolean {
        return false
    }

    override fun endPoint(): EndPoint<*> {
        return endPoint
    }


    override fun toString(): String {
//        return channelWrapper.toString()
        return "TODO"
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
//        val other1 = other as ConnectionImpl
//        if (channelWrapper != other1.channelWrapper) {
//            return false
//        }
        return true
    }

    //
    //
    // RMI methods
    //
    //
    override fun rmiSupport(): RmiSupportConnection<Connection_> {
        return rmiSupportConnection
    }

    override suspend fun <Iface> createObject(callback: suspend (Iface) -> Unit) {
        val iFaceClass = ClassHelper.getGenericParameterAsClassForSuperClass(Function2::class.java, callback.javaClass, 0)
        val interfaceClassId = endPoint.serialization.getClassId(iFaceClass)
        rmiSupportConnection.createRemoteObject(this, interfaceClassId, callback)
    }

    override fun <Iface> getObject(objectId: Int, interfaceClass: Class<Iface>): Iface {
        @Suppress("UNCHECKED_CAST")
        return rmiSupportConnection.getRemoteObject(this, endPoint as EndPoint<Connection_>, objectId, interfaceClass)
    }
}
