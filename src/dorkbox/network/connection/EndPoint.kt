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

import dorkbox.collections.ConcurrentIterator
import dorkbox.netUtil.IPv4
import dorkbox.netUtil.IPv6
import dorkbox.network.Client
import dorkbox.network.Configuration
import dorkbox.network.Server
import dorkbox.network.ServerConfiguration
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.BacklogStat
import dorkbox.network.aeron.EventPoller
import dorkbox.network.connection.streaming.StreamingControl
import dorkbox.network.connection.streaming.StreamingData
import dorkbox.network.connection.streaming.StreamingManager
import dorkbox.network.exceptions.ClientException
import dorkbox.network.exceptions.ServerException
import dorkbox.network.handshake.HandshakeMessage
import dorkbox.network.ipFilter.IpFilterRule
import dorkbox.network.ping.Ping
import dorkbox.network.ping.PingManager
import dorkbox.network.rmi.ResponseManager
import dorkbox.network.rmi.RmiManagerConnections
import dorkbox.network.rmi.RmiManagerGlobal
import dorkbox.network.rmi.messages.RmiMessage
import dorkbox.network.serialization.KryoExtra
import dorkbox.network.serialization.Serialization
import dorkbox.network.serialization.SettingsStore
import io.aeron.Publication
import io.aeron.logbuffer.Header
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KLogger
import mu.KotlinLogging
import org.agrona.DirectBuffer
import org.agrona.MutableDirectBuffer
import org.agrona.concurrent.IdleStrategy
import java.net.InetAddress
import java.util.concurrent.*
import java.util.concurrent.locks.*

