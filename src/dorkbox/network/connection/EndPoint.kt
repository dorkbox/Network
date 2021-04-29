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

import dorkbox.network.Client
import dorkbox.network.Configuration
import dorkbox.network.Server
import dorkbox.network.ServerConfiguration
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.CoroutineIdleStrategy
import dorkbox.network.coroutines.SuspendWaiter
import dorkbox.network.exceptions.MessageNotRegisteredException
import dorkbox.network.handshake.HandshakeMessage
import dorkbox.network.ipFilter.IpFilterRule
import dorkbox.network.ping.PingManager
import dorkbox.network.ping.PingMessage
import dorkbox.network.rmi.RmiManagerConnections
import dorkbox.network.rmi.RmiManagerGlobal
import dorkbox.network.rmi.messages.RmiMessage
import dorkbox.network.serialization.KryoExtra
import dorkbox.network.serialization.Serialization
import dorkbox.network.storage.SettingsStore
import dorkbox.util.exceptions.SecurityException
import io.aeron.Publication
import io.aeron.driver.MediaDriver
import io.aeron.logbuffer.Header
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KLogger
import mu.KotlinLogging
import org.agrona.DirectBuffer
import org.agrona.concurrent.IdleStrategy


// If TCP and UDP both fill the pipe, THERE WILL BE FRAGMENTATION and dropped UDP packets!
// it results in severe UDP packet loss and contention.
//
// http://www.isoc.org/INET97/proceedings/F3/F3_1.HTM
// also, a google search on just "INET97/proceedings/F3/F3_1.HTM" turns up interesting problems.
// Usually it's with ISPs.
/**
 * represents the base of a client/server end point for interacting with aeron
 *
 * @param type this is either "Client" or "Server", depending on who is creating this endpoint.
 * @param config these are the specific connection options
 *
 * @throws SecurityException if unable to initialize/generate ECC keys
*/
abstract class EndPoint<CONNECTION : Connection>
internal constructor(val type: Class<*>, internal val config: Configuration) : AutoCloseable {
    protected constructor(config: Configuration) : this(Client::class.java, config)
    protected constructor(config: ServerConfiguration) : this(Server::class.java, config)

    val logger: KLogger = KotlinLogging.logger(type.simpleName)

    internal val actionDispatch = CoroutineScope(Dispatchers.Default)

    internal val listenerManager = ListenerManager<CONNECTION>()
    internal val connections = ConnectionManager<CONNECTION>()

    internal val pingManager = PingManager<CONNECTION>(logger, actionDispatch)

    internal val aeronDriver: AeronDriver

    /**
     * Returns the serialization wrapper if there is an object type that needs to be added outside of the basic types.
     */
    val serialization: Serialization

    private val handshakeKryo: KryoExtra

    private val sendIdleStrategy: CoroutineIdleStrategy
    private val sendIdleStrategyHandshake: IdleStrategy

    /**
     * Crypto and signature management
     */
    internal val crypto: CryptoManagement

    private val shutdown = atomic(false)

    @Volatile
    private var shutdownWaiter: SuspendWaiter = SuspendWaiter()

    /**
     * Returns the storage used by this endpoint. This is the backing data structure for key/value pairs, and can be a database, file, etc
     *
     * Only one instance of these is created for an endpoint.
     */
    val storage: SettingsStore

    internal val rmiGlobalSupport = RmiManagerGlobal<CONNECTION>(logger, actionDispatch, config.serialization)

    init {
        require(!config.previouslyUsed) { "${type.simpleName} configuration cannot be reused!" }
        config.validate()

        runBlocking {
            // our default onError handler. All error messages go though this
            listenerManager.onError { throwable ->
                logger.error("Error processing events", throwable)
            }

            listenerManager.onError { connection, throwable ->
                logger.error("Error processing events for connection $connection", throwable)
            }
        }

        // serialization stuff
        serialization = config.serialization
        sendIdleStrategy = config.sendIdleStrategy
        sendIdleStrategyHandshake = sendIdleStrategy.cloneToNormal()

        handshakeKryo = serialization.initHandshakeKryo()

        // we have to be able to specify the property store
        storage = config.settingsStore.create(logger)

        crypto = CryptoManagement(logger, storage, type, config.enableRemoteSignatureValidation)

        // Only starts the media driver if we are NOT already running!
        try {
            AeronDriver.validateConfig(config, logger)
            aeronDriver = AeronDriver(config, type, logger)
        } catch (e: Exception) {
            listenerManager.notifyError(e)
            throw e
        }
    }

    internal fun initEndpointState() {
        shutdown.getAndSet(false)
        shutdownWaiter = SuspendWaiter()

        // Only starts the media driver if we are NOT already running!
        try {
            aeronDriver.start()
        } catch (e: Exception) {
            listenerManager.notifyError(e)
            throw e
        }
    }

    abstract fun newException(message: String, cause: Throwable? = null): Throwable

    // used internally to remove a connection
    internal fun removeConnection(connection: Connection) {
        @Suppress("UNCHECKED_CAST")
        removeConnection(connection as CONNECTION)
    }

    /**
     * Adds a custom connection to the server.
     *
     * This should only be used in situations where there can be DIFFERENT types of connections (such as a 'web-based' connection) and
     * you want *this* endpoint to manage listeners + message dispatch
     *
     * @param connection the connection to add
     */
    fun addConnection(connection: CONNECTION) {
        connections.add(connection)
    }

    /**
     * Removes a custom connection to the server.
     *
     * This should only be used in situations where there can be DIFFERENT types of connections (such as a 'web-based' connection) and
     * you want *this* endpoint to manage listeners + message dispatch
     *
     * @param connection the connection to remove
     */
    fun removeConnection(connection: CONNECTION) {
        connections.remove(connection)
    }

    /**
     * This method allows the connections used by the client/server to be subclassed (with custom implementations).
     *
     * As this is for the network stack, the new connection MUST subclass [Connection]
     *
     * The parameters are ALL NULL when getting the base class, as this instance is just thrown away.
     *
     * @return a new network connection
     */
    @Suppress("MemberVisibilityCanBePrivate")
    open fun newConnection(connectionParameters: ConnectionParams<CONNECTION>): CONNECTION {
        @Suppress("UNCHECKED_CAST")
        return Connection(connectionParameters) as CONNECTION
    }

    /**
     * Used for the client, because the client only has ONE ever support connection, and it allows us to create connection specific objects
     * from a "global" context
     */
    internal open fun getRmiConnectionSupport() : RmiManagerConnections<CONNECTION> {
        return RmiManagerConnections(logger, rmiGlobalSupport, serialization)
    }

    /**
     * Adds an IP+subnet rule that defines if that IP+subnet is allowed/denied connectivity to this server.
     *
     * If there are any IP+subnet added to this list - then ONLY those are permitted (all else are denied)
     *
     * If there is nothing added to this list - then ALL are permitted
     */
    fun filter(ipFilterRule: IpFilterRule) {
        actionDispatch.launch {
            listenerManager.filter(ipFilterRule)
        }
    }

    /**
     * Adds a function that will be called BEFORE a client/server "connects" with each other, and used to determine if a connection
     * should be allowed
     *
     * It is the responsibility of the custom filter to write the error, if there is one
     *
     * If the function returns TRUE, then the connection will continue to connect.
     * If the function returns FALSE, then the other end of the connection will
     *   receive a connection error
     *
     * For a server, this function will be called for ALL clients.
     */
    fun filter(function: suspend (CONNECTION) -> Boolean) {
        actionDispatch.launch {
            listenerManager.filter(function)
        }
    }

    /**
     * Adds a function that will be called when a client/server "connects" with each other
     *
     * For a server, this function will be called for ALL clients.
     */
    fun onConnect(function: suspend (CONNECTION) -> Unit) {
        actionDispatch.launch {
            listenerManager.onConnect(function)
        }
    }

    /**
     * Called when the remote end is no longer connected.
     *
     * Do not try to send messages! The connection will already be closed, resulting in an error if you attempt to do so.
     */
    fun onDisconnect(function: suspend (CONNECTION) -> Unit) {
        actionDispatch.launch {
            listenerManager.onDisconnect(function)
        }
    }

    /**
     * Called when there is an error for a specific connection
     *
     * The error is also sent to an error log before this method is called.
     */
    fun onError(function: (CONNECTION, Throwable) -> Unit) {
        actionDispatch.launch {
            listenerManager.onError(function)
        }
    }

    /**
     * Called when there is an error in general
     *
     * The error is also sent to an error log before this method is called.
     */
    fun onError(function: (Throwable) -> Unit) {
        actionDispatch.launch {
            listenerManager.onError(function)
        }
    }

    /**
     * Called when an object has been received from the remote end of the connection.
     *
     * This method should not block for long periods as other network activity will not be processed until it returns.
     */
    fun <Message : Any> onMessage(function: suspend (CONNECTION, Message) -> Unit) {
        actionDispatch.launch {
            listenerManager.onMessage(function)
        }
    }

    @Suppress("DuplicatedCode")
    // note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
    internal fun writeHandshakeMessage(publication: Publication, message: HandshakeMessage) {
        // The handshake sessionId IS NOT globally unique
        logger.trace {
            "[${publication.sessionId()}] send HS: $message"
        }

        try {
            // we are not thread-safe!
            val buffer = handshakeKryo.write(message)
            val objectSize = buffer.position()
            val internalBuffer = buffer.internalBuffer

            var result: Long
            while (true) {
                result = publication.offer(internalBuffer, 0, objectSize)
                if (result >= 0) {
                    // success!
                    return
                }

                /**
                 * The publication is not connected to a subscriber, this can be an intermittent state as subscribers come and go.
                 *  val NOT_CONNECTED: Long = -1
                 *
                 * The offer failed due to back pressure from the subscribers preventing further transmission.
                 *  val BACK_PRESSURED: Long = -2
                 *
                 * The offer failed due to an administration action and should be retried.
                 * The action is an operation such as log rotation which is likely to have succeeded by the next retry attempt.
                 *  val ADMIN_ACTION: Long = -3
                 */
                if (result >= Publication.ADMIN_ACTION) {
                    // we should retry.
                    sendIdleStrategyHandshake.idle()
                    continue
                }

                // more critical error sending the message. we shouldn't retry or anything.
                val exception = newException("[${publication.sessionId()}] Error sending handshake message. $message (${errorCodeName(result)})")
                ListenerManager.cleanStackTraceInternal(exception)
                listenerManager.notifyError(exception)
                return
            }
        } catch (e: Exception) {
            val exception = newException("[${publication.sessionId()}] Error serializing handshake message $message", e)
            ListenerManager.cleanStackTrace(exception, 2) // 2 because we do not want to see the stack for the abstract `newException`
            listenerManager.notifyError(exception)
        } finally {
            sendIdleStrategyHandshake.reset()
        }
    }

    /**
     * @param buffer The buffer
     * @param offset The offset from the start of the buffer
     * @param length The number of bytes to extract
     * @param header The aeron header information
     *
     * @return the message
     */
    // note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
    internal fun readHandshakeMessage(buffer: DirectBuffer, offset: Int, length: Int, header: Header): Any? {
        try {
            val message = handshakeKryo.read(buffer, offset, length)

            logger.trace {
                "[${header.sessionId()}] received HS: $message"
            }

            return message
        } catch (e: Exception) {
            // The handshake sessionId IS NOT globally unique
            val sessionId = header.sessionId()

            val exception = newException("[${sessionId}] Error de-serializing message", e)
            ListenerManager.cleanStackTrace(exception, 2) // 2 because we do not want to see the stack for the abstract `newException`
            listenerManager.notifyError(exception)

            logger.error("Error de-serializing message on connection ${header.sessionId()}!", e)
            return null
        }
    }

    /**
     * read the message from the aeron buffer
     *
     * @param buffer The buffer
     * @param offset The offset from the start of the buffer
     * @param length The number of bytes to extract
     * @param header The aeron header information
     * @param connection The connection this message happened on
     */
    internal fun processMessage(buffer: DirectBuffer, offset: Int, length: Int, header: Header, connection: Connection) {
        // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!
        @Suppress("UNCHECKED_CAST")
        connection as CONNECTION

        val message: Any?
        try {
            message = serialization.readMessage(buffer, offset, length, connection)
            logger.trace {
                "[${header.sessionId()}] received: $message"
            }
        } catch (e: Exception) {
            // The handshake sessionId IS NOT globally unique
            val sessionId = header.sessionId()

            val exception = newException("[${sessionId}] Error de-serializing message", e)
            ListenerManager.cleanStackTrace(exception, 2) // 2 because we do not want to see the stack for the abstract `newException`
            listenerManager.notifyError(connection, exception)

            return // don't do anything!
        }


        when (message) {
            is PingMessage -> {
                // the ping listener (internal use only!)
                actionDispatch.launch {
                    pingManager.manage(this@EndPoint, connection, message, logger)
                }
            }

            // small problem... If we expect IN ORDER messages (ie: setting a value, then later reading the value), multiple threads don't work.
            // this is worked around by having RMI always return (unless async), even with a null value, so the CALLING side of RMI will always
            // go in "lock step"
            is RmiMessage -> {
                actionDispatch.launch {
                    // if we are an RMI message/registration, we have very specific, defined behavior.
                    // We do not use the "normal" listener callback pattern because this require special functionality
                    rmiGlobalSupport.manage(this@EndPoint, connection, message, logger)
                }
            }
            is Any -> {
                actionDispatch.launch {
                    @Suppress("UNCHECKED_CAST")
                    var hasListeners = listenerManager.notifyOnMessage(connection, message)

                    // each connection registers, and is polled INDEPENDENTLY for messages.
                    hasListeners = hasListeners or connection.notifyOnMessage(message)

                    if (!hasListeners) {
                        val exception = MessageNotRegisteredException("No message callbacks found for ${message::class.java.simpleName}")
                        ListenerManager.cleanStackTrace(exception)
                        listenerManager.notifyError(connection, exception)
                    }
                }
            }
            else -> {
                // do nothing, there were problems with the message
                val exception = if (message != null) {
                    MessageNotRegisteredException("No message callbacks found for ${message::class.java.simpleName}")
                } else {
                    MessageNotRegisteredException("Unknown message received!!")
                }

                ListenerManager.cleanStackTrace(exception)
                listenerManager.notifyError(exception)
            }
        }
    }

    // NOTE: this **MUST** stay on the same co-routine that calls "send". This cannot be re-dispatched onto a different coroutine!
    @Suppress("DuplicatedCode")
    internal suspend fun send(message: Any, publication: Publication, connection: Connection) {
        // The handshake sessionId IS NOT globally unique
        logger.trace {
            "[${publication.sessionId()}] send: $message"
        }

        // since ANY thread can call 'send', we have to take kryo instances in a safe way
        val kryo: KryoExtra = serialization.takeKryo()
        try {
            val buffer = kryo.write(connection, message)
            val objectSize = buffer.position()
            val internalBuffer = buffer.internalBuffer

            var result: Long
            while (true) {
                result = publication.offer(internalBuffer, 0, objectSize)
                if (result >= 0) {
                    // success!
                    return
                }

                /**
                 * The publication is not connected to a subscriber, this can be an intermittent state as subscribers come and go.
                 *  val NOT_CONNECTED: Long = -1
                 *
                 * The offer failed due to back pressure from the subscribers preventing further transmission.
                 *  val BACK_PRESSURED: Long = -2
                 *
                 * The offer failed due to an administration action and should be retried.
                 * The action is an operation such as log rotation which is likely to have succeeded by the next retry attempt.
                 *  val ADMIN_ACTION: Long = -3
                 */
                if (result >= Publication.ADMIN_ACTION) {
                    // we should retry, BUT we want suspend ANYONE ELSE trying to write at the same time!
                    sendIdleStrategy.idle()
                    continue
                }

                // more critical error sending the message. we shouldn't retry or anything.
                val errorMessage = "[${publication.sessionId()}] Error sending message. $message (${errorCodeName(result)})"

                // either client or server. No other choices. We create an exception, because it's more useful!
                val exception = newException(errorMessage)

                // 2 because we do not want to see the stack for the abstract `newException`
                // 2 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
                // where we see who is calling "send()"
                ListenerManager.cleanStackTrace(exception, 4)

                @Suppress("UNCHECKED_CAST")
                listenerManager.notifyError(connection as CONNECTION, exception)

                return
            }
        } catch (e: Exception) {
            logger.error("[${publication.sessionId()}] Error serializing message $message", e)
        } finally {
            sendIdleStrategy.reset()
            serialization.returnKryo(kryo)
        }
    }


    /**
     * @return the error code text for the specified number
     */
    private fun errorCodeName(result: Long): String {
        return when (result) {
            // The publication is not connected to a subscriber, this can be an intermittent state as subscribers come and go.
            Publication.NOT_CONNECTED -> "Not connected"

            // The offer failed due to back pressure from the subscribers preventing further transmission.
            Publication.BACK_PRESSURED -> "Back pressured"

            // The action is an operation such as log rotation which is likely to have succeeded by the next retry attempt.
            Publication.ADMIN_ACTION -> "Administrative action"

            // The Publication has been closed and should no longer be used.
            Publication.CLOSED -> "Publication is closed"

            // If this happens then the publication should be closed and a new one added. To make it less likely to happen then increase the term buffer length.
            Publication.MAX_POSITION_EXCEEDED -> "Maximum term position exceeded"

            else -> throw IllegalStateException("Unknown error code: $result")
        }
    }

    override fun toString(): String {
        return "EndPoint [${type.simpleName}]"
    }

    override fun hashCode(): Int {
        val prime = 31
        var result = 1
        result = prime * result + (crypto.hashCode())
        return result
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

        other as EndPoint<*>
        return crypto == other.crypto
    }

    /**
     * @return true if this endpoint has been closed
     */
    fun isShutdown(): Boolean {
        return shutdown.value
    }

    /**
     * Waits for this endpoint to be closed
     */
    suspend fun waitForClose() {
        shutdownWaiter.doWait()
    }

    /**
     * Checks to see if an endpoint (using the current configuration) is running.
     *
     * @return true if the media driver is active and running
     */
    fun isRunning(): Boolean {
        return aeronDriver.isRunning()
    }

    /**
     * Checks to see if an endpoint (using the specified configuration) is running.
     *
     * @return true if the media driver is active and running
     */
    fun isRunning(context: MediaDriver.Context): Boolean {
        // if the media driver is running, it will be a quick connection. Usually 100ms or so
        return context.isDriverActive(1_000) { }
    }

    final override fun close() {
        if (shutdown.compareAndSet(expect = false, update = true)) {
            logger.info { "Shutting down..." }

            runBlocking {
                aeronDriver.close()

                connections.forEach {
                    it.close()
                }

                // the storage is closed via this as well.
                storage.close()

                // Connections are closed first, because we want to make sure that no RMI messages can be received
                // when we close the RMI support objects (in which case, weird - but harmless - errors show up)
                rmiGlobalSupport.close()
            }

            close0()

            // if we are waiting for shutdown, cancel the waiting thread (since we have shutdown now)
            shutdownWaiter.cancel()
        }
    }

    internal open fun close0() {}
}
