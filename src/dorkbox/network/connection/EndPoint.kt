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
import dorkbox.netUtil.IP
import dorkbox.network.Client
import dorkbox.network.Configuration
import dorkbox.network.Server
import dorkbox.network.ServerConfiguration
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.BacklogStat
import dorkbox.network.aeron.EventPoller
import dorkbox.network.connection.buffer.BufferedMessages
import dorkbox.network.connection.streaming.StreamingControl
import dorkbox.network.connection.streaming.StreamingData
import dorkbox.network.connection.streaming.StreamingManager
import dorkbox.network.exceptions.*
import dorkbox.network.handshake.Handshaker
import dorkbox.network.ping.Ping
import dorkbox.network.rmi.ResponseManager
import dorkbox.network.rmi.RmiManagerConnections
import dorkbox.network.rmi.RmiManagerGlobal
import dorkbox.network.rmi.messages.MethodResponse
import dorkbox.network.rmi.messages.RmiMessage
import dorkbox.network.serialization.KryoReader
import dorkbox.network.serialization.KryoWriter
import dorkbox.network.serialization.Serialization
import dorkbox.network.serialization.SettingsStore
import dorkbox.objectPool.BoundedPoolObject
import dorkbox.objectPool.ObjectPool
import dorkbox.objectPool.Pool
import dorkbox.os.OS
import io.aeron.Publication
import io.aeron.driver.ThreadingMode
import io.aeron.logbuffer.Header
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import org.agrona.DirectBuffer
import org.agrona.concurrent.IdleStrategy
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.*



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
abstract class EndPoint<CONNECTION : Connection> private constructor(val type: Class<*>, val config: Configuration, loggerName: String) {

    protected constructor(config: Configuration,
                          loggerName: String)
        : this(Client::class.java, config, loggerName)

    protected constructor(config: ServerConfiguration,
                          loggerName: String)
        : this(Server::class.java, config, loggerName)

    companion object {
        // connections are extremely difficult to diagnose when the connection timeout is short
        internal const val DEBUG_CONNECTIONS = false

        internal const val IPC_NAME = "IPC"

        internal val networkEventPoller = EventPoller()
        internal val responseManager = ResponseManager()

        internal val lanAddress = IP.lanAddress()
    }

    val logger: Logger = LoggerFactory.getLogger(loggerName)

    internal val eventDispatch = EventDispatcher(loggerName)

    private val handler = CoroutineExceptionHandler { _, exception ->
        logger.error("Uncaught Coroutine Error: ${exception.stackTraceToString()}")
    }

    // this is rather silly, BUT if there are more complex errors WITH the coroutine that occur, a regular try/catch WILL NOT catch it.
    // ADDITIONALLY, an error handler is ONLY effective at the first, top-level `launch`. IT WILL NOT WORK ANY OTHER WAY.
    private val messageCoroutineScope = CoroutineScope(Dispatchers.IO + handler + SupervisorJob())
    private val messageChannel = Channel<Paired<CONNECTION>>()
    private val pairedPool: Pool<Paired<CONNECTION>>

    internal val listenerManager = ListenerManager<CONNECTION>(logger, eventDispatch)

    val connections = ConcurrentIterator<CONNECTION>()

    @Volatile
    internal var aeronDriver: AeronDriver

    /**
     * Returns the serialization wrapper if there is an object type that needs to be added in addition to the basic types.
     */
    val serialization: Serialization<CONNECTION>

    /**
     * Read and Write can be concurrent (different buffers are used)
     * GLOBAL, single threaded only kryo instances.
     *
     * This WILL RE-CONFIGURED during the client handshake! (it is all the same thread, so object visibility is not a problem)
     */
    @Volatile
    internal lateinit var readKryo: KryoReader<CONNECTION>

    internal val handshaker: Handshaker<CONNECTION>

    /**
     * Crypto and signature management
     */
    internal val crypto: CryptoManagement


    private val hook: Thread


    // manage the startup state of the endpoint. True if the endpoint is running
    internal val endpointIsRunning = atomic(false)

    // this only prevents multiple shutdowns (in the event this close() is called multiple times)
    private var shutdown = atomic(false)
    internal val shutdownInProgress = atomic(false)

    @Volatile
    internal var shutdownEventPoller = false

