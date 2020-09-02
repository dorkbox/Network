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
import dorkbox.network.aeron.CoroutineIdleStrategy
import dorkbox.network.connection.ping.PingMessage
import dorkbox.network.ipFilter.IpFilterRule
import dorkbox.network.other.coroutines.SuspendWaiter
import dorkbox.network.rmi.RmiManagerConnections
import dorkbox.network.rmi.RmiManagerGlobal
import dorkbox.network.rmi.messages.RmiMessage
import dorkbox.network.serialization.KryoExtra
import dorkbox.network.serialization.Serialization
import dorkbox.network.storage.SettingsStore
import dorkbox.util.NamedThreadFactory
import dorkbox.util.exceptions.SecurityException
import io.aeron.Aeron
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
import java.io.File


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


    companion object {
        /**
         * Identifier for invalid sessions. This must be < RESERVED_SESSION_ID_LOW
         */
        const val RESERVED_SESSION_ID_INVALID = 0

        /**
         * The inclusive lower bound of the reserved sessions range. THIS SHOULD NEVER BE <= 0!
         */
        const val RESERVED_SESSION_ID_LOW = 1

        /**
         * The inclusive upper bound of the reserved sessions range.
         */
        const val RESERVED_SESSION_ID_HIGH = Integer.MAX_VALUE

        const val UDP_HANDSHAKE_STREAM_ID: Int = 0x1337cafe
        const val IPC_HANDSHAKE_STREAM_ID_PUB: Int = 0x1337c0de
        const val IPC_HANDSHAKE_STREAM_ID_SUB: Int = 0x1337c0d3


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
    }


    val logger: KLogger = KotlinLogging.logger(type.simpleName)

    internal val actionDispatch = CoroutineScope(Dispatchers.Default)

    internal val listenerManager = ListenerManager<CONNECTION>()
    internal val connections = ConnectionManager<CONNECTION>()

    internal var mediaDriverContext: MediaDriver.Context? = null
    private var mediaDriver: MediaDriver? = null
    private var aeron: Aeron? = null

    /**
     * Returns the serialization wrapper if there is an object type that needs to be added outside of the basic types.
     */
    val serialization: Serialization

    private val sendIdleStrategy: CoroutineIdleStrategy

    /**
     * Crypto and signature management
     */
    internal val crypto: CryptoManagement

    private val shutdown = atomic(false)

    @Volatile
    private var shutdownLatch: SuspendWaiter = SuspendWaiter()

    // we only want one instance of these created. These will be called appropriately
    val settingsStore: SettingsStore

    internal val rmiGlobalSupport = RmiManagerGlobal<CONNECTION>(logger, actionDispatch, config.serialization)

    init {
        logger.error("NETWORK STACK IS ONLY IPV4 AT THE MOMENT. IPV6 is in progress!")

        runBlocking {
            // our default onError handler. All error messages go though this
            listenerManager.onError { throwable ->
                logger.error("Error processing events", throwable)
            }

            listenerManager.onError { connection, throwable ->
                logger.error("Error processing events for connection $connection", throwable)
            }
        }

        // Aeron configuration

        /*
         * Linux
         * Linux normally requires some settings of sysctl values. One is net.core.rmem_max to allow larger SO_RCVBUF and
         * net.core.wmem_max to allow larger SO_SNDBUF values to be set.
         *
         * Windows
         * Windows tends to use SO_SNDBUF values that are too small. It is recommended to use values more like 1MB or so.
         *
         * Mac/Darwin
         *
         * Mac tends to use SO_SNDBUF values that are too small. It is recommended to use larger values, like 16KB.
         */
        if (config.receiveBufferSize == 0) {
            config.receiveBufferSize = io.aeron.driver.Configuration.SOCKET_RCVBUF_LENGTH_DEFAULT
//            when {
//                OS.isLinux() ->
//                OS.isWindows() ->
//                OS.isMacOsX() ->
//            }

//            val rmem_max = dorkbox.network.other.NetUtil.sysctlGetInt("net.core.rmem_max")
//            val wmem_max = dorkbox.network.other.NetUtil.sysctlGetInt("net.core.wmem_max")
        }


        if (config.sendBufferSize == 0) {
            config.receiveBufferSize = io.aeron.driver.Configuration.SOCKET_SNDBUF_LENGTH_DEFAULT
//            when {
//                OS.isLinux() ->
//                OS.isWindows() ->
//                OS.isMacOsX() ->
//            }

//            val rmem_max = dorkbox.network.other.NetUtil.sysctlGetInt("net.core.rmem_max")
//            val wmem_max = dorkbox.network.other.NetUtil.sysctlGetInt("net.core.wmem_max")
        }


        /*
         * Note: Since Mac OS does not have a built-in support for /dev/shm it is advised to create a RAM disk for the Aeron directory (aeron.dir).
         *
         * You can create a RAM disk with the following command:
         *
         * $ diskutil erasevolume HFS+ "DISK_NAME" `hdiutil attach -nomount ram://$((2048 * SIZE_IN_MB))`
         *
         * where:
         *
         * DISK_NAME should be replaced with a name of your choice.
         * SIZE_IN_MB is the size in megabytes for the disk (e.g. 4096 for a 4GB disk).
         *
         * For example, the following command creates a RAM disk named DevShm which is 2GB in size:
         *
         * $ diskutil erasevolume HFS+ "DevShm" `hdiutil attach -nomount ram://$((2048 * 2048))`
         *
         * After this command is executed the new disk will be mounted under /Volumes/DevShm.
         */
        var aeronDirAlreadyExists = false
        if (config.aeronLogDirectory == null) {
            val baseFileLocation = config.suggestAeronLogLocation(logger)

//            val aeronLogDirectory = File(baseFileLocation, "aeron-" + type.simpleName)
            val aeronLogDirectory = File(baseFileLocation, "aeron")
            aeronDirAlreadyExists = aeronLogDirectory.exists()
            config.aeronLogDirectory = aeronLogDirectory
        }

        logger.info("Aeron log directory: ${config.aeronLogDirectory}")
        if (aeronDirAlreadyExists) {
            logger.warn("Aeron log directory already exists! This might not be what you want!")
        }

        val threadFactory = NamedThreadFactory("Aeron", false)

        // LOW-LATENCY SETTINGS
        // .termBufferSparseFile(false)
        //             .useWindowsHighResTimer(true)
        //             .threadingMode(ThreadingMode.DEDICATED)
        //             .conductorIdleStrategy(BusySpinIdleStrategy.INSTANCE)
        //             .receiverIdleStrategy(NoOpIdleStrategy.INSTANCE)
        //             .senderIdleStrategy(NoOpIdleStrategy.INSTANCE);
        //     setProperty(DISABLE_BOUNDS_CHECKS_PROP_NAME, "true");
        //    setProperty("aeron.mtu.length", "16384");
        //    setProperty("aeron.socket.so_sndbuf", "2097152");
        //    setProperty("aeron.socket.so_rcvbuf", "2097152");
        //    setProperty("aeron.rcv.initial.window.length", "2097152");

        // driver context must happen in the initializer, because we have a Server.isRunning() method that uses the mediaDriverContext (without bind)
        val mDrivercontext = MediaDriver.Context()
            .publicationReservedSessionIdLow(RESERVED_SESSION_ID_LOW)
            .publicationReservedSessionIdHigh(RESERVED_SESSION_ID_HIGH)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true)
            .conductorThreadFactory(threadFactory)
            .receiverThreadFactory(threadFactory)
            .senderThreadFactory(threadFactory)
            .sharedNetworkThreadFactory(threadFactory)
            .sharedThreadFactory(threadFactory)
            .threadingMode(config.threadingMode)
            .mtuLength(config.networkMtuSize)
            .socketSndbufLength(config.sendBufferSize)
            .socketRcvbufLength(config.receiveBufferSize)

        mDrivercontext
            .aeronDirectoryName(config.aeronLogDirectory!!.absolutePath)
            .concludeAeronDirectory()

        if (mDrivercontext.ipcTermBufferLength() != io.aeron.driver.Configuration.ipcTermBufferLength()) {
            // default 64 megs each is HUGE
            mDrivercontext.ipcTermBufferLength(16 * 1024 * 1024)
        }

        if (mDrivercontext.publicationTermBufferLength() != io.aeron.driver.Configuration.termBufferLength()) {
            // default 16 megs each is HUGE (we run out of space in production w/ lots of clients)
            mDrivercontext.publicationTermBufferLength(4 * 1024 * 1024)
        }

        mediaDriverContext = mDrivercontext


        // serialization stuff
        serialization = config.serialization
        sendIdleStrategy = config.sendIdleStrategy

        // we have to be able to specify WHAT property store we want to use, since it can change!
        settingsStore = config.settingsStore
        settingsStore.init(serialization, config.settingsStorageSystem.build())

        settingsStore.getSerializationTypes().forEach {
            serialization.register(it)
        }

        crypto = CryptoManagement(logger, settingsStore, type, config)

        // we are done with initial configuration, now finish serialization
        serialization.finishInit(type)
    }

    internal fun initEndpointState(): Aeron {
        val aeronDirectory = config.aeronLogDirectory!!.absolutePath

        if (type == Server::class.java || !isRunning()) {
            // the server always creates a the media driver.
            mediaDriver = try {
                MediaDriver.launch(mediaDriverContext)
            } catch (e: Exception) {
                listenerManager.notifyError(e)
                throw e
            }
        }

        val aeronContext = Aeron.Context()
        aeronContext
            .aeronDirectoryName(aeronDirectory)
            .concludeAeronDirectory()

        try {
            aeron = Aeron.connect(aeronContext)
        } catch (e: Exception) {
            try {
                mediaDriver?.close()
            } catch (secondaryException: Exception) {
                e.addSuppressed(secondaryException)
            }

            listenerManager.notifyError(e)
            throw e
        }

        shutdown.getAndSet(false)

        shutdownLatch = SuspendWaiter()

        return aeron!!
    }


    abstract fun newException(message: String, cause: Throwable? = null): Throwable

    /**
     * Returns the property store used by this endpoint. The property store can store via properties,
     * a database, etc, or can be a "null" property store, which does nothing
     */
    fun <S : SettingsStore> getStorage(): S {
        @Suppress("UNCHECKED_CAST")
        return settingsStore as S
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

    /**
     * Runs an action for each connection
     */
    suspend fun forEachConnection(function: suspend (connection: CONNECTION) -> Unit) {
        connections.forEach {
            function(it)
        }
    }

    internal suspend fun writeHandshakeMessage(publication: Publication, message: Any) {
        // The sessionId is globally unique, and is assigned by the server.
        logger.trace {
            "[${publication.sessionId()}] send: $message"
        }

        // we are not thread-safe!
        val kryo = serialization.takeKryo()

        try {
            kryo.write(message)

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
                listenerManager.notifyError(newException("[${publication.sessionId()}] Error sending handshake message. $message (${
                    errorCodeName(result)
                })"))
                return
            }
        } catch (e: Exception) {
            listenerManager.notifyError(newException("[${publication.sessionId()}] Error serializing handshake message $message", e))
        } finally {
            sendIdleStrategy.reset()
            serialization.returnKryo(kryo)
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
    fun readHandshakeMessage(buffer: DirectBuffer, offset: Int, length: Int, header: Header): Any? {
        try {
            val message = serialization.readMessage(buffer, offset, length)
            logger.trace {
                "[${header.sessionId()}] received: $message"
            }

            return message
        } catch (e: Exception) {
            // The sessionId is globally unique, and is assigned by the server.
            val sessionId = header.sessionId()

            val exception = newException("[${sessionId}] Error de-serializing message", e)
            ListenerManager.cleanStackTrace(exception)
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
    fun processMessage(buffer: DirectBuffer, offset: Int, length: Int, header: Header, connection: Connection) {
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
            // The sessionId is globally unique, and is assigned by the server.
            val sessionId = header.sessionId()

            val exception = newException("[${sessionId}] Error de-serializing message", e)
            ListenerManager.cleanStackTrace(exception)
            listenerManager.notifyError(connection, exception)

            return // don't do anything!
        }


        when (message) {
            is PingMessage -> {
                // the ping listener (internal use only!)

//                val ping = message as PingMessage
//                if (ping.isReply) {
//                    updatePingResponse(ping)
//                } else {
//                    // return the ping from whence it came
//                    ping.isReply = true
//                    ping0(ping)
//                }
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
    suspend fun send(message: Any, publication: Publication, connection: Connection) {
        // The sessionId is globally unique, and is assigned by the server.
        logger.trace {
            "[${publication.sessionId()}] send: $message"
        }

        // since ANY thread can call 'send', we have to take kryo instances in a safe way
        val kryo: KryoExtra = serialization.takeKryo()
        try {
            kryo.write(connection, message)

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
                logger.error("[${publication.sessionId()}] Error sending message. $message (${errorCodeName(result)})")

                return
            }
        } catch (e: Exception) {
            logger.error("[${publication.sessionId()}] Error serializing message $message", e)
        } finally {
            sendIdleStrategy.reset()
            serialization.returnKryo(kryo)
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
        shutdownLatch.doWait()
    }

    /**
     * Checks to see if an endpoint (using the specified configuration) is running.
     *
     * @return true if the client/server is active and running
     */
    fun isRunning(): Boolean {
        // if the media driver is running, it will be a quick connection. Usually 100ms or so
        return mediaDriverContext?.isDriverActive(1_000, logger::debug) ?: false
    }

    final override fun close() {
        if (shutdown.compareAndSet(expect = false, update = true)) {
            aeron?.close()
            mediaDriver?.close()

            // the storage is closed via this as well
            settingsStore.close()

            rmiGlobalSupport.close()

            runBlocking {
                connections.forEach {
                    it.close()
                }
            }

            close0()

            // if we are waiting for shutdown, cancel the waiting thread (since we have shutdown now)
            shutdownLatch.cancel()
        }
    }

    internal open fun close0() {}
}
