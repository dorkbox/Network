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

import dorkbox.network.Client
import dorkbox.network.Configuration
import dorkbox.network.Server
import dorkbox.network.ServerConfiguration
import dorkbox.network.aeron.CoroutineIdleStrategy
import dorkbox.network.connection.ping.PingMessage
import dorkbox.network.other.CryptoEccNative
import dorkbox.network.other.NetworkUtil
import dorkbox.network.rmi.RmiSupport
import dorkbox.network.rmi.messages.RmiMessage
import dorkbox.network.serialization.NetworkSerializationManager
import dorkbox.network.store.NullSettingsStore
import dorkbox.network.store.SettingsStore
import dorkbox.util.NamedThreadFactory
import dorkbox.util.OS
import dorkbox.util.entropy.Entropy
import dorkbox.util.exceptions.SecurityException
import io.aeron.Aeron
import io.aeron.Publication
import io.aeron.driver.MediaDriver
import io.aeron.logbuffer.Header
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mu.KLogger
import mu.KotlinLogging
import org.agrona.DirectBuffer
import java.io.File
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.XECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

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
abstract class EndPoint<C : Connection>
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

        init {
            println("THIS IS ONLY IPV4 AT THE MOMENT. IPV6 is in progress!")
        }

        fun errorCodeName(result: Long): String {
            return when (result) {
                Publication.NOT_CONNECTED -> "Not connected"
                Publication.ADMIN_ACTION -> "Administrative action"
                Publication.BACK_PRESSURED -> "Back pressured"
                Publication.CLOSED -> "Publication is closed"
                Publication.MAX_POSITION_EXCEEDED -> "Maximum term position exceeded"
                else -> throw IllegalStateException()
            }
        }
    }


    val logger: KLogger = KotlinLogging.logger(type.simpleName)

    internal val closables = CopyOnWriteArrayList<AutoCloseable>()

    internal val actionDispatch = CoroutineScope(Dispatchers.Default)
    internal abstract val connectionManager: ConnectionManager<C>

    internal val mediaDriverContext: MediaDriver.Context
    internal val mediaDriver: MediaDriver
    internal val aeron: Aeron

    /**
     * Returns the serialization wrapper if there is an object type that needs to be added outside of the basics.
     */
    val serialization: NetworkSerializationManager

    private val sendIdleStrategy: CoroutineIdleStrategy


    val privateKey: PrivateKey?
    val publicKey: ByteArray?

    val secureRandom: SecureRandom

    private val shutdown = atomic(false)
    private val shutdownLatch = CountDownLatch(1)

    // we only want one instance of these created. These will be called appropriately
    val settingsStore: SettingsStore

    var disableRemoteKeyValidation = false

    val rmiSupport = RmiSupport<C>(logger, actionDispatch, config.serialization)

    /**
     * Checks to see if this client has connected yet or not.
     *
     * Once a server has connected to ANY client, it will always return true until server.close() is called
     *
     * @return true if we are connected, false otherwise.
     */
    abstract fun isConnected(): Boolean


    init {
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
        if (config.aeronLogDirectory == null) {
            val baseFile = when {
                OS.isMacOsX() -> {
                    logger.info("It is recommended to create a RAM drive for best performance. For example\n" +
                            "\$ diskutil erasevolume HFS+ \"DevShm\" `hdiutil attach -nomount ram://\$((2048 * 2048))`\n" +
                            "\t After this, set config.aeronLogDirectory = \"/Volumes/DevShm\"")
                    File(System.getProperty("java.io.tmpdir"))
                }
                OS.isLinux() -> {
                    // this is significantly faster for linux than using the temp dir
                    File(System.getProperty("/dev/shm/"))
                }
                else -> {
                    File(System.getProperty("java.io.tmpdir"))
                }
            }

            val baseName = "aeron-" + type.simpleName
            val aeronLogDirectory = File(baseFile, baseName)
            if (aeronLogDirectory.exists()) {
                logger.error("Aeron log directory already exists! This might not be what you want!")
            }
            logger.debug("Aeron log directory: $aeronLogDirectory")
            config.aeronLogDirectory = aeronLogDirectory
        }

        val threadFactory = NamedThreadFactory("Aeron", false)

        // LOW-LATENCY SETTINGS
        // .termBufferSparseFile(false)
        //             .useWindowsHighResTimer(true)
        //             .threadingMode(ThreadingMode.DEDICATED)
        //             .conductorIdleStrategy(BusySpinIdleStrategy.INSTANCE)
        //             .receiverIdleStrategy(NoOpIdleStrategy.INSTANCE)
        //             .senderIdleStrategy(NoOpIdleStrategy.INSTANCE);
        mediaDriverContext = MediaDriver.Context()
                .publicationReservedSessionIdLow(RESERVED_SESSION_ID_LOW)
                .publicationReservedSessionIdHigh(RESERVED_SESSION_ID_HIGH)
                .dirDeleteOnStart(true) // TODO: FOR NOW?
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
                .aeronDirectoryName(config.aeronLogDirectory!!.absolutePath)

        val aeronContext = Aeron.Context().aeronDirectoryName(mediaDriverContext.aeronDirectoryName())

        try {
            mediaDriver = MediaDriver.launch(mediaDriverContext)
        } catch (e: Exception) {
            throw e
        }

        try {
            aeron = Aeron.connect(aeronContext)
        } catch (e: Exception) {
            try {
                mediaDriver.close()
            } catch (secondaryException: Exception) {
                e.addSuppressed(secondaryException)
            }
            throw e
        }

        closables.add(aeron)
        closables.add(mediaDriver)


        // serialization stuff
        serialization = config.serialization
        sendIdleStrategy = config.sendIdleStrategy

        // we have to be able to specify WHAT property store we want to use, since it can change!
        settingsStore = config.settingsStore
        settingsStore.init(serialization, config.settingsStorageSystem.build())

        // the storage is closed via this as well
        closables.add(settingsStore)

        secureRandom = SecureRandom(settingsStore.getSalt())

        if (settingsStore !is NullSettingsStore) {
            // initialize the private/public keys used for negotiating ECC handshakes
            // these are ONLY used for IP connections. LOCAL connections do not need a handshake!
            var privateKey = settingsStore.getPrivateKey()
            var publicKey = settingsStore.getPublicKey()

            if (privateKey == null || publicKey == null) {
                try {
                    // seed our RNG based off of this and create our ECC keys
                    val seedBytes = Entropy.get("There are no ECC keys for the " + type.simpleName + " yet")
                    logger.debug("Now generating ECC (" + CryptoEccNative.curve25519 + ") keys. Please wait!")

                    secureRandom.nextBytes(seedBytes)
                    val generateKeyPair = CryptoEccNative.createKeyPair(secureRandom)
                    privateKey = generateKeyPair.private.encoded
                    publicKey = generateKeyPair.public.encoded

                    // save to properties file
                    settingsStore.savePrivateKey(privateKey)
                    settingsStore.savePublicKey(publicKey)
                    logger.debug("Done with ECC keys!")
                } catch (e: Exception) {
                    val message = "Unable to initialize/generate ECC keys. FORCED SHUTDOWN."
                    logger.error(message)
                    throw SecurityException(message)
                }
            }

            val keyFactory = KeyFactory.getInstance("XDH")

            this.privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKey)) as XECPrivateKey
            //  keyFactory.generatePublic(X509EncodedKeySpec(publicKey)) as XECPublicKey
            this.publicKey = publicKey
        } else {
            privateKey = null
            publicKey = null
        }

        // we are done with initial configuration, now finish serialization
        serialization.finishInit(type)
    }

    /**
     * Disables remote endpoint public key validation when the connection is established. This is not recommended as it is a security risk
     */
    fun disableRemoteKeyValidation() {
        if (isConnected()) {
            logger.error("Cannot disable the remote key validation after this endpoint is connected!")
        } else {
            logger.info("WARNING: Disabling remote key validation is a security risk!!")
            disableRemoteKeyValidation = true
        }
    }

    /**
     * If the key does not match AND we have disabled remote key validation, then metachannel.changedRemoteKey = true. OTHERWISE, key validation is REQUIRED!
     *
     * @return true if the remote address public key matches the one saved or we disabled remote key validation.
     */
    internal fun validateRemoteAddress(remoteAddress: Int, publicKey: ByteArray?): Boolean {
        if (publicKey == null) {
            logger.error("Error validating public key for ${NetworkUtil.IP.toString(remoteAddress)}! It was null (and should not have been)")
            return false
        }

        try {
            val savedPublicKey = config.settingsStore.getRegisteredServerKey(remoteAddress)
            if (savedPublicKey == null) {
                if (logger.isDebugEnabled) {
                    logger.debug("Adding new remote IP address key for ${NetworkUtil.IP.toString(remoteAddress)}")
                }

                config.settingsStore.addRegisteredServerKey(remoteAddress, publicKey)
            } else {
                // COMPARE!
                if (!publicKey.contentEquals(savedPublicKey)) {
                    return if (!config.enableRemoteSignatureValidation) {
                        logger.warn("Invalid or non-matching public key from remote connection, their public key has changed. Toggling extra flag in channel to indicate key change. To fix, remove entry for: ${NetworkUtil.IP.toString(remoteAddress)}")
                        true
                    } else {
                        // keys do not match, abort!
                        logger.error("Invalid or non-matching public key from remote connection, their public key has changed. To fix, remove entry for: ${NetworkUtil.IP.toString(remoteAddress)}")
                        false
                    }
                }
            }
        } catch (e: SecurityException) {
            // keys do not match, abort!
            logger.error("Error validating public key for ${NetworkUtil.IP.toString(remoteAddress)}!", e)
            return false
        }

        return true
    }


    /**
     * Returns the property store used by this endpoint. The property store can store via properties,
     * a database, etc, or can be a "null" property store, which does nothing
     */
    fun <S : SettingsStore> getPropertyStore(): S {
        @Suppress("UNCHECKED_CAST")
        return settingsStore as S
    }

    /**
     * This method allows the connections used by the client/server to be subclassed (with custom implementations).
     *
     * As this is for the network stack, the new connection MUST subclass [ConnectionImpl]
     *
     * The parameters are ALL NULL when getting the base class, as this instance is just thrown away.
     *
     * @return a new network connection
     */
    @Suppress("MemberVisibilityCanBePrivate")
    open fun newConnection(endPoint: EndPoint<C>, mediaDriverConnection: MediaDriverConnection): C {
        @Suppress("UNCHECKED_CAST")
        return ConnectionImpl(endPoint, mediaDriverConnection) as C
    }

    /**
     * Adds a function that will be called BEFORE a client/server "connects" with
     * each other, and used to determine if a connection should be allowed
     * <p>
     * If the function returns TRUE, then the connection will continue to connect.
     * If the function returns FALSE, then the other end of the connection will
     *   receive a connection error
     * <p>
     * For a server, this function will be called for ALL clients.
     */
    fun filter(function: suspend (C) -> Boolean) {
        actionDispatch.launch {
            connectionManager.filter(function)
        }
    }

    /**
     * Adds a function that will be called when a client/server "connects" with each other
     * <p>
     * For a server, this function will be called for ALL clients.
     */
    fun onConnect(function: suspend (C) -> Unit) {
        actionDispatch.launch {
            connectionManager.onConnect(function)
        }
    }

    /**
     * Called when the remote end is no longer connected.
     * <p>
     * Do not try to send messages! The connection will already be closed, resulting in an error if you attempt to do so.
     */
    fun onDisconnect(function: suspend (C) -> Unit) {
        actionDispatch.launch {
            connectionManager.onDisconnect(function)
        }
    }

    /**
     * Called when there is an error for a specific connection
     * <p>
     * The error is also sent to an error log before this method is called.
     */
    fun onError(function: suspend (C, Throwable) -> Unit) {
        actionDispatch.launch {
            connectionManager.onError(function)
        }
    }

    /**
     * Called when there is an error in general
     * <p>
     * The error is also sent to an error log before this method is called.
     */
    fun onError(function: suspend (Throwable) -> Unit) {
        actionDispatch.launch {
            connectionManager.onError(function)
        }
    }

    /**
     * Called when an object has been received from the remote end of the connection.
     * <p>
     * This method should not block for long periods as other network activity will not be processed until it returns.
     */
    fun <M : Any> onMessage(function: suspend (C, M) -> Unit) {
        actionDispatch.launch {
            connectionManager.onMessage(function)
        }
    }

    /**
     * Runs an action for each connection inside of a read-lock
     */
    suspend fun forEachConnection(function: suspend (connection: C) -> Unit) {
        connectionManager.forEachConnectionDoRead(function)
    }

    internal suspend fun writeHandshakeMessage(publication: Publication, message: Any) {
        // The sessionId is globally unique, and is assigned by the server.
        logger.debug("[{}] send: {}", publication.sessionId(), message)

        val kryo: KryoExtra = serialization.takeKryo()
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
                logger.error("Error sending message. ${errorCodeName(result)}")

                return
            }
        } catch (e: Exception) {
            logger.error("Error serializing message $message", e)
        } finally {
            sendIdleStrategy.reset()
            serialization.returnKryo(kryo)
        }
    }

    /**
     * @param buffer The buffer
     * @param offset The offset from the start of the buffer
     * @param length The number of bytes to extract
     *
     * @return A string
     */
    internal fun readHandshakeMessage(buffer: DirectBuffer, offset: Int, length: Int, header: Header): Any? {
        val kryo: KryoExtra = serialization.takeKryo()
        try {
            val message = kryo.read(buffer, offset, length)
            logger.debug("[{}] received: {}", header.sessionId(), message)
            return message
        } catch (e: Exception) {
            logger.error("Error de-serializing message on connection ${header.sessionId()}!", e)
        } finally {
            serialization.returnKryo(kryo)
        }

        return null
    }

    suspend fun readMessage(buffer: DirectBuffer, offset: Int, length: Int, header: Header, connection: Connection_) {
        // The sessionId is globally unique, and is assigned by the server.
        val sessionId = header.sessionId()

        // note: this address will ALWAYS be an IP:PORT combo
//        val remoteIpAndPort = (header.context() as Image).sourceIdentity()

        // split
//        val splitPoint = remoteIpAndPort.lastIndexOf(':')
//        val ip = remoteIpAndPort.substring(0, splitPoint)
//        val port = remoteIpAndPort.substring(splitPoint+1)

//        val ipAsInt = NetworkUtil.IP.toInt(ip)


        var message: Any? = null

        val kryo: KryoExtra = serialization.takeKryo()
        try {
            message = kryo.read(buffer, offset, length, connection)
            logger.debug("[{}] received: {}", sessionId, message)
        } catch (e: Exception) {
            logger.error("[${sessionId}] Error de-serializing message", e)
        } finally {
            serialization.returnKryo(kryo)
        }


        val data = ByteArray(length)
        buffer.getBytes(offset, data)

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
            is RmiMessage -> {
                // if we are an RMI message/registration, we have very specific, defined behavior.
                // We do not use the "normal" listener callback pattern because this require special functionality
                @Suppress("UNCHECKED_CAST")
                rmiSupport.manage(this as EndPoint<Connection_>, connection, message, logger)
            }
            is Any -> {
                connectionManager.notifyOnMessage(connection, message)
            }
            else -> {
                // do nothing, there were problems with the message
            }
        }
    }





















    override fun toString(): String {
        return "EndPoint [${type.simpleName}]"
    }

    override fun hashCode(): Int {
        val prime = 31
        var result = 1
        result = prime * result + (privateKey?.hashCode() ?: 0)
        result = prime * result + (publicKey?.hashCode() ?: 0)
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

        val other1 = other as EndPoint<*>
        if (privateKey == null) {
            if (other1.privateKey != null) {
                return false
            }
        } else if (other1.privateKey == null) {
            return false
        } else if (!privateKey.encoded.contentEquals(other1.privateKey.encoded)) {
            return false
        }
        if (publicKey == null) {
            if (other1.publicKey != null) {
                return false
            }
        } else if (other1.publicKey == null) {
            return false
        } else if (!publicKey.contentEquals(other1.publicKey)) {
            return false
        }

        return true
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
    fun waitForShutdown() {
        shutdownLatch.await()
    }


    override fun close() {
        if (shutdown.compareAndSet(expect = false, update = true)) {
            closables.forEach {
                it.close()
            }
            closables.clear()

            actionDispatch.cancel()
            shutdownLatch.countDown()
        }
    }
}
