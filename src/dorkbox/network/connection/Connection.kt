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
import dorkbox.network.Server
import dorkbox.network.aeron.AeronDriver.Companion.sessionIdAllocator
import dorkbox.network.aeron.AeronDriver.Companion.streamIdAllocator
import dorkbox.network.connection.session.SessionConnection
import dorkbox.network.ping.Ping
import dorkbox.network.rmi.RmiSupportConnection
import io.aeron.Image
import io.aeron.logbuffer.FragmentHandler
import io.aeron.logbuffer.Header
import io.aeron.protocol.DataHeaderFlyweight
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
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

    // only accessed on a single thread!
    private val connectionExpirationTimoutNanos = endPoint.config.connectionExpirationTimoutNanos
    // the timeout starts from when the connection is first created, so that we don't get "instant" timeouts when the server rejects a connection
    private var connectionTimeoutTimeNanos = System.nanoTime()

    /**
     * There can be concurrent writes to the network stack, at most 1 per connection. Each connection has its own logic on the remote endpoint,
     * and can have its own back-pressure.
     */
    internal val sendIdleStrategy = endPoint.config.sendIdleStrategy

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

    /**
     * The largest size a SINGLE message via AERON can be. Because the maximum size we can send in a "single fragment" is the
     * publication.maxPayloadLength() function (which is the MTU length less header). We could depend on Aeron for fragment reassembly,
     * but that has a (very low) maximum reassembly size -- so we have our own mechanism for object fragmentation/assembly, which
     * is (in reality) only limited by available ram.
     */
    internal val maxMessageSize = if (isNetwork) {
        endPoint.config.networkMtuSize - DataHeaderFlyweight.HEADER_LENGTH
    } else {
        endPoint.config.ipcMtuSize - DataHeaderFlyweight.HEADER_LENGTH
    }


    private val listenerManager = atomic<ListenerManager<Connection>?>(null)
    val logger = endPoint.logger

    private val isClosed = atomic(false)


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

    // Used to track that this connection WILL be closed, but has not yet been closed.
    @Volatile
    internal var closeRequested = false


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
        toString0 = info.getLogInfo(logger)
    }

    /**
     * When this is called, we should always have a subscription image!
     */
    internal fun setImage() {
        var triggered = false
        while (subscription.hasNoImages()) {
            triggered = true
            Thread.sleep(50)
        }

        if (triggered) {
            logger.error("Delay while configuring subscription!")
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
    internal open fun send(message: Any, abortEarly: Boolean): Boolean {
        if (logger.isTraceEnabled) {
            // The handshake sessionId IS NOT globally unique
            // don't automatically create the lambda when trace is disabled! Because this uses 'outside' scoped info, it's a new lambda each time!
            if (logger.isTraceEnabled) {
                logger.trace("[$toString0] send: ${message.javaClass.simpleName} : $message")
            }
        }
        return endPoint.write(message, publication, sendIdleStrategy, this@Connection, maxMessageSize, abortEarly)
    }

    /**
     * Safely sends objects to a destination.
     *
     * @return true if the message was successfully sent, false otherwise. Exceptions are caught and NOT rethrown!
     */
    fun send(message: Any): Boolean {
        return send(message, false)
    }

    /**
     * Sends a "ping" packet to measure **ROUND TRIP** time to the remote connection.
     *
     * @return true if the message was successfully sent by aeron
     */
    fun ping(function: Ping.() -> Unit = {}): Boolean {
        return sendPing(function)
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
    fun onDisconnect(function: Connection.() -> Unit) {
        // make sure we atomically create the listener manager, if necessary
        listenerManager.getAndUpdate { origManager ->
            origManager ?: ListenerManager(logger, endPoint.eventDispatch)
        }

        listenerManager.value!!.onDisconnect(function)
    }

    /**
     * Adds a function that will be called only for this connection, when a client/server receives a message
     */
    fun <MESSAGE> onMessage(function: Connection.(MESSAGE) -> Unit) {
        // make sure we atomically create the listener manager, if necessary
        listenerManager.getAndUpdate { origManager ->
            origManager ?: ListenerManager(logger, endPoint.eventDispatch)
        }

        listenerManager.value!!.onMessage(function)
    }

    /**
     * Invoked when a message object was received from a remote peer.
     *
     * This is ALWAYS called on a new dispatch
     */
    internal fun notifyOnMessage(message: Any): Boolean {
        return listenerManager.value?.notifyOnMessage(this, message) ?: false
    }

    /**
     * @return true if this connection has had close() called
     */
    fun isClosed(): Boolean {
        return isClosed.value
    }

    /**
     * We must account for network blips. The blips will be recovered by aeron, but we want to make sure that we are actually
     * disconnected for a set period of time before we start the close process for a connection
     *
     * @return `true` if this connection has been closed via aeron
     */
    fun isClosedWithTimeout(): Boolean {
        // we ONLY want to actually, legit check, 1 time every XXX ms.
        val now = System.nanoTime()

        // as long as we are connected, we reset the state, so that if there is a network blip, we want to make sure that it is
        // a network blip for a while, instead of just once or twice. (which WILL happen)
        if (subscription.isConnected && publication.isConnected) {
            // reset connection timeout
            connectionTimeoutTimeNanos = now

            // we are still connected (true means we are closed)
            return false
        }


        // make sure that our "isConnected" state lasts LONGER than the expiry timeout!

        // 1) connections take a little bit of time from polling -> connecting (because of how we poll connections before 'connecting' them).
        // 2) network blips happen. Aeron will recover, and we want to make sure that WE don't instantly DC
        return now - connectionTimeoutTimeNanos >= connectionExpirationTimoutNanos
    }


    /**
     * Closes the connection, and removes all connection specific listeners
     */
    fun close() {
        close(sendDisconnectMessage = true,
              closeEverything = true)
    }

    /**
     * Closes the connection, and removes all connection specific listeners
     */
    internal fun close(sendDisconnectMessage: Boolean, closeEverything: Boolean) {
        // there are 2 ways to call close.
        //   MANUALLY
        //   When a connection is disconnected via a timeout/expire.

        // the compareAndSet is used to make sure that if we call close() MANUALLY, (and later) when the auto-cleanup/disconnect is called -- it doesn't
        // try to do it again.

        // make sure that EVERYTHING before "close()" runs before we do.
        // If there are multiple clients/servers sharing the same NetworkPoller -- then they will wait on each other!
        val close = endPoint.eventDispatch.CLOSE
        if (!close.isDispatch()) {
            close.launch {
                close(sendDisconnectMessage = sendDisconnectMessage, closeEverything = closeEverything)
            }
            return
        }

        closeImmediately(sendDisconnectMessage = sendDisconnectMessage, closeEverything = closeEverything)
    }


    // connection.close() -> this
    // endpoint.close() -> connection.close() -> this
    internal fun closeImmediately(sendDisconnectMessage: Boolean, closeEverything: Boolean) {
        // the server 'handshake' connection info is cleaned up with the disconnect via timeout/expire.
        if (!isClosed.compareAndSet(expect = false, update = true)) {
            logger.debug("[$toString0] connection ignoring close request.")
            return
        }

        if (logger.isDebugEnabled) {
            logger.debug("[$toString0] connection closing. sendDisconnectMessage=$sendDisconnectMessage, closeEverything=$closeEverything")
        }

        // make sure to save off the RMI objects for session management
        if (!closeEverything && endPoint.sessionManager.enabled()) {
            endPoint.sessionManager.onDisconnect(this as SessionConnection)
        }

        // on close, we want to make sure this file is DELETED!
        try {
            // we might not be able to close this connection!!
            endPoint.aeronDriver.close(subscription, toString0)
        }
        catch (e: Exception) {
            endPoint.listenerManager.notifyError(e)
        }


        // notify the remote endPoint that we are closing
        // we send this AFTER we close our subscription (so that no more messages will be received, when the remote end ping-pong's this message back)
        if (sendDisconnectMessage && publication.isConnected) {
            if (logger.isTraceEnabled) {
                logger.trace("Sending disconnect message to ${endPoint.otherTypeName}")
            }

            // sometimes the remote end has already disconnected, THERE WILL BE ERRORS if this happens (but they are ok)
            if (closeEverything) {
                send(DisconnectMessage.CLOSE_EVERYTHING, true)
            } else {
                send(DisconnectMessage.CLOSE_FOR_SESSION, true)
            }
        }

        // on close, we want to make sure this file is DELETED!
        try {
            // we might not be able to close this connection.
            endPoint.aeronDriver.close(publication, toString0)
        }
        catch (e: Exception) {
            endPoint.listenerManager.notifyError(e)
        }

        // NOTE: any waiting RMI messages that are in-flight will terminate when they time-out (and then do nothing)
        // if there are errors within the driver, we do not want to notify disconnect, as we will automatically reconnect.
        endPoint.listenerManager.notifyDisconnect(this)

        endPoint.removeConnection(this)


        val connection = this
        if (endPoint.isServer()) {
            // clean up the resources associated with this connection when it's closed
            if (logger.isDebugEnabled) {
                logger.debug("[${connection}] freeing resources")
            }
            sessionIdAllocator.free(info.sessionIdPub)
            sessionIdAllocator.free(info.sessionIdSub)

            streamIdAllocator.free(info.streamIdPub)
            streamIdAllocator.free(info.streamIdSub)

            if (remoteAddress != null) {
                // unique for UDP endpoints
                (endPoint as Server).handshake.connectionsPerIpCounts.decrementSlow(remoteAddress)
            }
        }

        if (logger.isDebugEnabled) {
            logger.debug("[$toString0] connection closed")
        }
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

    internal fun receivePing(ping: Ping) {
        if (ping.pongTime == 0L) {
            // this is on the "remote end".
            ping.pongTime = System.currentTimeMillis()

            if (!send(ping)) {
                logger.error("Error returning ping: $ping")
            }
        } else {
            // this is on the "local end" when the response comes back
            ping.finishedTime = System.currentTimeMillis()

            val rmiId = ping.packedId

            // process the ping message so that our ping callback does something

            // this will be null if the ping took longer than XXX seconds and was cancelled
            val result = EndPoint.responseManager.removeWaiterCallback<Ping.() -> Unit>(rmiId, logger)
            if (result != null) {
                result(ping)
            } else {
                logger.error("Unable to receive ping, there was no waiting response for $ping ($rmiId)")
            }
        }
    }

    internal fun sendPing(function: Ping.() -> Unit): Boolean {
        val id = EndPoint.responseManager.prepWithCallback(logger, function)

        val ping = Ping()
        ping.packedId = id
        ping.pingTime = System.currentTimeMillis()

        // if there is no ping response EVER, it means that the connection is in a critically BAD state!
        // eventually, all the ping replies (or, in our case, the RMI replies that have timed out) will
        // become recycled.
        // Is it a memory-leak? No, because the memory will **EVENTUALLY** get freed.

        return send(ping)
    }
}
