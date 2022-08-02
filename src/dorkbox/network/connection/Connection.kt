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

import dorkbox.network.handshake.ConnectionCounts
import dorkbox.network.handshake.RandomId65kAllocator
import dorkbox.network.ping.Ping
import dorkbox.network.ping.PingManager
import dorkbox.network.rmi.RmiSupportConnection
import io.aeron.FragmentAssembler
import io.aeron.Publication
import io.aeron.Subscription
import io.aeron.logbuffer.Header
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.coroutines.runBlocking
import org.agrona.DirectBuffer
import java.lang.Thread.sleep
import java.net.InetAddress
import java.util.concurrent.*

/**
 * This connection is established once the registration information is validated, and the various connect/filter checks have passed
 */
open class Connection(connectionParameters: ConnectionParams<*>) {
    private var messageHandler: FragmentAssembler
    internal val subscription: Subscription
    internal val publication: Publication

    /**
     * The publication port (used by aeron) for this connection. This is from the perspective of the server!
     */
    private val subscriptionPort: Int
    private val publicationPort: Int

    /**
     * the stream id of this connection. Can be 0 for IPC connections
     */
    val streamId: Int

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
    val isIpc = connectionParameters.connectionInfo.remoteAddress == null

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

    internal val isClosed = atomic(false)

    // enableNotifyDisconnect : we don't always want to enable notifications on disconnect
    internal var closeAction: suspend (enableNotifyDisconnect: Boolean) -> Unit = {}

    // only accessed on a single thread!
    private var connectionLastCheckTimeNanos = 0L
    private var connectionTimeoutTimeNanos = 0L

    // always offset by the linger amount, since we cannot act faster than the linger for adding/removing publications
    private val connectionCheckIntervalNanos = connectionParameters.endPoint.config.connectionCheckIntervalNanos + endPoint.aeronDriver.getLingerNs()
    private val connectionExpirationTimoutNanos = connectionParameters.endPoint.config.connectionExpirationTimoutNanos + endPoint.aeronDriver.getLingerNs()


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

    // a record of how many messages are in progress of being sent. When closing the connection, this number must be 0
    private val messagesInProgress = atomic(0)

    // we customize the toString() value for this connection, and it's just better to cache it's value (since it's a modestly complex string)
    private val toString0: String



