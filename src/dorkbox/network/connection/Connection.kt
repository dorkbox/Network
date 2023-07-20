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

import dorkbox.network.Client
import dorkbox.network.aeron.AeronDriver.Companion.sessionIdAllocator
import dorkbox.network.aeron.AeronDriver.Companion.streamIdAllocator
import dorkbox.network.ping.Ping
import dorkbox.network.rmi.RmiSupportConnection
import io.aeron.Image
import io.aeron.logbuffer.FragmentHandler
import io.aeron.logbuffer.Header
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.agrona.DirectBuffer
import javax.crypto.SecretKey

/**
 * This connection is established once the registration information is validated, and the various connect/filter checks have passed
 */
open class Connection(connectionParameters: ConnectionParams<*>) {
    private var messageHandler: FragmentHandler

    /**
     * The specific connection details for this connection!
     *
     * NOTE: remember, the connection details are for the connection, but the toString() info is reversed for the client
     *     (so that we can line-up client/server connection logs)
     */
    val info = connectionParameters.connectionInfo

    /**
     * the endpoint associated with this connection
     */
    internal val endPoint = connectionParameters.endPoint

    internal val subscription = info.sub
    internal val publication = info.pub
    private lateinit var image: Image

    /**
     * There can be concurrent writes to the network stack, at most 1 per connection. Each connection has its own logic on the remote endpoint,
     * and can have its own back-pressure.
     */
    internal val sendIdleStrategy = endPoint.config.sendIdleStrategy.cloneToNormal()

    /**
     * This is the client UUID. This is useful determine if the same client is connecting multiple times to a server (instead of only using IP address)
     */
    val uuid = connectionParameters.publicKey

    /**
     * The unique session id of this connection, assigned by the server.
     *
     * Specifically this is the subscription session ID for the server
     */
    val id = if (endPoint::class.java == Client::class.java) {
        info.sessionIdPub
    } else {
        info.sessionIdSub
    }

    /**
     * The remote address, as a string. Will be null for IPC connections
     */
    val remoteAddress = info.remoteAddress

    /**
     * The remote address, as a string. Will be "IPC" for IPC connections
     */
    val remoteAddressString = info.remoteAddressString

    /**
     * The remote port. Will be 0 for IPC connections
     */
    val remotePort = info.portPub

    /**
     * @return true if this connection is an IPC connection
     */
    val isIpc = info.isIpc

    /**
     * @return true if this connection is a network connection
     */
    val isNetwork = !isIpc




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
    internal val remoteKeyChanged = connectionParameters.publicKeyValidation == PublicKeyValidationState.TAMPERED

    /**
     * Methods supporting Remote Method Invocation and Objects
     */
    val rmi: RmiSupportConnection<out Connection>

    // we customize the toString() value for this connection, and it's just better to cache its value (since it's a modestly complex string)
    private val toString0: String

    /**
     * @return the AES key
     */
    internal val cryptoKey: SecretKey = connectionParameters.cryptoKey

    // The IV for AES-GCM must be 12 bytes, since it's 4 (salt) + 4 (external counter) + 4 (GCM counter)
    // The 12 bytes IV is created during connection registration, and during the AES-GCM crypto, we override the last 8 with this
    // counter, which is also transmitted as an optimized int. (which is why it starts at 0, so the transmitted bytes are small)
    internal val aes_gcm_iv = atomic(0)


    init {
        // NOTE: subscriptions (ie: reading from buffers, etc) are not thread safe!  Because it is ambiguous HOW EXACTLY they are unsafe,
        //  we exclusively read from the DirectBuffer on a single thread.

        // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
        //  publication of any state to other threads and not be:
        //   - long running
        //   - re-entrant with the client
        messageHandler = FragmentHandler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
            // Subscriptions are NOT multi-thread safe, so only processed on the thread that calls .poll()!
            endPoint.dataReceive(buffer, offset, length, header, this@Connection)
        }

        @Suppress("LeakingThis")
        rmi = endPoint.rmiConnectionSupport.getNewRmiSupport(this)

