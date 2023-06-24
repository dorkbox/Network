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
package dorkbox.network.connection

import dorkbox.network.aeron.AeronDriver.Companion.sessionIdAllocator
import dorkbox.network.aeron.AeronDriver.Companion.streamIdAllocator
import dorkbox.network.exceptions.ClientException
import dorkbox.network.exceptions.SerializationException
import dorkbox.network.exceptions.ServerException
import dorkbox.network.exceptions.TransmitException
import dorkbox.network.handshake.ConnectionCounts
import dorkbox.network.ping.Ping
import dorkbox.network.rmi.RmiSupportConnection
import dorkbox.network.rmi.messages.MethodResponse
import dorkbox.network.serialization.KryoExtra
import io.aeron.FragmentAssembler
import io.aeron.logbuffer.Header
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.agrona.DirectBuffer
import org.agrona.concurrent.IdleStrategy
import java.util.concurrent.*

/**
 * This connection is established once the registration information is validated, and the various connect/filter checks have passed
 */
open class Connection(connectionParameters: ConnectionParams<*>) {
    private var messageHandler: FragmentAssembler

    private val subscription = connectionParameters.connectionInfo.sub
    private val publication = connectionParameters.connectionInfo.pub

    /**
     * When publishing data, we cannot have concurrent publications for a single connection (per Aeron publication)
     */
    private val writeMutex = Mutex()

    /**
     * There can be concurrent writes to the network stack, at most 1 per connection. Each connection has its own logic on the remote endpoint,
     * and can have its own back-pressure.
     */
    private val sendIdleStrategy: IdleStrategy

    private val writeKryo: KryoExtra<Connection>
    private val tempWriteKryo: KryoExtra<Connection>

    /**
     * This is the client UUID. This is useful determine if the same client is connecting multiple times to a server (instead of only using IP address)
     */
    val uuid = connectionParameters.clientUuid

    /**
     * The unique session id of this connection, assigned by the server.
     *
     * Specifically this is the subscription session ID of the server
     */
    val id = connectionParameters.connectionInfo.sessionIdSub

    /**
     * the remote address, as a string. Will be null for IPC connections
     */
    val remoteAddress = connectionParameters.connectionInfo.remoteAddress

    /**
     * the remote address, as a string. Will be "ipc" for IPC connections
     */
    val remoteAddressString = connectionParameters.connectionInfo.remoteAddressString

    /**
     * @return true if this connection is an IPC connection
     */
    val isIpc = connectionParameters.connectionInfo.isIpc

    /**
     * @return true if this connection is a network connection
     */
    val isNetwork = !isIpc

    /**
     * the endpoint associated with this connection
     */
    internal val endPoint = connectionParameters.endPoint


    private val listenerManager = atomic<ListenerManager<Connection>?>(null)
    val logger = endPoint.logger

    private val isClosed = atomic(false)

    // only accessed on a single thread!
    private var connectionLastCheckTimeNanos = 0L
    private var connectionTimeoutTimeNanos = 0L

    // always offset by the linger amount, since we cannot act faster than the linger timeout for adding/removing publications
    private val connectionCheckIntervalNanos = endPoint.config.connectionCheckIntervalNanos + endPoint.aeronDriver.lingerNs()
    private val connectionExpirationTimoutNanos = endPoint.config.connectionExpirationTimoutNanos + endPoint.aeronDriver.lingerNs()


    // while on the CLIENT, if the SERVER's ecc key has changed, the client will abort and show an error.
    private val remoteKeyChanged = connectionParameters.publicKeyValidation == PublicKeyValidationState.TAMPERED

    // The IV for AES-GCM must be 12 bytes, since it's 4 (salt) + 8 (external counter) + 4 (GCM counter)
    // The 12 bytes IV is created during connection registration, and during the AES-GCM crypto, we override the last 8 with this
    // counter, which is also transmitted as an optimized int. (which is why it starts at 0, so the transmitted bytes are small)
//    private val aes_gcm_iv = atomic(0)

    /**
     * Methods supporting Remote Method Invocation and Objects
     */
    val rmi: RmiSupportConnection<out Connection>

    /**
     * The specific connection details for this connection!
     *
     * NOTE: remember, the connection details are for the connection, but the toString() info is reversed for the client
     *     (so that we can line-up client/server connection logs)
     */
    val info = connectionParameters.connectionInfo

    // we customize the toString() value for this connection, and it's just better to cache it's value (since it's a modestly complex string)
    private val toString0: String