    @Volatile
    private var shutdownLatch = CountDownLatch(0)

    /**
     * This is run in lock-step to shutdown/close the client/server event poller. Afterward, connect/bind can be called again
     */
    @Volatile
    internal var pollerClosedLatch = CountDownLatch(0)

    /**
     * This is only notified when endpoint.close() is called where EVERYTHING is to be closed.
     */
    @Volatile
    internal var closeLatch = CountDownLatch(0)

    /**
     * Returns the storage used by this endpoint. This is the backing data structure for key/value pairs, and can be a database, file, etc
     *
     * Only one instance of these is created for an endpoint.
     */
    val storage: SettingsStore

    internal val rmiGlobalSupport = RmiManagerGlobal<CONNECTION>(logger)
    internal val rmiConnectionSupport: RmiManagerConnections<CONNECTION>

    private val streamingManager = StreamingManager<CONNECTION>(logger, config)

    /**
     * The primary machine port that the server will listen for connections on
     */
    @Volatile
    var port1: Int = 0
        internal set

    /**
     * The secondary machine port that the server will use to work around NAT firewalls (this is required, and will be different from the primary)
     */
    @Volatile
    var port2: Int = 0
        internal set

    init {
        if (DEBUG_CONNECTIONS) {
            logger.error("DEBUG_CONNECTIONS is enabled. This should not happen in release!")
        }

        // this happens more than once! (this is ok)
        config.validate()

        // serialization stuff
        @Suppress("UNCHECKED_CAST")
        serialization = config.serialization as Serialization<CONNECTION>
        serialization.finishInit(type, config.networkMtuSize)

        serialization.fileContentsSerializer.streamingManager = streamingManager

        // we are done with initial configuration, now finish serialization
        // the CLIENT will reassign these in the `connect0` method (because it registers what the server says to register)
        if (type == Server::class.java) {
            readKryo = serialization.newReadKryo()
        }


        // we have to be able to specify the property store
        storage = SettingsStore(config.settingsStore, logger)
        crypto = CryptoManagement(logger, storage, type, config.enableRemoteSignatureValidation)

        // Only starts the media driver if we are NOT already running!
        // NOTE: in the event that we are IPC -- only ONE SERVER can be running IPC at a time for a single driver!
        if (type == Server::class.java && config.enableIpc) {
            val configuration = config.copy()
            if (AeronDriver.isLoaded(configuration, logger)) {
                val e = ServerException("Only one server at a time can share a single aeron driver! Make the driver unique or change it's directory: ${configuration.aeronDirectory}")
                listenerManager.notifyError(e)
                throw e
            }
        }

        aeronDriver = try {
            @Suppress("LeakingThis")
            AeronDriver.new(this@EndPoint)
        } catch (e: Exception) {
            val exception = Exception("Error initializing endpoint", e)
            listenerManager.notifyError(exception)
            throw exception
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

        handshaker = Handshaker(logger, config, serialization, listenerManager, aeronDriver) { errorMessage, exception ->
            return@Handshaker newException(errorMessage, exception)
        }

        hook = Thread {
            close(closeEverything = true, sendDisconnectMessage = true, releaseWaitingThreads = true)
        }

        Runtime.getRuntime().addShutdownHook(hook)



        val poolObject = object : BoundedPoolObject<Paired<CONNECTION>>() {
            override fun newInstance(): Paired<CONNECTION> {
                return Paired()
            }
        }

        // The purpose of this, is to lessen the impact of garbage created on the heap.
        pairedPool = ObjectPool.nonBlockingBounded(poolObject, 256)
    }


    internal val typeName: String
        get() {
            return if (type == Server::class.java) {
                "server"
            } else {
                "client"
            }
        }

    internal val otherTypeName: String
        get() {
            return if (type == Server::class.java) {
                "client"
            } else {
                "server"
            }
        }

    internal fun isServer(): Boolean {
        return type === Server::class.java
    }

    internal fun isClient(): Boolean {
        return type === Client::class.java
    }

    /**
     * Make sure that shutdown latch is properly initialized
     *
     * The client calls this every time it attempts a connection.
     */
    internal fun initializeState() {
        // on repeated runs, we have to make sure that we release the original latches so we don't appear to deadlock.
        val origCloseLatch = closeLatch
        val origShutdownLatch = shutdownLatch
        val origPollerLatch = pollerClosedLatch

        // on the first run, we depend on these to be 0
        shutdownLatch = CountDownLatch(1)
        closeLatch = CountDownLatch(1)

        // make sure we don't deadlock if we are waiting for the server to close
        origCloseLatch.countDown()
        origShutdownLatch.countDown()
        origPollerLatch.countDown()

        endpointIsRunning.lazySet(true)
        shutdown.lazySet(false)
        shutdownEventPoller = false

        // there are threading issues if there are client(s) and server's within the same JVM, where we have thread starvation
        // this resolves the problem. Additionally, this is tied-to specific a specific endpoint instance
        networkEventPoller.configure(logger, config, this)



        // how to select the number of threads that will fetch/use data off the network stack.
        // The default is a minimum of 1, but maximum of 4.
        // Account for
        //  - Aeron Threads (3, usually - defined in config)
        //  - Leave 2 threads for "the box"

        val aeronThreads = when (config.threadingMode) {
            ThreadingMode.SHARED -> 1
            ThreadingMode.SHARED_NETWORK -> 2
            ThreadingMode.DEDICATED -> 3
            else -> 3
        }


        // create a new one when the endpoint starts up, because we close it when the endpoint shuts down or when the client retries
        // this leaves 2 for the box and XX for aeron
        val messageProcessThreads = (OS.optimumNumberOfThreads - aeronThreads).coerceAtLeast(1).coerceAtMost(4)

        // create a new one when the endpoint starts up, because we close it when the endpoint shuts down or when the client retries
        repeat(messageProcessThreads) {
            messageCoroutineScope.launch {
                // this is only true while the endpoint is running.
                while (endpointIsRunning.value) {
                    val paired = messageChannel.receive()
                    val connection = paired.connection
                    val message = paired.message
                    pairedPool.put(paired)

                    processMessageFromChannel(connection, message)
                }
            }
        }
    }

    /**
     * Only starts the media driver if we are NOT already running!
     *
     * If we were previously closed, we will start a new again. This is concurrent safe!
     *
     * @throws Exception if there is a problem starting the media driver
     */
    fun startDriver() {
        // recreate the driver if we have previously closed. If we have never run, this does nothing
        aeronDriver = aeronDriver.newIfClosed()
        aeronDriver.start()
    }

    /**
     * Stops the network driver.
     *
     * @param forceTerminate if true, then there is no caution when restarting the Aeron driver, and any other process on the machine using
     * the same driver will probably crash (unless they have been appropriately stopped).
     * If false, then the Aeron driver is only stopped if it is safe to do so
     */
    fun stopDriver(forceTerminate: Boolean = false) {
        if (forceTerminate) {
            aeronDriver.close()
        } else {
            aeronDriver.closeIfSingle()
        }
    }

    /**
     * This is called whenever a new connection is made. By overriding this, it is possible to customize the Connection type.
     */
    open fun newConnection(connectionParameters: ConnectionParams<CONNECTION>): CONNECTION {
        @Suppress("UNCHECKED_CAST")
        return Connection(connectionParameters) as CONNECTION
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
     * Adds a function that will be called when a client/server connection is FIRST initialized, but before it's
     * connected to the remote endpoint.
     *
     * NOTE: This callback is executed IN-LINE with network IO, so one must be very careful about what is executed.
     *
     * Things that happen in this event are TIME-CRITICAL, and must happen before anything else. If you block here, you will block network IO
     *
     * For a server, this function will be called for ALL client connections.
     */
    fun onInit(function: CONNECTION.() -> Unit){
        listenerManager.onInit(function)
    }

    /**
     * Adds a function that will be called when a client/server connection first establishes a connection with the remote end.
     * 'onInit()' callbacks will execute for both the client and server before `onConnect()` will execute will "connects" with each other
     */
    fun onConnect(function: CONNECTION.() -> Unit) {
        listenerManager.onConnect(function)
    }

    /**
     * Called when the remote end is no longer connected.
     *
     * Do not try to send messages! The connection will already be closed, resulting in an error if you attempt to do so.
     */
    fun onDisconnect(function: CONNECTION.() -> Unit) {
        listenerManager.onDisconnect(function)
    }

    /**
     * Called when there is an error for a specific connection
     *
     * The error is also sent to an error log before this method is called.
     */
    fun onError(function: CONNECTION.(Throwable) -> Unit) {
        listenerManager.onError(function)
    }

    /**
     * Called when there is a global error (and error that is not specific to a connection)
     *
     * The error is also sent to an error log before this method is called.
     */
    fun onErrorGlobal(function: (Throwable) -> Unit) {
        listenerManager.onError(function)
    }

    /**
     * Called when an object has been received from the remote end of the connection.
     *
     * This method should not block for long periods as other network activity will not be processed until it returns.
     */
    fun <Message : Any> onMessage(function: CONNECTION.(Message) -> Unit) {
        listenerManager.onMessage(function)
    }

    /**
     * This is designed to permit modifying/overriding how data is processed on the network.
     *
     * This will split a message if it's too large to send in a single network message.
     *
     * @return true if the message was successfully sent by aeron, false otherwise. Exceptions are caught and NOT rethrown!
     */
    open fun write(
        message: Any,
        publication: Publication,
        sendIdleStrategy: IdleStrategy,
        connection: Connection,
        maxMessageSize: Int,
        abortEarly: Boolean
    ): Boolean {
        @Suppress("UNCHECKED_CAST")
        connection as CONNECTION

        // prep for idle states
        sendIdleStrategy.reset()

        // A kryo instance CANNOT be re-used until after it's buffer is flushed to the network!
        val success = try {
            // since ANY thread can call 'send', we have to take kryo instances in a safe way
            val kryo = serialization.take()
            try {
                val buffer = kryo.write(connection, message)
                val objectSize = buffer.position()
                val internalBuffer = buffer.internalBuffer

                // one small problem! What if the message is too big to send all at once?
                // The maximum size we can send in a "single fragment" is the maxPayloadLength() function, which is the MTU length less header (with defaults this is 1,376 bytes).
                if (objectSize >= maxMessageSize) {
                    val kryoStream = serialization.take()
                    try {
                        // we must split up the message! It's too large for Aeron to manage.
                        streamingManager.send(
                            publication = publication,
                            originalBuffer = internalBuffer,
                            objectSize = objectSize,
                            maxMessageSize = maxMessageSize,
                            endPoint = this@EndPoint,
                            kryo = kryoStream, // this is safe, because we save out the bytes from the original object!
                            sendIdleStrategy = sendIdleStrategy,
                            connection = connection
                        )
                    } finally {
                        serialization.put(kryoStream)
                    }
                } else {
                    aeronDriver.send(publication, internalBuffer, kryo.bufferClaim, 0, objectSize, sendIdleStrategy, connection, abortEarly, listenerManager)
                }
            } finally {
                serialization.put(kryo)
            }
        } catch (e: Throwable) {
            // if the driver is closed due to a network disconnect or a remote-client termination, we also must close the connection.
            if (aeronDriver.internal.mustRestartDriverOnError) {
                // we had a HARD network crash/disconnect, we close the driver and then reconnect automatically
                //NOTE: notifyDisconnect IS NOT CALLED!
            }

            // make sure we atomically create the listener manager, if necessary
            else if (message is MethodResponse && message.result is Exception) {
                val result = message.result as Exception
                val newException = SerializationException("Error serializing message ${message.javaClass.simpleName}: '$message'", result)
                listenerManager.notifyError(connection, newException)
            } else if (message is ClientException || message is ServerException) {
                val newException = TransmitException("Error with message ${message.javaClass.simpleName}: '$message'", e)
                listenerManager.notifyError(connection, newException)
            } else {
                val newException = TransmitException("Error sending message ${message.javaClass.simpleName}: '$message'", e)
                listenerManager.notifyError(connection, newException)
            }

            false
        }

        return success
    }

    /**
     * This is designed to permit modifying/overriding how data is processed on the network.
     *
     * This will NOT split a message if it's too large. Aeron will just crash. This is used by the exclusively by the streaming manager.
     *
     * @return true if the message was successfully sent by aeron, false otherwise. Exceptions are caught and NOT rethrown!
     */
    open fun writeUnsafe(message: Any, publication: Publication, sendIdleStrategy: IdleStrategy, connection: CONNECTION, kryo: KryoWriter<CONNECTION>): Boolean {
        // NOTE: A kryo instance CANNOT be re-used until after it's buffer is flushed to the network!

        // since ANY thread can call 'send', we have to take kryo instances in a safe way
        // the maximum size that this buffer can be is:
        //   ExpandableDirectByteBuffer.MAX_BUFFER_LENGTH = 1073741824
        val buffer = kryo.write(connection, message)
        val objectSize = buffer.position()
        val internalBuffer = buffer.internalBuffer
        val bufferClaim = kryo.bufferClaim

        return aeronDriver.send(publication, internalBuffer, bufferClaim, 0, objectSize, sendIdleStrategy, connection, false, listenerManager)
    }

    /**
     * Processes a message that has been read off the network.
     *
     * The thread that reads this data, IS NOT the thread that consumes data off the network socket, but rather the data is consumed off
     * of the logfile (hopefully on a mem-disk). This allows for backpressure and network messages to arrive **faster** that what can be
     * processed.
     *
     * This is written in a way that permits modifying/overriding how data is processed on the network
     *
     * There are custom objects that are used (Ping, RmiMessages, Streaming object, etc.) are manage and use custom object types. These types
     * must be EXPLICITLY used by the implementation, and if a custom message processor is to be used (ie: a state machine) you must
     * guarantee that Ping, RMI, Streaming object, etc. are not used (as it would not function without this custom
     */
    open fun processMessage(message: Any?, connection: CONNECTION, readKryo: KryoReader<CONNECTION>) {
        // the REPEATED usage of wrapping methods below is because Streaming messages have to intercept data BEFORE it goes to a coroutine
        when (message) {
            // the remote endPoint will send this message if it is closing the connection.
            // IF we get this message in time, then we do not have to wait for the connection to expire before closing it
            is DisconnectMessage -> {
                val closeEverything = message.closeEverything

                if (logger.isDebugEnabled) {
                    if (closeEverything) {
                        logger.debug("Received disconnect message from $otherTypeName")
                    } else {
                        logger.debug("Received session disconnect message from $otherTypeName")
                    }
                }

                // make sure we flag the connection as NOT to timeout!!
                connection.isClosedWithTimeout() // we only need this to update fields
                connection.close(sendDisconnectMessage = false, closeEverything = closeEverything)
            }

            // streaming message. This is used when the published data is too large for a single Aeron message.
            // TECHNICALLY, we could arbitrarily increase the size of the permitted Aeron message, however this doesn't let us
            // send arbitrarily large pieces of data (gigs in size, potentially).
            // This will recursively call into this method for each of the unwrapped blocks of data.
            is StreamingControl -> {
                // NOTE: this CANNOT be on a separate threads, because we must guarantee that this happens first!
                streamingManager.processControlMessage(message, this@EndPoint, connection)
            }
            is StreamingData -> {
                // NOTE: this CANNOT be on a separate threads, because we must guarantee that this happens in-order!
                try {
                    streamingManager.processDataMessage(message, this@EndPoint, connection)
                } catch (e: Exception) {
                    listenerManager.notifyError(connection, StreamingException("Error processing StreamingMessage", e))
                }
            }

            is Any -> {
                // NOTE: This MUST be on a new threads (otherwise RMI has issues where it will be blocking)
                val paired = pairedPool.take()
                paired.connection = connection
                paired.message = message

                // This will try to send the element (blocking if necessary)
                messageChannel.trySendBlocking(paired)
            }

            else -> {
                listenerManager.notifyError(connection, MessageDispatchException("Unknown message received!!"))
            }
        }
    }

    /**
     * This is also what process the incoming message when it is received from the aeron network.
     *
     * THIS IS PROCESSED ON MULTIPLE THREADS!
     */
    internal fun processMessageFromChannel(connection: CONNECTION, message: Any) {
        when (message) {
            is Ping -> {
                // PING will also measure APP latency, not just NETWORK PIPE latency
                try {
                    connection.receivePing(message)
                } catch (e: Exception) {
                    listenerManager.notifyError(connection, PingException("Error while processing Ping message: $message", e))
                }
            }

            is BufferedMessages -> {
                // this can potentially be an EXTREMELY large set of data -- so when there are buffered messages, it is often better
                // to batch-send them instead of one-at-a-time (which can cause excessive CPU load and Network I/O)
                message.messages.forEach {
                    processMessageFromChannel(connection, it)
                }
            }

            // small problem... If we expect IN ORDER messages (ie: setting a value, then later reading the value), multiple threads don't work.
            // this is worked around by having RMI always return (unless async), even with a null value, so the CALLING side of RMI will always
            // go in "lock step"
            is RmiMessage -> {
                // if we are an RMI message/registration, we have very specific, defined behavior.
                // We do not use the "normal" listener callback pattern because this requires special functionality
                try {
                    rmiGlobalSupport.processMessage(serialization, connection, message, rmiConnectionSupport, responseManager, logger)
                } catch (e: Exception) {
                    listenerManager.notifyError(connection, RMIException("Error while processing RMI message", e))
                }
            }

            is SendSync -> {
                // SendSync enables us to NOTIFY the remote endpoint that we have received the message. This is to guarantee happens-before!
                // Using this will depend upon APP+NETWORK latency, and is (by design) not as performant as sending a regular message!
                try {
                    val message2 = message.message
                    if (message2 != null) {
                        // this is on the "remote end". Make sure to dispatch/notify the message BEFORE we send a message back!
                        try {
                            var hasListeners = listenerManager.notifyOnMessage(connection, message2)

                            // each connection registers, and is polled INDEPENDENTLY for messages.
                            hasListeners = hasListeners or connection.notifyOnMessage(message2)

                            if (!hasListeners) {
                                listenerManager.notifyError(connection, MessageDispatchException("No send-sync message callbacks found for ${message2::class.java.name}"))
                            }
                        } catch (e: Exception) {
                            listenerManager.notifyError(connection, MessageDispatchException("Error processing send-sync message ${message2::class.java.name}", e))
                        }
                    }

                    connection.receiveSendSync(message)
                } catch (e: Exception) {
                    listenerManager.notifyError(connection, SendSyncException("Error while processing send-sync message: $message", e))
                }
            }

            else -> {
                try {
                    var hasListeners = listenerManager.notifyOnMessage(connection, message)

                    // each connection registers, and is polled INDEPENDENTLY for messages.
                    hasListeners = hasListeners or connection.notifyOnMessage(message)

                    if (!hasListeners) {
                        listenerManager.notifyError(connection, MessageDispatchException("No message callbacks found for ${message::class.java.name}"))
                    }
                } catch (e: Exception) {
                    listenerManager.notifyError(connection, MessageDispatchException("Error processing message ${message::class.java.name}", e))
                }
            }
        }
    }


    /**
     * reads the message from the aeron buffer and figures out how to process it.
     *
     * This can be overridden should you want to customize exactly how data is received.
     *
     * @param buffer The buffer
     * @param offset The offset from the start of the buffer
     * @param length The number of bytes to extract
     * @param header The aeron header information
     * @param connection The connection this message happened on
     */
    internal fun dataReceive(
        buffer: DirectBuffer,
        offset: Int,
        length: Int,
        header: Header,
        connection: Connection
    ) {
        // this is processed on the thread that calls "poll". Subscriptions are NOT multi-thread safe!
        @Suppress("UNCHECKED_CAST")
        connection as CONNECTION

        try {
            // NOTE: This ABSOLUTELY MUST be done on the same thread! This cannot be done on a new one, because the buffer could change!
            val message = readKryo.read(buffer, offset, length, connection)
            if (logger.isTraceEnabled) {
                // don't automatically create the lambda when trace is disabled! Because this uses 'outside' scoped info, it's a new lambda each time!
                logger.trace("[${header.sessionId()}] received: ${message?.javaClass?.simpleName} $message")
            }
            processMessage(message, connection, readKryo)
        } catch (e: Exception) {
            listenerManager.notifyError(connection, newException("Error de-serializing message", e))
        }
    }

    /**
     * Ensures that an endpoint (using the specified configuration) is NO LONGER running.
     *
     * By default, we will wait the [Configuration.connectionCloseTimeoutInSeconds] * 2 amount of time before returning, and
     * 50ms between checks of the endpoint running
     *
     * @return true if the media driver is STOPPED.
     */
    fun ensureStopped(timeoutMS: Long = TimeUnit.SECONDS.toMillis(config.connectionCloseTimeoutInSeconds.toLong() * 2),
                              intervalTimeoutMS: Long = 500): Boolean {

        return aeronDriver.ensureStopped(timeoutMS, intervalTimeoutMS)
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
     * Waits for this endpoint to be internally shutdown, but not 100% fully closed (which only happens manually)
     *
     * @return true if the wait completed before the timeout
     */
    internal fun waitForEndpointShutdown(timeoutMS: Long = 0L): Boolean {
        // default is true, because if we haven't started up yet, we don't even check the latches
        var success = true


        var origPollerLatch: CountDownLatch?
        var origShutdownLatch: CountDownLatch? = null


        // don't need to check for both, as they are set together (we just have to check the later of the two)
        while (origShutdownLatch !== shutdownLatch) {
            // if we redefine the latches WHILE we are waiting for them, then we will NEVER release (since we lose the reference to the
            // original latch). This makes sure to check again to make sure we don't appear to deadlock
            origPollerLatch = pollerClosedLatch
            origShutdownLatch = shutdownLatch


            if (timeoutMS > 0) {
                success = success && origPollerLatch.await(timeoutMS, TimeUnit.MILLISECONDS)
                success = success && origShutdownLatch.await(timeoutMS, TimeUnit.MILLISECONDS)
            } else {
                origPollerLatch.await()
                origShutdownLatch.await()
                success = true
            }
        }

        return success
    }


    /**
     * Waits for this endpoint to be fully closed. A disconnect from the network (or remote endpoint) will not signal this to continue.
     */
    fun waitForClose(): Boolean {
        return waitForClose(0L)
    }

    /**
     * Waits for this endpoint to be fully closed. A disconnect from the network (or remote endpoint) will not signal this to continue.
     *
     * @return true if the wait completed before the timeout
     */
    fun waitForClose(timeoutMS: Long = 0L): Boolean {
        // if we are restarting the network state, we want to continue to wait for a proper close event.
        // when shutting down, it can take up to 5 seconds to fully register as "shutdown"

        if (networkEventPoller.isDispatch()) {
            // we cannot wait for a connection while inside the network event dispatch, since it has to close itself and this method waits for it!!
            throw IllegalStateException("Unable to 'waitForClose()' while inside the network event dispatch, this will deadlock!")
        }

        logger.trace("Waiting for endpoint to close...")

        var origCloseLatch: CountDownLatch? = null

        var success = false
        while (origCloseLatch !== closeLatch) {
            // if we redefine the latches WHILE we are waiting for them, then we will NEVER release (since we lose the reference to the
            // original latch). This makes sure to check again to make sure we don't appear to deadlock
            origCloseLatch = closeLatch


            success = if (timeoutMS > 0) {
                origCloseLatch.await(timeoutMS, TimeUnit.MILLISECONDS)
            } else {
                origCloseLatch.await()
                true
            }
        }

        return success
    }


    /**
     * Shall we preserve state when we shutdown, or do we remove all onConnect/Disconnect/etc events from memory.
     *
     * There are two viable concerns when we close the connection/client.
     *  1) We should reset 100% of the state+events, so that every time we connect, everything is redone
     *  2) We preserve the state+event, BECAUSE adding the onConnect/Disconnect/message event states might be VERY expensive.
     *
     * NOTE: This method does NOT block, as the connection state is asynchronous. Use "waitForClose()" to wait for this to finish
     *
     * @param closeEverything unless explicitly called, this is only false when a connection is closed in the client.
     */
    internal fun close(
        closeEverything: Boolean,
        sendDisconnectMessage: Boolean,
        releaseWaitingThreads: Boolean)
    {
        if (!eventDispatch.CLOSE.isDispatch()) {
            eventDispatch.CLOSE.launch {
                close(closeEverything, sendDisconnectMessage, releaseWaitingThreads)
            }
            return
        }

        if (logger.isDebugEnabled) {
            logger.debug("Requesting close: closeEverything=$closeEverything, sendDisconnectMessage=$sendDisconnectMessage, releaseWaitingThreads=$releaseWaitingThreads")
        }

        // 1) endpoints can call close()
        // 2) client can close the endpoint if the connection is D/C from aeron (and the endpoint was not closed manually)
        val shutdownPreviouslyStarted = shutdownInProgress.getAndSet(true)
        if (closeEverything && shutdownPreviouslyStarted) {
            if (logger.isDebugEnabled) {
                logger.debug("Shutdown previously started, cleaning up...")
            }
            // this is only called when the client network event poller shuts down
            // if we have clientConnectionClosed, then run that logic (because it doesn't run on the client when the connection is closed remotely)

            // Clears out all registered events
            listenerManager.close()

            // Remove from memory the data from the back-end storage
            storage.close()

            // don't do anything more, since we've already shutdown!
            return
        }

        if (shutdownPreviouslyStarted) {
            if (logger.isDebugEnabled) {
                logger.debug("Shutdown previously started, ignoring...")
            }
            return
        }

        if (Thread.currentThread() != hook) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook)
            } catch (ignored: Exception) {
            } catch (ignored: RuntimeException) {
            }
        }



        if (logger.isDebugEnabled) {
            logger.debug("Shutting down endpoint...")
        }


        // always do this. It is OK to run this multiple times
        // the server has to be able to call server.notifyDisconnect() on a list of connections. If we remove the connections
        // inside of connection.close(), then the server does not have a list of connections to call the global notifyDisconnect()
        connections.forEach {
            it.closeImmediately(sendDisconnectMessage = sendDisconnectMessage, closeEverything = closeEverything)
        }


        // this closes the endpoint specific instance running in the poller

        // THIS WILL SHUT DOWN THE EVENT POLLER IMMEDIATELY! BUT IN AN ASYNC MANNER!
        shutdownEventPoller = true

        // if we close the poller AND listener manager too quickly, events will not get published
        // this waits for the ENDPOINT to finish running its tasks in the poller.
        pollerClosedLatch.await()



        // this will ONLY close the event dispatcher if ALL endpoints have closed it.
        // when an endpoint closes, the poll-loop shuts down, and removes itself from the list of poll actions that need to be performed.
        networkEventPoller.close(logger, this)

        // Connections MUST be closed first, because we want to make sure that no RMI messages can be received
        // when we close the RMI support objects (in which case, weird - but harmless - errors show up)
        // IF CLOSED VIA RMI: this will wait for RMI timeouts if there are RMI in-progress.
        if (closeEverything) {
            // only close out RMI if we are closing everything!
            responseManager.close(logger)
        }


        // don't do these things if we are "closed" from a client connection disconnect
        // if there are any events going on, we want to schedule them to run AFTER all other events for this endpoint are done
        if (closeEverything) {
            // when the client connection is closed, we don't close the driver/etc.

            // Clears out all registered events
            listenerManager.close()

            // Remove from memory the data from the back-end storage
            storage.close()
        }

        // we might be restarting the aeron driver, so make sure it's closed.
        aeronDriver.close()

        shutdown.lazySet(true)

        // the shutdown here must be in the launchSequentially lambda, this way we can guarantee the driver is closed before we move on
        shutdownInProgress.lazySet(false)
        shutdownLatch.countDown()

        if (releaseWaitingThreads) {
            logger.trace("Counting down the close latch...")
            closeLatch.countDown()
        }

        logger.info("Done shutting down the endpoint.")
    }

    /**
     * @return true if the current execution thread is in the primary network event dispatch
     */
    fun isDispatch(): Boolean {
        return networkEventPoller.isDispatch()
    }

    /**
     * Shuts-down each event dispatcher executor, and waits for it to gracefully shutdown.
     *
     * Once shutdown, it cannot be restarted and the application MUST recreate the endpoint
     *
     * @param timeout how long to wait, must be > 0
     * @param timeoutUnit what the unit count is
     */
    fun shutdownEventDispatcher(timeout: Long = 15, timeoutUnit: TimeUnit = TimeUnit.SECONDS) {
        logger.info("Waiting for Event Dispatcher to shutdown...")
        eventDispatch.shutdownAndWait(timeout, timeoutUnit)
    }

    /**
     * Reset the running state when there's an error starting up
     */
    internal fun resetOnError() {
        shutdownLatch.countDown()
        pollerClosedLatch.countDown()
        endpointIsRunning.lazySet(false)
        shutdown.lazySet(false)
        shutdownEventPoller = false
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