        // For toString() and logging
        toString0 = info.getLogInfo(logger.isDebugEnabled)
    }

    /**
     * When this is called, we should always have a subscription image!
     */
    internal suspend fun setImage() {
        var triggered = false
        while (subscription.hasNoImages()) {
            triggered = true
            delay(50)
        }

        if (triggered) {
            logger.error { "Delay while configuring subscription!" }
        }

        image = subscription.imageAtIndex(0)
    }

    /**
     * Polls the AERON media driver subscription channel for incoming messages
     */
    internal fun poll(): Int {
        return image.poll(messageHandler, 1)
    }

    /**
     * Safely sends objects to a destination, if `abortEarly` is true, there are no retries if sending the message fails.
     *
     *  @return true if the message was successfully sent, false otherwise. Exceptions are caught and NOT rethrown!
     */
    internal suspend fun send(message: Any, abortEarly: Boolean): Boolean {
        var success = false

        // this is dispatched to the IO context!! (since network calls are IO/blocking calls)
        withContext(Dispatchers.IO) {
            // The handshake sessionId IS NOT globally unique
            logger.trace { "[$toString0] send: ${message.javaClass.simpleName} : $message" }
            success = endPoint.write(message, publication, sendIdleStrategy, this@Connection, abortEarly)
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
     * This is the per-message sequence number.
     *
     * The IV for AES-GCM must be 12 bytes, since it's 4 (salt) + 4 (external counter) + 4 (GCM counter)
     * The 12 bytes IV is created during connection registration, and during the AES-GCM crypto, we override the last 8 with this
     * counter, which is also transmitted as an optimized int. (which is why it starts at 0, so the transmitted bytes are small)
     */
    internal fun nextGcmSequence(): Int {
        return aes_gcm_iv.getAndIncrement()
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
        close(true)
    }

    /**
     * Closes the connection, and removes all connection specific listeners
     */
    internal suspend fun close(sendDisconnectMessage: Boolean) {
        // there are 2 ways to call close.
        //   MANUALLY
        //   When a connection is disconnected via a timeout/expire.
        // the compareAndSet is used to make sure that if we call close() MANUALLY, (and later) when the auto-cleanup/disconnect is called -- it doesn't
        // try to do it again.

        // make sure that EVERYTHING before "close()" runs before we do
        EventDispatcher.launchSequentially(EventDispatcher.CLOSE) {
            closeImmediately(sendDisconnectMessage)
        }
    }


    // connection.close() -> this
    // endpoint.close() -> connection.close() -> this
    internal suspend fun closeImmediately(sendDisconnectMessage: Boolean) {
        // the server 'handshake' connection info is cleaned up with the disconnect via timeout/expire.
        if (!isClosed.compareAndSet(expect = false, update = true)) {
            return
        }

        logger.debug {"[$toString0] connection closing"}

        // on close, we want to make sure this file is DELETED!
        endPoint.aeronDriver.close(subscription, toString0)

        // notify the remote endPoint that we are closing
        // we send this AFTER we close our subscription (so that no more messages will be received, when the remote end ping-pong's this message back)
        if (sendDisconnectMessage && publication.isConnected) {
            logger.trace { "Sending disconnect message to remote endpoint" }

            // sometimes the remote end has already disconnected, THERE WILL BE ERRORS if this happens (but they are ok)
            send(DisconnectMessage.INSTANCE, true)
        }

        // on close, we want to make sure this file is DELETED!
        endPoint.aeronDriver.close(publication, toString0)

        // NOTE: any waiting RMI messages that are in-flight will terminate when they time-out (and then do nothing)
        // NOTE: notifyDisconnect() is called inside closeAction()!!

        endPoint.removeConnection(this)
        endPoint.listenerManager.notifyDisconnect(this)


        val connection = this
        endPoint.isServer {
            // clean up the resources associated with this connection when it's closed
            logger.debug { "[${connection}] freeing resources" }
            sessionIdAllocator.free(info.sessionIdPub)
            sessionIdAllocator.free(info.sessionIdSub)

            streamIdAllocator.free(info.streamIdPub)
            streamIdAllocator.free(info.streamIdSub)

            if (remoteAddress != null) {
                // unique for UDP endpoints
                handshake.connectionsPerIpCounts.decrementSlow(remoteAddress)
            }
        }

        logger.debug {"[$toString0] connection closed"}
    }


    // called in a ListenerManager.notifyDisconnect(), so we don't expose our internal listenerManager
    internal fun notifyDisconnect() {
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
}