// If TCP and UDP both fill the pipe, THERE WILL BE FRAGMENTATION and dropped UDP packets!
// it results in severe UDP packet loss and contention.
//
// http://www.isoc.org/INET97/proceedings/F3/F3_1.HTM
// also, a Google search on just "INET97/proceedings/F3/F3_1.HTM" turns up interesting problems.
// Usually it's with ISPs.
/**
 * represents the base of a client/server end point for interacting with aeron
 *
 * @param type this is either "Client" or "Server", depending on who is creating this endpoint.
 * @param config these are the specific connection options
 * @param connectionFunc allows for custom connection implementations defined as a unit function
 *
 *  @throws SecurityException if unable to initialize/generate ECC keys
*/
abstract class EndPoint<CONNECTION : Connection>
internal constructor(val type: Class<*>,
                     internal val config: Configuration,
                     internal val connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION,
                     loggerName: String)
             : AutoCloseable {

    protected constructor(config: Configuration,
                          connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION,
                          loggerName: String)
            : this(Client::class.java, config, connectionFunc, loggerName)

    protected constructor(config: ServerConfiguration,
                          connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION,
                          loggerName: String)
            : this(Server::class.java, config, connectionFunc, loggerName)

    companion object {
        // connections are extremely difficult to diagnose when the connection timeout is short
        internal const val DEBUG_CONNECTIONS = false

        internal const val IPC_NAME = "IPC"

        internal val networkEventPoller = EventPoller()
        internal val responseManager = ResponseManager()

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

        fun isLocalhost(ipAddress: String): Boolean {
            return when (ipAddress.lowercase()) {
                "loopback", "localhost", "lo", "127.0.0.1", "::1" -> true
                else -> false
            }
        }
        fun isWildcard(ipAddress: String): Boolean {
            return when (ipAddress) {
                // this is the "wildcard" address. Windows has problems with this.
                "0", "::", "0.0.0.0", "*" -> true
                else -> false
            }
        }

        fun isWildcard(ipAddress: InetAddress): Boolean {
            return when (ipAddress) {
                // this is the "wildcard" address. Windows has problems with this.
                IPv4.WILDCARD, IPv6.WILDCARD -> true
                else -> false
            }
        }

        fun getWildcard(ipAddress: InetAddress, ipAddressString: String, shouldBeIpv4: Boolean): String {
            return if (isWildcard(ipAddress)) {
                if (shouldBeIpv4) {
                    IPv4.WILDCARD_STRING
                } else {
                    IPv6.WILDCARD_STRING
                }
            } else {
                ipAddressString
            }
        }

        fun formatCommonAddress(ipAddress: String, isIpv4: Boolean, elseAction: () -> InetAddress?): InetAddress? {
            return if (isLocalhost(ipAddress)) {
                if (isIpv4) { IPv4.LOCALHOST } else { IPv6.LOCALHOST }
            } else if (isWildcard(ipAddress)) {
                if (isIpv4) { IPv4.WILDCARD } else { IPv6.WILDCARD }
            } else if (IPv4.isValid(ipAddress)) {
                IPv4.toAddress(ipAddress)!!
            } else if (IPv6.isValid(ipAddress)) {
                IPv6.toAddress(ipAddress)!!
            } else {
                elseAction()
            }
        }

        fun formatCommonAddressString(ipAddress: String, isIpv4: Boolean, elseAction: () -> String = { ipAddress }): String {
            return if (isLocalhost(ipAddress)) {
                if (isIpv4) { IPv4.LOCALHOST_STRING } else { IPv6.LOCALHOST_STRING }
            } else if (isWildcard(ipAddress)) {
                if (isIpv4) { IPv4.WILDCARD_STRING } else { IPv6.WILDCARD_STRING }
            } else if (IPv4.isValid(ipAddress)) {
                ipAddress
            } else if (IPv6.isValid(ipAddress)) {
                ipAddress
            } else {
                elseAction()
            }
        }
    }

    val logger: KLogger = KotlinLogging.logger(loggerName)

    // this is rather silly, BUT if there are more complex errors WITH the coroutine that occur, a regular try/catch WILL NOT catch it.
    // ADDITIONALLY, an error handler is ONLY effective at the first, top-level `launch`. IT WILL NOT WORK ANY OTHER WAY.
    private val errorHandler = CoroutineExceptionHandler { _, exception ->
        logger.error(exception) { "Uncaught Coroutine Error!" }
    }

    internal val eventDispatch = EventDispatcher()
    private val messageDispatch = CoroutineScope(config.messageDispatch)

    internal val listenerManager = ListenerManager<CONNECTION>(logger)

    val connections = ConcurrentIterator<CONNECTION>()

    internal val aeronDriver: AeronDriver

    /**
     * Returns the serialization wrapper if there is an object type that needs to be added in addition to the basic types.
     */
    val serialization: Serialization<CONNECTION>

    // These are GLOBAL, single threaded only kryo instances.
    // The readKryo WILL RE-CONFIGURED during the client handshake! (it is all the same thread, so object visibility is not a problem)
    internal var readKryo: KryoExtra<CONNECTION>
    internal var streamingReadKryo: KryoExtra<CONNECTION>
    private val handshakeReadKryo: KryoExtra<CONNECTION>
    private val handshakeWriteKryo: KryoExtra<CONNECTION>
    private val handshakeSendIdleStrategy: IdleStrategy

    /**
     * Crypto and signature management
     */
    internal val crypto: CryptoManagement

    private val shutdown = atomic(false)

    @Volatile
    private var shutdownLatch = CountDownLatch(1)

    /**
     * Returns the storage used by this endpoint. This is the backing data structure for key/value pairs, and can be a database, file, etc
     *
     * Only one instance of these is created for an endpoint.
     */
    val storage: SettingsStore

    internal val rmiGlobalSupport = RmiManagerGlobal<CONNECTION>(logger)
    internal val rmiConnectionSupport: RmiManagerConnections<CONNECTION>

    private val streamingManager = StreamingManager<CONNECTION>(logger, messageDispatch)

    private val pingManager = PingManager<CONNECTION>()





    init {
        require(eventDispatch.isActive) { "The Event Dispatch is no longer active. It has been shutdown" }
        require(messageDispatch.isActive) { "The Message Dispatch is no longer active. It has been shutdown" }


        if (DEBUG_CONNECTIONS) {
            logger.error { "DEBUG_CONNECTIONS is enabled. This should not happen in release!" }
        }

        config.validate() // this happens more than once! (this is ok)

        // there are threading issues if there are client(s) and server's within the same JVM, where we have thread starvation
        networkEventPoller.configure(config)


        // serialization stuff
        @Suppress("UNCHECKED_CAST")
        serialization = config.serialization as Serialization<CONNECTION>

        // we are done with initial configuration, now finish serialization
        val kryo = serialization.initGlobalKryo()
        serialization.finishInit(type, kryo)


        handshakeSendIdleStrategy = config.sendIdleStrategy.cloneToNormal()

        // the initial kryo created for serialization is reused as the read kryo
        if (type == Server::class.java) {
            readKryo = serialization.initKryo(kryo)
            streamingReadKryo = serialization.initKryo()
        } else {
            // these will be reassigned by the client Connect method!
            readKryo = kryo
            streamingReadKryo = kryo
        }

        handshakeReadKryo = serialization.newHandshakeKryo()
        handshakeWriteKryo = serialization.newHandshakeKryo()

        // we have to be able to specify the property store
        storage = SettingsStore(config.settingsStore, logger)
        crypto = CryptoManagement(logger, storage, type, config.enableRemoteSignatureValidation)

        // Only starts the media driver if we are NOT already running!
        try {
            aeronDriver = AeronDriver.getDriver(config, logger)
        } catch (e: Exception) {
            logger.error("Error initialize endpoint", e)
            throw e
        }

        rmiConnectionSupport = if (type.javaClass == Server::class.java) {
            // server cannot "get" global RMI objects, only the client can
            RmiManagerConnections(logger, responseManager, listenerManager, serialization) { _, _, _ ->
                throw IllegalAccessException("Global RMI access is only possible from a Client connection!")
            }
        } else {
            RmiManagerConnections(logger, responseManager, listenerManager, serialization) { connection, objectId, interfaceClass ->
                return@RmiManagerConnections rmiGlobalSupport.getGlobalRemoteObject(connection, objectId, interfaceClass)
            }
        }
    }

    /**
     * Only starts the media driver if we are NOT already running!
     *
     * @throws Exception if there is a problem starting the media driver
     */
    fun startDriver() {
        aeronDriver.start(logger)
        shutdown.value = false
    }

    /**
     * Stops the network driver.
     *
     * @param forceTerminate if true, then there is no caution when restarting the Aeron driver, and any other process on the machine using
     * the same driver will probably crash (unless they have been appropriately stopped). If false (the default), then the Aeron driver is
     * only stopped if it is safe to do so
     */
    fun stopDriver(forceTerminate: Boolean = false) {
        if (forceTerminate) {
            aeronDriver.close()
        } else {
            aeronDriver.closeIfSingle()
        }
    }

    abstract fun newException(message: String, cause: Throwable? = null): Throwable

    // used internally to remove a connection. Will also remove all proxy objects
    @Suppress("UNCHECKED_CAST")
    internal fun removeConnection(connection: Connection) {
        connection as CONNECTION

        rmiConnectionSupport.close(connection)
        removeConnection(connection)
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
     * Adds an IP+subnet rule that defines if that IP+subnet is allowed/denied connectivity to this server.
     *
     * By default, if there are no filter rules, then all connections are allowed to connect
     * If there are filter rules - then ONLY connections for the filter that returns true are allowed to connect (all else are denied)
     *
     * If ANY filter rule that is applied returns true, then the connection is permitted
     *
     * This function will be called for **only** network clients (IPC client are excluded)
     */
    fun filter(ipFilterRule: IpFilterRule) {
        eventDispatch.launch {
            listenerManager.filter(ipFilterRule)
        }
    }

    /**
     * Adds a function that will be called BEFORE a client/server "connects" with each other, and used to determine if a connection
     * should be allowed
     *
     * By default, if there are no filter rules, then all connections are allowed to connect
     * If there are filter rules - then ONLY connections for the filter that returns true are allowed to connect (all else are denied)
     *
     * It is the responsibility of the custom filter to write the error, if there is one
     *
     * If the function returns TRUE, then the connection will continue to connect.
     * If the function returns FALSE, then the other end of the connection will
     *   receive a connection error
     *
     *
     * If ANY filter rule that is applied returns true, then the connection is permitted
     *
     * This function will be called for **only** network clients (IPC client are excluded)
     */
    fun filter(function: CONNECTION.() -> Boolean) {
        eventDispatch.launch {
            listenerManager.filter(function)
        }
    }

    /**
     * Adds a function that will be called when a client/server connection is FIRST initialized, but before it's
     * connected to the remote endpoint.
     *
     * NOTE: This callback is executed IN-LINE with network IO, so one must be very careful about what is executed.
     *
     * For a server, this function will be called for ALL client connections.
     */
    fun onInit(function: suspend CONNECTION.() -> Unit) {
        eventDispatch.launch {
            listenerManager.onInit(function)
        }
    }

    /**
     * Adds a function that will be called when a client/server connection first establishes a connection with the remote end.
     * 'onInit()' callbacks will execute for both the client and server before `onConnect()` will execute will "connects" with each other
     */
    fun onConnect(function: suspend CONNECTION.() -> Unit) {
        eventDispatch.launch {
            listenerManager.onConnect(function)
        }
    }

    /**
     * Called when the remote end is no longer connected.
     *
     * Do not try to send messages! The connection will already be closed, resulting in an error if you attempt to do so.
     */
    fun onDisconnect(function: suspend CONNECTION.() -> Unit) {
        eventDispatch.launch {
            listenerManager.onDisconnect(function)
        }
    }

    /**
     * Called when there is an error for a specific connection
     *
     * The error is also sent to an error log before this method is called.
     */
    fun onError(function: CONNECTION.(Throwable) -> Unit) {
        eventDispatch.launch {
            listenerManager.onError(function)
        }
    }

    /**
     * Called when there is a global error (and error that is not specific to a connection)
     *
     * The error is also sent to an error log before this method is called.
     */
    fun onErrorGlobal(function: (Throwable) -> Unit) {
        eventDispatch.launch {
            listenerManager.onError(function)
        }
    }

    /**
     * Called when an object has been received from the remote end of the connection.
     *
     * This method should not block for long periods as other network activity will not be processed until it returns.
     */
    fun <Message : Any> onMessage(function: suspend CONNECTION.(Message) -> Unit) {
        eventDispatch.launch {
            listenerManager.onMessage(function)
        }
    }

    /**
     * Sends a "ping" packet to measure **ROUND TRIP** time to the remote connection.
     *
     * @return true if the message was successfully sent by aeron
     */
    internal suspend fun ping(connection: Connection, pingTimeoutMs: Int, function: suspend Ping.() -> Unit): Boolean {
        return pingManager.ping(connection, pingTimeoutMs, eventDispatch, responseManager, logger, function)
    }

    /**
     * NOTE: this **MUST** stay on the same co-routine that calls "send". This cannot be re-dispatched onto a different coroutine!
     *       CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
     *       Server -> will be network polling thread
     *       Client -> will be thread that calls `connect()`
     *
     * @return true if the message was successfully sent by aeron
     */
    @Suppress("DuplicatedCode")
    internal fun writeHandshakeMessage(publication: Publication, aeronLogInfo: String, message: HandshakeMessage) {
        // The handshake sessionId IS NOT globally unique
        logger.trace { "[$aeronLogInfo] (${message.connectKey}) send HS: $message" }

        try {
            val buffer = handshakeWriteKryo.write(message)
            val objectSize = buffer.position()
            val internalBuffer = buffer.internalBuffer

            var timeoutInNanos = 0L
            var startTime = 0L

            var result: Long
            while (true) {
                result = publication.offer(internalBuffer, 0, objectSize)
                if (result >= 0) {
                    // success!
                    return
                }

                /**
                 * Since the publication is not connected, we weren't able to send data to the remote endpoint.
                 *
                 * According to Aeron Docs, Pubs and Subs can "come and go", whatever that means. We just want to make sure that we
                 * don't "loop forever" if a publication is ACTUALLY closed, like on purpose.
                 */
                if (result == Publication.NOT_CONNECTED) {
                    if (timeoutInNanos == 0L) {
                        timeoutInNanos = (aeronDriver.getLingerNs() * 1.2).toLong() // close enough. Just needs to be slightly longer
                        startTime = System.nanoTime()
                    }

                    if (System.nanoTime() - startTime < timeoutInNanos) {
                        // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
                        //  publication of any state to other threads and not be long running or re-entrant with the client.
                        // on close, the publication CAN linger (in case a client goes away, and then comes back)
                        // AERON_PUBLICATION_LINGER_TIMEOUT, 5s by default (this can also be set as a URI param)

                        //fixme: this should be the linger timeout, not a retry count!

                        // we should retry.
                        handshakeSendIdleStrategy.idle()
                        continue
                    } else if (!publication.isClosed) {
                        // more critical error sending the message. we shouldn't retry or anything.
                        // this exception will be a ClientException or a ServerException
                        val exception = newException(
                            "[$aeronLogInfo] Error sending message. (Connection in non-connected state longer than linger timeout. ${errorCodeName(result)})"
                        )
                        ListenerManager.cleanStackTraceInternal(exception)
                        listenerManager.notifyError(exception)
                        throw exception
                    }
                    else {
                        // publication was actually closed, so no bother throwing an error
                        return
                    }
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
                    handshakeSendIdleStrategy.idle()
                    continue
                }

                // more critical error sending the message. we shouldn't retry or anything.
                // this exception will be a ClientException or a ServerException
                val exception = newException("[$aeronLogInfo] Error sending handshake message. $message (${errorCodeName(result)})")
                ListenerManager.cleanStackTraceInternal(exception)
                listenerManager.notifyError(exception)
                throw exception
            }
        } catch (e: Exception) {
            if (e is ClientException || e is ServerException) {
                throw e
            } else {
                val exception = newException("[$aeronLogInfo] Error serializing handshake message $message", e)
                ListenerManager.cleanStackTrace(exception, 2) // 2 because we do not want to see the stack for the abstract `newException`
                listenerManager.notifyError(exception)
                throw exception
            }
        } finally {
            handshakeSendIdleStrategy.reset()
        }
    }

    /**
     * NOTE: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
     *
     * @param buffer The buffer
     * @param offset The offset from the start of the buffer
     * @param length The number of bytes to extract
     *
     * @return the message
     */
    internal fun readHandshakeMessage(buffer: DirectBuffer, offset: Int, length: Int, aeronLogInfo: String): Any? {
        return try {
            // NOTE: This ABSOLUTELY MUST be done on the same thread! This cannot be done on a new one, because the buffer could change!
            val message = handshakeReadKryo.read(buffer, offset, length) as HandshakeMessage

            logger.trace { "[$aeronLogInfo] (${message.connectKey}) received HS: $message" }

            message
        } catch (e: Exception) {
            // The handshake sessionId IS NOT globally unique
            logger.error(e) { "[$aeronLogInfo] Error de-serializing handshake message!!" }
            listenerManager.notifyError(e)
            null
        }
    }

    /**
     * This is designed to permit modifying/overriding how data is processed on the network.
     *
     * This will split a message if it's too large to send in a single network message.
     *
     * NOTE: we use exclusive publications, and they are not thread safe/concurrent!
     *
     * THIS IS CALLED FROM WITHIN A MUTEX!!
     *
     * @return true if the message was successfully sent by aeron, false otherwise. Exceptions are caught and NOT rethrown!
     */
    open fun write(
        writeKryo: KryoExtra<Connection>,
        tempWriteKryo: KryoExtra<Connection>,
        message: Any,
        publication: Publication,
        sendIdleStrategy: IdleStrategy,
        connection: Connection
    ): Boolean {
        // NOTE: A kryo instance CANNOT be re-used until after it's buffer is flushed to the network!

        // since ANY thread can call 'send', we have to take kryo instances in a safe way
        // the maximum size that this buffer can be is:
        //   ExpandableDirectByteBuffer.MAX_BUFFER_LENGTH = 1073741824
        val buffer = writeKryo.write(connection, message)
        val objectSize = buffer.position()
        val internalBuffer = buffer.internalBuffer


        // one small problem! What if the message is too big to send all at once?
        val maxMessageLength = publication.maxMessageLength()
        return if (objectSize >= maxMessageLength) {
            // we must split up the message! It's too large for Aeron to manage.
            // this will split up the message, construct the necessary control message and state, then CALL the sendData
            // method directly for each subsequent message.
            streamingManager.send(
                publication = publication,
                internalBuffer = internalBuffer,
                objectSize = objectSize,
                endPoint = this,
                tempWriteKryo = tempWriteKryo,
                sendIdleStrategy = sendIdleStrategy,
                connection = connection
            )
        } else {
            dataSend(publication, internalBuffer, 0, objectSize, sendIdleStrategy, connection)
        }
    }

    /**
     * This is designed to permit modifying/overriding how data is processed on the network.
     *
     * This will NOT split a message if it's too large. Aeron will just crash. This is used by the exclusively by the streaming manager.
     *
     * NOTE: we use exclusive publications, and they are not thread safe/concurrent!
     *
     * @return true if the message was successfully sent by aeron, false otherwise. Exceptions are caught and NOT rethrown!
     */
    open fun writeUnsafe(writeKryo: KryoExtra<Connection>, message: Any, publication: Publication, sendIdleStrategy: IdleStrategy, connection: Connection): Boolean {
        // NOTE: A kryo instance CANNOT be re-used until after it's buffer is flushed to the network!

        // since ANY thread can call 'send', we have to take kryo instances in a safe way
        // the maximum size that this buffer can be is:
        //   ExpandableDirectByteBuffer.MAX_BUFFER_LENGTH = 1073741824
        val buffer = writeKryo.write(connection, message)
        val objectSize = buffer.position()
        val internalBuffer = buffer.internalBuffer

        return dataSend(publication, internalBuffer, 0, objectSize, sendIdleStrategy, connection)
    }

    /**
     * Processes a message that has been read off the network.
     *
     * This is written in a way that permits modifying/overriding how data is processed on the network
     *
     * There are custom objects that are used (Ping, RmiMessages, Streaming object, etc.) are manage and use custom object types. These types
     * must be EXPLICITLY used by the implementation, and if a custom message processor is to be used (ie: a state machine) you must
     * guarantee that Ping, RMI, Streaming object, etc. are not used (as it would not function without this custom
     */
    open fun processMessage(message: Any?, connection: CONNECTION) {
        // the REPEATED usage of wrapping methods below is because Streaming messages have to intercept data BEFORE it goes to a coroutine
        when (message) {
            is Ping -> {
                // NOTE: This MUST be on a new co-routine
                messageDispatch.launch {
                    try {
                        pingManager.manage(connection, responseManager, message, logger)
                    } catch (e: Exception) {
                        logger.error("Error processing message", e)
                        listenerManager.notifyError(connection, e)
                    }
                }
            }

            // small problem... If we expect IN ORDER messages (ie: setting a value, then later reading the value), multiple threads don't work.
            // this is worked around by having RMI always return (unless async), even with a null value, so the CALLING side of RMI will always
            // go in "lock step"
            is RmiMessage -> {
                // if we are an RMI message/registration, we have very specific, defined behavior.
                // We do not use the "normal" listener callback pattern because this requires special functionality
                // NOTE: This MUST be on a new co-routine
                messageDispatch.launch {
                    try {
                        rmiGlobalSupport.processMessage(serialization, connection, message, rmiConnectionSupport, responseManager, logger)
                    } catch (e: Exception) {
                        logger.error("Error processing message", e)
                        listenerManager.notifyError(connection, e)
                    }
                }
            }


            // streaming/chunked message. This is used when the published data is too large for a single Aeron message.
            // TECHNICALLY, we could arbitrarily increase the size of the permitted Aeron message, however this doesn't let us
            // send arbitrarily large pieces of data (gigs in size, potentially).
            // This will recursively call into this method for each of the unwrapped chunks of data.
            is StreamingControl -> {
                streamingManager.processControlMessage(message, readKryo,this@EndPoint, connection)
            }
            is StreamingData -> {
                // NOTE: this will read extra data from the kryo input as necessary (which is why it's not on action dispatch)!
                val rawInput = readKryo.readerBuffer
                val dataLength = rawInput.readVarInt(true)
                message.payload = rawInput.readBytes(dataLength)


                // NOTE: This MUST NOT be on a new co-routine. It must be on the same thread!
                try {
                    streamingManager.processDataMessage(message, this@EndPoint, connection)
                } catch (e: Exception) {
                    logger.error("Error processing StreamingMessage", e)
                    listenerManager.notifyError(connection, e)
                }
            }


            is Any -> {
                // NOTE: This MUST be on a new co-routine
                messageDispatch.launch {
                    try {
                        var hasListeners = listenerManager.notifyOnMessage(connection, message)

                        // each connection registers, and is polled INDEPENDENTLY for messages.
                        hasListeners = hasListeners or connection.notifyOnMessage(message)

                        if (!hasListeners) {
                            logger.error("No message callbacks found for ${message::class.java.name}")
                        }
                    } catch (e: Exception) {
                        logger.error("Error processing message ${message::class.java.name}", e)
                        listenerManager.notifyError(connection, e)
                    }
                }
            }

            else -> {
                logger.error("Unknown message received!!")
            }
        }
    }


    /**
     * reads the message from the aeron buffer and figures out how to process it
     *
     *
     * @param buffer The buffer
     * @param offset The offset from the start of the buffer
     * @param length The number of bytes to extract
     * @param header The aeron header information
     * @param connection The connection this message happened on
     */
    internal fun dataReceive(buffer: DirectBuffer, offset: Int, length: Int, header: Header, connection: Connection) {
        // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!
        @Suppress("UNCHECKED_CAST")
        connection as CONNECTION

        try {
            // NOTE: This ABSOLUTELY MUST be done on the same thread! This cannot be done on a new one, because the buffer could change!
            val message =  readKryo.read(buffer, offset, length, connection)
            logger.trace { "[${header.sessionId()}] received: ${message?.javaClass?.simpleName} $message" }
            processMessage(message, connection)
        } catch (e: Exception) {
            // The handshake sessionId IS NOT globally unique
            logger.error("[${header.sessionId()}] Error de-serializing message", e)
            listenerManager.notifyError(connection, e)
        }
    }

    /**
     * NOTE: we use exclusive publications, and they are not thread safe/concurrent!
     *
     * the actual bits that send data on the network.
     *
     * @param publication the connection specific publication
     * @param internalBuffer the internal buffer that will be copied to the Aeron network driver
     * @param offset the offset in the internal buffer at which to start copying bytes
     * @param objectSize the number of bytes to copy (starting at the offset)
     * @param connection the connection object
     * @return true if the message was successfully sent by aeron, false otherwise. Exceptions are caught and NOT rethrown!
     */
    internal fun dataSend(
        publication: Publication,
        internalBuffer: MutableDirectBuffer,
        offset: Int,
        objectSize: Int,
        sendIdleStrategy: IdleStrategy,
        connection: Connection
    ): Boolean {
        var timeoutInNanos = 0L
        var startTime = 0L

        var result: Long
        while (true) {
            // we use exclusive publications, and they are not thread safe/concurrent!
            result = publication.offer(internalBuffer, offset, objectSize)
            if (result >= 0) {
                // success!
                return true
            }

            /**
             * Since the publication is not connected, we weren't able to send data to the remote endpoint.
             *
             * According to Aeron Docs, Pubs and Subs can "come and go", whatever that means. We just want to make sure that we
             * don't "loop forever" if a publication is ACTUALLY closed, like on purpose.
             */
            if (result == Publication.NOT_CONNECTED) {
                if (timeoutInNanos == 0L) {
                    timeoutInNanos = (aeronDriver.getLingerNs() * 1.2).toLong() // close enough. Just needs to be slightly longer
                    startTime = System.nanoTime()
                }

                if (System.nanoTime() - startTime < timeoutInNanos) {
                    // we should retry.
                    sendIdleStrategy.idle()
                    continue
                } else if (!publication.isClosed) {
                    // more critical error sending the message. we shouldn't retry or anything.
                    val errorMessage = "[${publication.sessionId()}] Error sending message. (Connection in non-connected state longer than linger timeout. ${errorCodeName(result)})"

                    // either client or server. No other choices. We create an exception, because it's more useful!
                    val exception = newException(errorMessage)

                    // +2 because we do not want to see the stack for the abstract `newException`
                    // +3 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
                    // where we see who is calling "send()"
                    ListenerManager.cleanStackTrace(exception, 5)
                    return false
                } else {
                    // publication was actually closed, so no bother throwing an error
                    return false
                }
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
                // we should retry, BUT we want to suspend ANYONE ELSE trying to write at the same time!
                sendIdleStrategy.idle()
                continue
            }


            if (result == Publication.CLOSED && connection.isClosedViaAeron()) {
                // this can happen when we use RMI to close a connection. RMI will (in most cases) ALWAYS send a response when it's
                // done executing. If the connection is *closed* first (because an RMI method closed it), then we will not be able to
                // send the message.
                // NOTE: we already know the connection is closed. we closed it (so it doesn't make sense to emit an error about this)
                return false
            }

            // more critical error sending the message. we shouldn't retry or anything.
            val errorMessage = "[${publication.sessionId()}] Error sending message. (${errorCodeName(result)})"

            // either client or server. No other choices. We create an exception, because it's more useful!
            val exception = newException(errorMessage)

            // +2 because we do not want to see the stack for the abstract `newException`
            // +3 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
            // where we see who is calling "send()"
            ListenerManager.cleanStackTrace(exception, 5)
            return false
        }
    }

    /**
     * Checks to see if an endpoint is running.
     *
     * @return true if the media driver is active and running
     */
    fun isRunning(): Boolean {
        return aeronDriver.isRunning()
    }

    /**
     * @param counterFunction callback for each of the internal counters of the Aeron driver in the current aeron directory
     */
    fun driverCounters(counterFunction: (counterId: Int, counterValue: Long, typeId: Int, keyBuffer: DirectBuffer?, label: String?) -> Unit) {
        aeronDriver.driverCounters(counterFunction)
    }

    /**
     * @return the backlog statistics for the Aeron driver
     */
    fun driverBacklog(): BacklogStat? {
        return aeronDriver.driverBacklog()
    }

    /**
     * @param errorAction callback for each of the errors reported by the Aeron driver in the current Aeron directory
     */
    fun driverErrors(errorAction: (observationCount: Int, firstObservationTimestamp: Long, lastObservationTimestamp: Long, encodedException: String) -> Unit) {
        aeronDriver.driverErrors(errorAction)
    }

    /**
     * @param lossStats callback for each of the loss statistic entries reported by the Aeron driver in the current Aeron directory
     */
    fun driverLossStats(lossStats: (observationCount: Long,
                                    totalBytesLost: Long,
                                    firstObservationTimestamp: Long,
                                    lastObservationTimestamp: Long,
                                    sessionId: Int, streamId: Int,
                                    channel: String, source: String) -> Unit): Int {
        return aeronDriver.driverLossStats(lossStats)
    }

    /**
     * @return the internal heartbeat of the Aeron driver in the current Aeron directory
     */
    fun driverHeartbeatMs(): Long {
        return aeronDriver.driverHeartbeatMs()
    }

    /**
     * @return the internal version of the Aeron driver in the current Aeron directory
     */
    fun driverVersion(): String {
        return aeronDriver.driverVersion()
    }

    /**
     * @return the current aeron context info, if any
     */
    fun contextInfo(): String {
        return aeronDriver.contextInfo()
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
    fun waitForClose() {
        var latch: CountDownLatch? = null

        while (latch !== shutdownLatch) {
            latch = shutdownLatch
            // if we are restarting the network state, we want to continue to wait for a proper close event. Because we RESET the latch,
            // we must continue to check
            latch.await()
        }
    }

    final override fun close() {
        if (shutdown.compareAndSet(expect = false, update = true)) {
            logger.info { "Shutting down..." }

            closeAction {
                // Connections MUST be closed first, because we want to make sure that no RMI messages can be received
                // when we close the RMI support objects (in which case, weird - but harmless - errors show up)
                // this will wait for RMI timeouts if there are RMI in-progress. (this happens if we close via an RMI method)
                responseManager.close()

                // the storage is closed via this as well.
                storage.close()
            }

            logger.info { "Done shutting down..." }
        }
    }
    /**
     * Close in such a way that we enable us to be restarted. This is the same as a "normal close", but DOES NOT close
     *  - response manager
     *  - storage
     */
    fun closeForRestart() {
        if (shutdown.compareAndSet(expect = false, update = true)) {
            logger.info { "Shutting down for restart..." }

            closeAction()

            logger.info { "Done shutting down for restart..." }
        }
    }

    private fun closeAction(extraActions: () -> Unit = {}) {
        // the server has to be able to call server.notifyDisconnect() on a list of connections. If we remove the connections
        // inside of connection.close(), then the server does not have a list of connections to call the global notifyDisconnect()
        val enableRemove = type == Client::class.java
        connections.forEach {
            logger.info {
                val aeronLogInfo = "${it.id}/${it.streamId} : ${it.remoteAddressString}"
                "[$aeronLogInfo] Closing connection" }
            it.close(enableRemove)
        }

        // must run after connections have been closed, but before anything else
        extraActions()

        close0()

        aeronDriver.close()

        // This closes the scope and all children in the scope
        eventDispatch.cancel("${type.simpleName} shutting down")
        messageDispatch.cancel("${type.simpleName} shutting down")

        // this will ONLY close the event dispatcher if ALL endpoints have closed it.
        // when an endpoint closes, the poll-loop shuts down, and removes itself from the list of poll actions that need to be performed.
        networkEventPoller.close()

        shutdownLatch = CountDownLatch(1)

        // if we are waiting for shutdown, cancel the waiting thread (since we have shutdown now)
        shutdownLatch.countDown()

        logger.info { "${type.simpleName} finished shutting down."}
    }


    internal open fun close0() {}


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
}