    init {
        @Suppress("UNCHECKED_CAST")
        writeKryo = endPoint.serialization.initKryo() as KryoExtra<Connection>
        @Suppress("UNCHECKED_CAST")
        tempWriteKryo = endPoint.serialization.initKryo() as KryoExtra<Connection>


        sendIdleStrategy = endPoint.config.sendIdleStrategy.cloneToNormal()


        // NOTE: subscriptions (ie: reading from buffers, etc) are not thread safe!  Because it is ambiguous HOW EXACTLY they are unsafe,
        //  we exclusively read from the DirectBuffer on a single thread.

        // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
        //  publication of any state to other threads and not be:
        //   - long running
        //   - re-entrant with the client
        messageHandler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
            // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!

            endPoint.dataReceive(buffer, offset, length, header, this@Connection)
        }

        @Suppress("LeakingThis")
        rmi = endPoint.rmiConnectionSupport.getNewRmiSupport(this)

        // For toString() and logging
        // Note: the pub/sub info is from the perspective of the SERVER
        toString0 = info.getLogInfo(logger.isDebugEnabled)
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
    internal fun poll(): Int {
        // NOTE: regarding fragment limit size. Repeated calls to '.poll' will reassemble a fragment.
        //   `.poll(handler, 4)` == `.poll(handler, 2)` + `.poll(handler, 2)`
        return subscription.poll(messageHandler, 1)
    }

    /**
     * Safely sends objects to a destination, if `abortEarly` is true, there are no retries if sending the message fails.
     *
     * NOTE: this is dispatched to the IO context!! (since network calls are IO/blocking calls)
     *
     * @return true if the message was successfully sent, false otherwise. Exceptions are caught and NOT rethrown!
     */
    internal suspend fun send(message: Any, abortEarly: Boolean): Boolean {
        // we use a mutex because we do NOT want different threads/coroutines to be able to send data over the SAME connections at the SAME time.
        // NOTE: additionally we want to propagate back-pressure to the calling coroutines, PER CONNECTION!

        val success = writeMutex.withLock {
            // we reset the sending timeout strategy when a message was successfully sent.
            sendIdleStrategy.reset()

            try {
                // The handshake sessionId IS NOT globally unique
                logger.trace { "[$toString0] send: ${message.javaClass.simpleName} : $message" }
                val write = endPoint.write(writeKryo, tempWriteKryo, message, publication, sendIdleStrategy, this@Connection, abortEarly)
                write
            } catch (e: Throwable) {
                // make sure we atomically create the listener manager, if necessary
                listenerManager.getAndUpdate { origManager ->
                    origManager ?: ListenerManager(logger)
                }

                val listenerManager = listenerManager.value!!



                if (message is MethodResponse && message.result is Exception) {
                    val result = message.result as Exception
                    val newException = SerializationException("Error serializing message ${message.javaClass.simpleName}: '$message'", result)
                    listenerManager.notifyError(this@Connection, newException)
                } else if (message is ClientException || message is ServerException) {
                    val newException = TransmitException("Error with message ${message.javaClass.simpleName}: '$message'", e)
                    listenerManager.notifyError(this@Connection, newException)
                } else {
                    val newException = TransmitException("Error sending message ${message.javaClass.simpleName}: '$message'", e)
                    listenerManager.notifyError(this@Connection, newException)
                }

                false
            }
        }


        return success
    }

    /**
     * Safely sends objects to a destination.
     *
     * NOTE: this is dispatched to the IO context!! (since network calls are IO/blocking calls)
     *
     * @return true if the message was successfully sent, false otherwise. Exceptions are caught and NOT rethrown!
     */
    suspend fun send(message: Any): Boolean {
        return send(message, false)
    }