    init {
        val connectionInfo = connectionParameters.connectionInfo

        id = connectionInfo.sessionId // NOTE: this is UNIQUE per server!

        subscription = connectionInfo.subscription
        publication = connectionInfo.publication

        // can only get this AFTER we have built the sub/pub
        streamId = connectionInfo.streamId // NOTE: this is UNIQUE per server!
        subscriptionPort = connectionInfo.subscriptionPort
        publicationPort = connectionInfo.publicationPort

        remoteAddress = connectionInfo.remoteAddress
        remoteAddressString = connectionInfo.remoteAddressString

        toString0 = "[${id}/${streamId}] $remoteAddressString [$publicationPort|$subscriptionPort]"

        messageHandler = FragmentAssembler { buffer: DirectBuffer, offset: Int, length: Int, header: Header ->
            // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!

            // NOTE: subscriptions (ie: reading from buffers, etc) are not thread safe!  Because it is ambiguous HOW EXACTLY they are unsafe,
            //  we exclusively read from the DirectBuffer on a single thread.
            endPoint.processMessage(buffer, offset, length, header, this@Connection)
        }

        @Suppress("LeakingThis")
        rmi = connectionParameters.endPoint.rmiConnectionSupport.getNewRmiSupport(this)
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
     * Safely sends objects to a destination.
     *
     * @return true if the message was successfully sent, false otherwise. Exceptions are caught and NOT rethrown!
     */
    fun send(message: Any): Boolean {
        messagesInProgress.getAndIncrement()
        val success = endPoint.send(message, publication, this)
        messagesInProgress.getAndDecrement()

        return success
    }

    /**
     * Sends a "ping" packet to measure **ROUND TRIP** time to the remote connection.
     *
     * @return true if the message was successfully sent by aeron
     */
    suspend fun ping(pingTimeoutSeconds: Int = PingManager.DEFAULT_TIMEOUT_SECONDS, function: suspend Ping.() -> Unit): Boolean {
        return endPoint.ping(this, pingTimeoutSeconds, function)
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
     * Closes the connection, and removes all connection specific listeners
     */
    fun close() {
        close(enableRemove = true,
              enableNotifyDisconnect = true)
    }

    /**
     * Closes the connection, and removes all connection specific listeners
     */
    internal fun close(enableRemove: Boolean, enableNotifyDisconnect: Boolean) {
        // there are 2 ways to call close.
        //   MANUALLY
        //   When a connection is disconnected via a timeout/expire.
        // the compareAndSet is used to make sure that if we call close() MANUALLY, (and later) when the auto-cleanup/disconnect is called -- it doesn't
        // try to do it again.

        // the server 'handshake' connection info is cleaned up with the disconnect via timeout/expire.
        if (isClosed.compareAndSet(expect = false, update = true)) {
            val aeronLogInfo = "${id}/${streamId}"
            logger.debug {"[$aeronLogInfo] connection closing"}

            subscription.close()


            val timoutInNanos = TimeUnit.SECONDS.toNanos(endPoint.config.connectionCloseTimeoutInSeconds.toLong())
            var closeTimeoutTime = System.nanoTime()

            // we do not want to close until AFTER all publications have been sent. Calling this WITHOUT waiting will instantly stop everything
            // we want a timeout-check, otherwise this will run forever
            while (messagesInProgress.value != 0 && System.nanoTime() - closeTimeoutTime < timoutInNanos) {
                sleep(50)
            }

            // on close, we want to make sure this file is DELETED!
            val logFile = endPoint.aeronDriver.getMediaDriverPublicationFile(publication.registrationId())
            publication.close()


            closeTimeoutTime = System.nanoTime()
            while (logFile.exists() && System.nanoTime() - closeTimeoutTime < timoutInNanos) {
                if (logFile.delete()) {
                    break
                }
                sleep(100)
            }

            if (logFile.exists()) {
                logger.error("[$aeronLogInfo] Unable to delete aeron publication log on close: $logFile")
            }

            if (enableRemove) {
                endPoint.removeConnection(this)
            }

            // NOTE: notifyDisconnect() is called inside closeAction()!!

            // This is set by the client/server so if there is a "connect()" call in the the disconnect callback, we can have proper
            // lock-stop ordering for how disconnect and connect work with each-other
            runBlocking {
                closeAction(enableNotifyDisconnect)
            }
            logger.debug {"[$aeronLogInfo] connection closed"}
        }
    }

    // called in postCloseAction(), so we don't expose our internal listenerManager
    internal suspend fun doNotifyDisconnect() {
        val connectionSpecificListenerManager = listenerManager.value
        if (connectionSpecificListenerManager != null) {
            // this always has to be on event dispatch, otherwise we can have weird logic loops if we reconnect within a disconnect callback
            endPoint.actionDispatch.eventLoop {
                // a connection might have also registered for disconnect events (THIS IS NOT THE "CLIENT/SERVER" listenerManager!)
                connectionSpecificListenerManager.notifyDisconnect(this@Connection)
            }
        }
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
    internal fun cleanup(connectionsPerIpCounts: ConnectionCounts, sessionIdAllocator: RandomId65kAllocator, streamIdAllocator: RandomId65kAllocator) {
        sessionIdAllocator.free(id)

        if (isIpc) {
            streamIdAllocator.free(publicationPort)
            streamIdAllocator.free(subscriptionPort)
        } else {
            // unique for UDP endpoints
            connectionsPerIpCounts.decrementSlow(remoteAddress!!)
            streamIdAllocator.free(streamId)
        }
    }
}
