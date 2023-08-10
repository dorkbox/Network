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
import dorkbox.network.aeron.CoroutineIdleStrategy
import dorkbox.network.aeron.EventPoller
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTrace
import dorkbox.network.connection.streaming.StreamingControl
import dorkbox.network.connection.streaming.StreamingData
import dorkbox.network.connection.streaming.StreamingManager
import dorkbox.network.exceptions.*
import dorkbox.network.handshake.Handshaker
import dorkbox.network.ping.Ping
import dorkbox.network.ping.PingManager
import dorkbox.network.rmi.ResponseManager
import dorkbox.network.rmi.RmiManagerConnections
import dorkbox.network.rmi.RmiManagerGlobal
import dorkbox.network.rmi.messages.MethodResponse
import dorkbox.network.rmi.messages.RmiMessage
import dorkbox.network.serialization.KryoReader
import dorkbox.network.serialization.KryoWriter
import dorkbox.network.serialization.Serialization
import dorkbox.network.serialization.SettingsStore
import io.aeron.Publication
import io.aeron.logbuffer.BufferClaim
import io.aeron.logbuffer.Header
import io.aeron.protocol.DataHeaderFlyweight
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import mu.KLogger
import mu.KotlinLogging
import org.agrona.DirectBuffer
import org.agrona.MutableDirectBuffer
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
abstract class EndPoint<CONNECTION : Connection> private constructor(val type: Class<*>,
                     val config: Configuration,
                     internal val connectionFunc: (connectionParameters: ConnectionParams<CONNECTION>) -> CONNECTION,
                     loggerName: String)
    {

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

        internal val lanAddress = IP.lanAddress()
    }

    val logger: KLogger = KotlinLogging.logger(loggerName)

    // this is rather silly, BUT if there are more complex errors WITH the coroutine that occur, a regular try/catch WILL NOT catch it.
    // ADDITIONALLY, an error handler is ONLY effective at the first, top-level `launch`. IT WILL NOT WORK ANY OTHER WAY.
    private val errorHandler = CoroutineExceptionHandler { _, exception ->
        logger.error(exception) { "Uncaught Coroutine Error!" }
    }

    private val messageDispatch = CoroutineScope(config.messageDispatch + SupervisorJob())

    internal val listenerManager = ListenerManager<CONNECTION>(logger)

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
    @Volatile
    private var shutdown = false
    internal val shutdownInProgress = atomic(false)

    @Volatile
    internal var shutdownEventPoller = false

    @Volatile
    private var shutdownLatch = dorkbox.util.sync.CountDownLatch(0)

    /**
     * This is run in lock-step to shutdown/close the client/server event poller. Afterwards, connect/bind can be called again
     */
    @Volatile
    internal var pollerClosedLatch = dorkbox.util.sync.CountDownLatch(0)


    /**
     * Returns the storage used by this endpoint. This is the backing data structure for key/value pairs, and can be a database, file, etc
     *
     * Only one instance of these is created for an endpoint.
     */
    val storage: SettingsStore

    internal val rmiGlobalSupport = RmiManagerGlobal<CONNECTION>(logger)
    internal val rmiConnectionSupport: RmiManagerConnections<CONNECTION>

    private val streamingManager = StreamingManager<CONNECTION>(logger, messageDispatch, config)

    private val pingManager = PingManager<CONNECTION>()

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
            logger.error { "DEBUG_CONNECTIONS is enabled. This should not happen in release!" }
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
            runBlocking {
                var configuration = config.copy()
                if (AeronDriver.isLoaded(configuration, logger)) {
                    val e = ServerException("Only one server at a time can share a single aeron driver! Make the driver unique or change it's directory: ${configuration.aeronDirectory}")
                    listenerManager.notifyError(e)
                    throw e
                }


                configuration = config.copy()
                if (AeronDriver.isRunning(configuration, logger)) {
                    val e = ServerException("Only one server at a time can share a single aeron driver! Make the driver unique or change it's directory: ${configuration.aeronDirectory}")
                    listenerManager.notifyError(e)
                    throw e
                }
            }
        }


        try {
            @Suppress("LeakingThis")
            aeronDriver = AeronDriver(this)
        } catch (e: Exception) {
            listenerManager.notifyError(Exception("Error initializing endpoint", e))
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

        handshaker = Handshaker(logger, config, serialization, listenerManager, aeronDriver) { errorMessage, exception ->
            return@Handshaker newException(errorMessage, exception)
        }

        hook = Thread {
            runBlocking {
                close(
                    closeEverything = true, releaseWaitingThreads = true
                )
            }
        }

        Runtime.getRuntime().addShutdownHook(hook)
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

    internal fun ifServer(function: Server<CONNECTION>.() -> Unit) {
        if (type == Server::class.java) {
            function(this as Server<CONNECTION>)
        }
    }

    internal fun ifClient(function: Client<CONNECTION>.() -> Unit) {
        if (type == Client::class.java) {
            function(this as Client<CONNECTION>)
        }
    }

    /**
     * Make sure that the different dispatchers are currently active.
     *
     * The client calls this every time it attempts a connection.
     */
    internal fun verifyState() {
        require(messageDispatch.isActive) { "The Message Dispatch is no longer active. It has been shutdown" }
    }

    /**
     * Make sure that shutdown latch is properly initialized
     *
     * The client calls this every time it attempts a connection.
     */
    internal fun initializeState() {
        // on the first run, we depend on these to be 0
        shutdownLatch = dorkbox.util.sync.CountDownLatch(1)
        pollerClosedLatch = dorkbox.util.sync.CountDownLatch(1)
        closeLatch = dorkbox.util.sync.CountDownLatch(1)

        endpointIsRunning.lazySet(true)
        shutdown = false
        shutdownEventPoller = false

        // there are threading issues if there are client(s) and server's within the same JVM, where we have thread starvation
        // this resolves the problem. Additionally, this is tied-to specific a specific endpoint instance
        networkEventPoller.configure(logger, config, this)
    }

    /**
     * Only starts the media driver if we are NOT already running!
     *
     * If we were previously closed, we will start a new again. This is concurrent safe!
     *
     * @throws Exception if there is a problem starting the media driver
     */
    suspend fun startDriver() {
        if (aeronDriver.closed()) {
            // Only starts the media driver if we are NOT already running!
            try {
                aeronDriver = AeronDriver(this)
            } catch (e: Exception) {
                throw newException("Error initializing aeron driver", e)
            }
        }

        aeronDriver.start()
    }

    /**
     * Stops the network driver.
     *
     * @param forceTerminate if true, then there is no caution when restarting the Aeron driver, and any other process on the machine using
     * the same driver will probably crash (unless they have been appropriately stopped).
     * If false, then the Aeron driver is only stopped if it is safe to do so
     */
    suspend fun stopDriver(forceTerminate: Boolean = false) {
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
     * Adds a function that will be called when a client/server connection is FIRST initialized, but before it's
     * connected to the remote endpoint.
     *
     * NOTE: This callback is executed IN-LINE with network IO, so one must be very careful about what is executed.
     *
     * Things that happen in this event are TIME-CRITICAL, and must happen before anything else. If you block here, you will block network IO
     *
     * For a server, this function will be called for ALL client connections.
     */
    fun onInit(function: suspend CONNECTION.() -> Unit){
        runBlocking {
            listenerManager.onInit(function)
        }
    }

    /**
     * Adds a function that will be called when a client/server connection first establishes a connection with the remote end.
     * 'onInit()' callbacks will execute for both the client and server before `onConnect()` will execute will "connects" with each other
     */
    fun onConnect(function: suspend CONNECTION.() -> Unit) {
        runBlocking {
            listenerManager.onConnect(function)
        }
    }

    /**
     * Called when the remote end is no longer connected.
     *
     * Do not try to send messages! The connection will already be closed, resulting in an error if you attempt to do so.
     */
    fun onDisconnect(function: suspend CONNECTION.() -> Unit) {
        runBlocking {
            listenerManager.onDisconnect(function)
        }
    }

    /**
     * Called when there is an error for a specific connection
     *
     * The error is also sent to an error log before this method is called.
     */
    fun onError(function: suspend CONNECTION.(Throwable) -> Unit) {
        runBlocking {
            listenerManager.onError(function)
        }
    }

    /**
     * Called when there is a global error (and error that is not specific to a connection)
     *
     * The error is also sent to an error log before this method is called.
     */
    fun onErrorGlobal(function: suspend (Throwable) -> Unit) {
        runBlocking {
            listenerManager.onError(function)
        }
    }

    /**
     * Called when an object has been received from the remote end of the connection.
     *
     * This method should not block for long periods as other network activity will not be processed until it returns.
     */
    fun <Message : Any> onMessage(function: suspend CONNECTION.(Message) -> Unit) {
        runBlocking {
            listenerManager.onMessage(function)
        }
    }

    /**
     * Sends a "ping" packet to measure **ROUND TRIP** time to the remote connection.
     *
     * @return true if the message was successfully sent by aeron
     */
    internal suspend fun ping(connection: Connection, pingTimeoutMs: Int, function: suspend Ping.() -> Unit): Boolean {
        return pingManager.ping(connection, pingTimeoutMs, responseManager, logger, function)
    }

    /**
     * This is designed to permit modifying/overriding how data is processed on the network.
     *
     * This will split a message if it's too large to send in a single network message.
     *
     * @return true if the message was successfully sent by aeron, false otherwise. Exceptions are caught and NOT rethrown!
     */
    open suspend fun write(
        message: Any,
        publication: Publication,
        sendIdleStrategy: CoroutineIdleStrategy,
        connection: Connection,
        maxMessageSize: Int,
        abortEarly: Boolean
    ): Boolean {
        @Suppress("UNCHECKED_CAST")
        connection as CONNECTION

        // prep for idle states
        sendIdleStrategy.reset()

        // A kryo instance CANNOT be re-used until after it's buffer is flushed to the network!
        return try {
            // since ANY thread can call 'send', we have to take kryo instances in a safe way
            serialization.withKryo {
                val buffer =  this.write(connection, message)
                val objectSize = buffer.position()
                val internalBuffer = buffer.internalBuffer

                // one small problem! What if the message is too big to send all at once?
                // The maximum size we can send in a "single fragment" is the maxPayloadLength() function, which is the MTU length less header (with defaults this is 1,376 bytes).
                if (objectSize >= maxMessageSize) {
                    serialization.withKryo {
                        // we must split up the message! It's too large for Aeron to manage.
                        streamingManager.send(
                            publication = publication,
                            originalBuffer = internalBuffer,
                            objectSize = objectSize,
                            maxMessageSize = maxMessageSize,
                            endPoint = this@EndPoint,
                            kryo = this, // this is safe, because we save out the bytes from the original object!
                            sendIdleStrategy = sendIdleStrategy,
                            connection = connection
                        )
                    }
                } else {
                    aeronDriver.send(publication, internalBuffer, bufferClaim, 0, objectSize, sendIdleStrategy, connection, abortEarly, listenerManager)
                }
            }
        } catch (e: Throwable) {
            // if the driver is closed due to a network disconnect or a remote-client termination, we also must close the connection.
            if (aeronDriver.criticalDriverError) {
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
    }

    /**
     * This is designed to permit modifying/overriding how data is processed on the network.
     *
     * This will NOT split a message if it's too large. Aeron will just crash. This is used by the exclusively by the streaming manager.
     *
     * @return true if the message was successfully sent by aeron, false otherwise. Exceptions are caught and NOT rethrown!
     */
    open suspend fun writeUnsafe(message: Any, publication: Publication, sendIdleStrategy: CoroutineIdleStrategy, connection: CONNECTION, kryo: KryoWriter<CONNECTION>): Boolean {
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
                // NOTE: This MUST be on a new co-routine (this is...)
                runBlocking {
                    logger.debug { "Received disconnect message from $otherTypeName" }
                    connection.close(sendDisconnectMessage = false,
                                     notifyDisconnect = true)
                }
            }

            is Ping -> {
                // PING will also measure APP latency, not just NETWORK PIPE latency
                // NOTE: This MUST be on a new co-routine, specifically the messageDispatch
                messageDispatch.launch {
                    try {
                        pingManager.manage(connection, responseManager, message, logger)
                    } catch (e: Exception) {
                        listenerManager.notifyError(connection, PingException("Error while processing Ping message", e))
                    }
                }
            }

            // small problem... If we expect IN ORDER messages (ie: setting a value, then later reading the value), multiple threads don't work.
            // this is worked around by having RMI always return (unless async), even with a null value, so the CALLING side of RMI will always
            // go in "lock step"
            is RmiMessage -> {
                // if we are an RMI message/registration, we have very specific, defined behavior.
                // We do not use the "normal" listener callback pattern because this requires special functionality
                // NOTE: This MUST be on a new co-routine, specifically the messageDispatch (it IS NOT the EventDispatch.RESPONSE_MANAGER!)
                messageDispatch.launch {
                    try {
                        rmiGlobalSupport.processMessage(serialization, connection, message, rmiConnectionSupport, responseManager, logger)
                    } catch (e: Exception) {
                        listenerManager.notifyError(connection, RMIException("Error while processing RMI message", e))
                    }
                }
            }


            // streaming message. This is used when the published data is too large for a single Aeron message.
            // TECHNICALLY, we could arbitrarily increase the size of the permitted Aeron message, however this doesn't let us
            // send arbitrarily large pieces of data (gigs in size, potentially).
            // This will recursively call into this method for each of the unwrapped blocks of data.
            is StreamingControl -> {
                streamingManager.processControlMessage(message, readKryo,this@EndPoint, connection)
            }
            is StreamingData -> {
                // NOTE: This MUST NOT be on a new co-routine. It must be on the same thread!
                try {
                    streamingManager.processDataMessage(message, this@EndPoint, connection)
                } catch (e: Exception) {
                    listenerManager.notifyError(connection, StreamingException("Error processing StreamingMessage", e))
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
                            listenerManager.notifyError(connection, MessageDispatchException("No message callbacks found for ${message::class.java.name}"))
                        }
                    } catch (e: Exception) {
                        listenerManager.notifyError(connection, MessageDispatchException("Error processing message ${message::class.java.name}", e))
                    }
                }
            }

            else -> {
                listenerManager.notifyError(connection, MessageDispatchException("Unknown message received!!"))
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
            logger.trace { "[${header.sessionId()}] received: ${message?.javaClass?.simpleName} $message" }
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
    suspend fun ensureStopped(timeoutMS: Long = TimeUnit.SECONDS.toMillis(config.connectionCloseTimeoutInSeconds.toLong() * 2),
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
        return shutdown
    }

    /**
     * Waits for this endpoint to be internally shutdown, but not 100% fully closed (which only happens manually)
     *
     * @return true if the wait completed before the timeout
     */
    internal suspend fun waitForEndpointShutdown(timeoutMS: Long = 0L): Boolean {
        return if (timeoutMS > 0) {
            pollerClosedLatch.await(timeoutMS, TimeUnit.MILLISECONDS) &&
            shutdownLatch.await(timeoutMS, TimeUnit.MILLISECONDS)
        } else {
            pollerClosedLatch.await()
            shutdownLatch.await()
            true
        }
    }


    /**
     * Waits for this endpoint to be closed
     */
    suspend fun waitForClose(): Boolean {
        return waitForClose(0L)
    }

    /**
     * Waits for this endpoint to be fully closed. A disconnect from the network (or remote endpoint) will not signal this to continue.
     *
     * @return true if the wait completed before the timeout
     */
    suspend fun waitForClose(timeoutMS: Long = 0L): Boolean {
        // if we are restarting the network state, we want to continue to wait for a proper close event.
        // when shutting down, it can take up to 5 seconds to fully register as "shutdown"

        val success = if (timeoutMS > 0) {
            closeLatch.await(timeoutMS, TimeUnit.MILLISECONDS)
        } else {
            closeLatch.await()
            true
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
    internal suspend fun close(
        closeEverything: Boolean,
        releaseWaitingThreads: Boolean)
    {
        logger.debug { "Requesting close: closeEverything=$closeEverything, releaseWaitingThreads=$releaseWaitingThreads" }

        // 1) endpoints can call close()
        // 2) client can close the endpoint if the connection is D/C from aeron (and the endpoint was not closed manually)
        val shutdownPreviouslyStarted = shutdownInProgress.getAndSet(true)
        if (closeEverything && shutdownPreviouslyStarted) {
            logger.debug { "Shutdown previously started, cleaning up..." }
            // this is only called when the client network event poller shuts down
            // if we have clientConnectionClosed, then run that logic (because it doesn't run on the client when the connection is closed remotely)

            // Clears out all registered events
            listenerManager.close()

            // Remove from memory the data from the back-end storage
            storage.close()

            aeronDriver.close()

            // don't do anything more, since we've already shutdown!
            return
        }

        if (!shutdownPreviouslyStarted && Thread.currentThread() != hook) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook)
            } catch (ignored: Exception) {
            } catch (ignored: RuntimeException) {
            }
        }

        EventDispatcher.CLOSE.launch {
            logger.debug { "Shutting down endpoint..." }

            // always do this. It is OK to run this multiple times
            // the server has to be able to call server.notifyDisconnect() on a list of connections. If we remove the connections
            // inside of connection.close(), then the server does not have a list of connections to call the global notifyDisconnect()
            connections.forEach {
                it.closeImmediately(sendDisconnectMessage = true,
                                    notifyDisconnect = true)
            }



            // this closes the endpoint specific instance running in the poller

            // THIS WILL SHUT DOWN THE EVENT POLLER IMMEDIATELY! BUT IN AN ASYNC MANNER!
            shutdownEventPoller = true

            // if we close the poller AND listener manager too quickly, events will not get published
            pollerClosedLatch.await()

            // this will ONLY close the event dispatcher if ALL endpoints have closed it.
            // when an endpoint closes, the poll-loop shuts down, and removes itself from the list of poll actions that need to be performed.
            networkEventPoller.close(logger, this)




            // Connections MUST be closed first, because we want to make sure that no RMI messages can be received
            // when we close the RMI support objects (in which case, weird - but harmless - errors show up)
            // this will wait for RMI timeouts if there are RMI in-progress. (this happens if we close via an RMI method)
            responseManager.close()

            // don't do these things if we are "closed" from a client connection disconnect
            // if there are any events going on, we want to schedule them to run AFTER all other events for this endpoint are done
            EventDispatcher.launchSequentially(EventDispatcher.CLOSE) {
                if (closeEverything) {
                    // when the client connection is closed, we don't close the driver/etc.

                    // Clears out all registered events
                    listenerManager.close()

                    // Remove from memory the data from the back-end storage
                    storage.close()
                }

                    aeronDriver.close()

                    shutdown = true

                // the shutdown here must be in the launchSequentially lambda, this way we can guarantee the driver is closed before we move on
                shutdownLatch.countDown()
                shutdownInProgress.lazySet(false)

                if (releaseWaitingThreads) {
                    logger.trace { "Counting down the close latch..." }
                    closeLatch.countDown()
                }

                logger.info { "Done shutting down the endpoint." }
            }
        }
    }

    /**
     * Reset the running state when there's an error starting up
     */
    internal fun resetOnError() {
        shutdownLatch.countDown()
        pollerClosedLatch.countDown()
        endpointIsRunning.lazySet(false)
        shutdown = false
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