    /**
     * Sends a "ping" packet to measure **ROUND TRIP** time to the remote connection.
     *
     * @return true if the message was successfully sent by aeron
     */
    suspend fun ping(pingTimeoutSeconds: Int = endPoint.config.pingTimeoutSeconds, function: suspend Ping.() -> Unit = {}): Boolean {
        return endPoint.ping(this, pingTimeoutSeconds, function)
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
    suspend fun onDisconnect(function: suspend Connection.() -> Unit) {
        // make sure we atomically create the listener manager, if necessary
        listenerManager.getAndUpdate { origManager ->
            origManager ?: ListenerManager(logger)
        }

        listenerManager.value!!.onDisconnect(function)
    }

    /**
     * Adds a function that will be called only for this connection, when a client/server receives a message
     */
    suspend fun <MESSAGE> onMessage(function: suspend Connection.(MESSAGE) -> Unit) {
        // make sure we atomically create the listener manager, if necessary
        listenerManager.getAndUpdate { origManager ->
            origManager ?: ListenerManager(logger)
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

    /**
     * We must account for network blips. The blips will be recovered by aeron, but we want to make sure that we are actually
     * disconnected for a set period of time before we start the close process for a connection
     *
     * @return `true` if this connection has been closed via aeron
     */
    fun isClosedViaAeron(): Boolean {
        if (isClosed.value) {
            // if we are manually closed, then don't check aeron timeouts!
            return true
        }

        // we ONLY want to actually, legit check, 1 time every XXX ms.
        val now = System.nanoTime()

        if (now - connectionLastCheckTimeNanos < connectionCheckIntervalNanos) {
            // we haven't waited long enough for another check. always return false (true means we are closed)
            return false
        }
        connectionLastCheckTimeNanos = now

        // as long as we are connected, we reset the state, so that if there is a network blip, we want to make sure that it is
        // a network blip for a while, instead of just once or twice. (which can happen)
        if (subscription.isConnected && publication.isConnected) {
            // reset connection timeout
            connectionTimeoutTimeNanos = 0L

            // we are still connected (true means we are closed)
            return false
        }

        //
        // aeron is not connected
        //

        if (connectionTimeoutTimeNanos == 0L) {
            connectionTimeoutTimeNanos = now
        }

        // make sure that our "isConnected" state lasts LONGER than the expiry timeout!

        // 1) connections take a little bit of time from polling -> connecting (because of how we poll connections before 'connecting' them).
        // 2) network blips happen. Aeron will recover, and we want to make sure that WE don't instantly DC
        return now - connectionTimeoutTimeNanos >= connectionExpirationTimoutNanos
    }

    /**
     * @return true if this connection has had close() called
     */
    fun isClosed(): Boolean {
        return isClosed.value
    }

    /**
     * Closes the connection, and removes all connection specific listeners
     */
    suspend fun close() {
        // there are 2 ways to call close.
        //   MANUALLY
        //   When a connection is disconnected via a timeout/expire.
        // the compareAndSet is used to make sure that if we call close() MANUALLY, (and later) when the auto-cleanup/disconnect is called -- it doesn't
        // try to do it again.

        // make sure that EVERYTHING before "close()" runs before we do
        EventDispatcher.launchSequentially(EventDispatcher.CLOSE) {
            closeImmediately()
        }
    }


    // connection.close() -> this
    // endpoint.close() -> connection.close() -> this
    internal suspend fun closeImmediately() {
        // the server 'handshake' connection info is cleaned up with the disconnect via timeout/expire.
        if (!isClosed.compareAndSet(expect = false, update = true)) {
            return
        }

        logger.debug {"[$toString0] connection closing"}

        // on close, we want to make sure this file is DELETED!
        endPoint.aeronDriver.closeAndDeleteSubscription(subscription, toString0)

        // notify the remote endPoint that we are closing
        // we send this AFTER we close our subscription (so that no more messages will be received, when the remote end ping-pong's this message back)
        if (publication.isConnected) {
            // sometimes the remote end has already disconnected
            send(DisconnectMessage.INSTANCE, true)
        }

        val timeoutInNanos = TimeUnit.SECONDS.toNanos(endPoint.config.connectionCloseTimeoutInSeconds.toLong())
        val closeTimeoutTime = System.nanoTime()

        // we do not want to close until AFTER all publications have been sent. Calling this WITHOUT waiting will instantly stop everything
        // we want a timeout-check, otherwise this will run forever
        while (writeMutex.isLocked && System.nanoTime() - closeTimeoutTime < timeoutInNanos) {
            delay(50)
        }

        // on close, we want to make sure this file is DELETED!
        endPoint.aeronDriver.closeAndDeletePublication(publication, toString0)

        // NOTE: any waiting RMI messages that are in-flight will terminate when they time-out (and then do nothing)
        // NOTE: notifyDisconnect() is called inside closeAction()!!

        endPoint.removeConnection(this)
        endPoint.listenerManager.notifyDisconnect(this)


        val connection = this
        // clean up the resources associated with this connection when it's closed
        endPoint.isServer {
            logger.debug { "[${connection}] freeing resources" }
            connection.cleanup(handshake.connectionsPerIpCounts)
        }

        logger.debug {"[$toString0] connection closed"}
    }


    // called in a ListenerManager.notifyDisconnect(), so we don't expose our internal listenerManager
    internal suspend fun notifyDisconnect() {
        val connectionSpecificListenerManager = listenerManager.value
        connectionSpecificListenerManager?.directNotifyDisconnect(this@Connection)
    }


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

    // cleans up the connection information (only the server calls this!)
    internal fun cleanup(connectionsPerIpCounts: ConnectionCounts) {
        sessionIdAllocator.free(info.sessionIdPub)
        sessionIdAllocator.free(info.sessionIdSub)

        streamIdAllocator.free(info.streamIdPub)
        streamIdAllocator.free(info.streamIdSub)

        if (remoteAddress != null) {
            // unique for UDP endpoints
            connectionsPerIpCounts.decrementSlow(remoteAddress)
        }
    }
}
