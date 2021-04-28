package dorkbox.network.aeron

import dorkbox.network.Configuration
import dorkbox.network.connection.ListenerManager
import dorkbox.util.NamedThreadFactory
import io.aeron.Aeron
import io.aeron.ChannelUriStringBuilder
import io.aeron.Publication
import io.aeron.Subscription
import io.aeron.driver.MediaDriver
import io.aeron.exceptions.DriverTimeoutException
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mu.KLogger
import mu.KotlinLogging
import org.agrona.concurrent.BackoffIdleStrategy
import java.io.File

/**
 * Class for managing the Aeron+Media drivers
 */
class AeronDriver(val config: Configuration,
                  val type: Class<*> = AeronDriver::class.java,
                  val logger: KLogger = KotlinLogging.logger("AeronConfig")) {

    companion object {
        /**
         * Identifier for invalid sessions. This must be < RESERVED_SESSION_ID_LOW
         */
        internal const val RESERVED_SESSION_ID_INVALID = 0

        /**
         * The inclusive lower bound of the reserved sessions range. THIS SHOULD NEVER BE <= 0!
         */
        internal const val RESERVED_SESSION_ID_LOW = 1

        /**
         * The inclusive upper bound of the reserved sessions range.
         */
        internal const val RESERVED_SESSION_ID_HIGH = Integer.MAX_VALUE

        const val UDP_HANDSHAKE_STREAM_ID: Int = 0x1337cafe
        const val IPC_HANDSHAKE_STREAM_ID_PUB: Int = 0x1337c0de
        const val IPC_HANDSHAKE_STREAM_ID_SUB: Int = 0x1337c0d3


        // on close, the publication CAN linger (in case a client goes away, and then comes back)
        // AERON_PUBLICATION_LINGER_TIMEOUT, 5s by default (this can also be set as a URI param)
        private const val AERON_PUBLICATION_LINGER_TIMEOUT = 5_000L  // in MS


        private fun create(config: Configuration, logger: KLogger): MediaDriver.Context {
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
            if (config.aeronDirectory == null) {
                val baseFileLocation = config.suggestAeronLogLocation(logger)

    //            val aeronLogDirectory = File(baseFileLocation, "aeron-" + type.simpleName)
                val aeronLogDirectory = File(baseFileLocation, "aeron")
                config.aeronDirectory = aeronLogDirectory
            }

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
            val context = MediaDriver.Context()
                .publicationReservedSessionIdLow(RESERVED_SESSION_ID_LOW)
                .publicationReservedSessionIdHigh(RESERVED_SESSION_ID_HIGH)
                .threadingMode(config.threadingMode)
                .mtuLength(config.networkMtuSize)
                .socketSndbufLength(config.sendBufferSize)
                .socketRcvbufLength(config.receiveBufferSize)

            context.aeronDirectoryName(config.aeronDirectory!!.absolutePath)

            if (context.ipcTermBufferLength() != io.aeron.driver.Configuration.ipcTermBufferLength()) {
                // default 64 megs each is HUGE
                context.ipcTermBufferLength(8 * 1024 * 1024)
            }

            if (context.publicationTermBufferLength() != io.aeron.driver.Configuration.termBufferLength()) {
                // default 16 megs each is HUGE (we run out of space in production w/ lots of clients)
                context.publicationTermBufferLength(2 * 1024 * 1024)
            }

            // we DO NOT want to abort the JVM if there are errors.
            context.errorHandler { error ->
                logger.error("Error in Aeron", error)
            }


            val aeronDir = File(context.aeronDirectoryName()).absoluteFile
            context.aeronDirectoryName(aeronDir.path)

            return context
        }


        /**
         * Creates the Aeron Media Driver context
         *
         * @throws IllegalStateException if the configuration has already been used to create a context
         * @throws IllegalArgumentException if the aeron media driver directory cannot be setup
         */
        fun createContext(config: Configuration, logger: KLogger = KotlinLogging.logger("AeronConfig")) {
            if (config.context != null) {
                logger.warn { "Unable to recreate a context for a configuration that has already been created" }
                return
            }

            var context = create(config, logger)

            // this happens EXACTLY once. Must be BEFORE the "isRunning" check!
            context.concludeAeronDirectory()

            // will setup the aeron directory or throw IllegalArgumentException if it cannot be configured
            val aeronDir = context.aeronDirectory()

            var isRunning = isRunning(context)

            // this is incompatible with IPC, and will not be set if IPC is enabled
            if (config.aeronDirectoryForceUnique && isRunning) {
                val savedParent = aeronDir.parentFile
                var retry = 0
                val retryMax = 100

                while (config.aeronDirectoryForceUnique && isRunning) {
                    if (retry++ > retryMax) {
                        throw IllegalArgumentException("Unable to force unique aeron Directory. Tried $retryMax times and all tries were in use.")
                    }

                    val randomNum = (1..retryMax).shuffled().first()
                    val newDir = savedParent.resolve("${aeronDir.name}_$randomNum")

                    context = create(config, logger)
                    context.aeronDirectoryName(newDir.path)

                    // this happens EXACTLY once. Must be BEFORE the "isRunning" check!
                    context.concludeAeronDirectory()

                    isRunning = isRunning(context)
                }

                if (!isRunning) {
                    // NOTE: We must be *super* careful trying to delete directories, because if we have multiple AERON/MEDIA DRIVERS connected to the
                    //   same directory, deleting the directory will cause any other aeron connection to fail! (which makes sense).
                    // since we are forcing a unique directory, we should ALSO delete it when we are done!
                    context.dirDeleteOnShutdown()
                }
            }

            logger.info { "Aeron directory: '${context.aeronDirectory()}'" }

            // once we do this, we cannot change any of the config values!
            config.context = context
        }

        /**
         * Checks to see if an endpoint (using the specified configuration) is running.
         *
         * @return true if the media driver is active and running
         */
        fun isRunning(context: MediaDriver.Context, timeout: Long = context.driverTimeoutMs()): Boolean {
            // if the media driver is running, it will be a quick connection. Usually 100ms or so
            return context.isDriverActive(timeout) { }
        }

        /**
         * validates and creates the configuration. This can only happen once!
         */
        fun validateConfig(config: Configuration, logger: KLogger = KotlinLogging.logger("AeronConfig")) {
            config.validate()

            if (config.context == null) {
                createContext(config, logger)
            }

            require(config.context != null) { "Configuration context cannot be properly created. Unable to continue!" }
        }
    }

    private val closeRequested = atomic(false)

    @Volatile
    private var aeron: Aeron? = null

    @Volatile
    private var mediaDriver: MediaDriver? = null

    // the context is validated before the AeronDriver object is created
    private val threadFactory = NamedThreadFactory("Thread", ThreadGroup("${type.simpleName}-AeronDriver"), true)
    private val mediaDriverContext = config.context!!

    // did WE start the media driver, or did SOMEONE ELSE start it?
    private val mediaDriverAlreadyStarted: Boolean

    init {
        mediaDriverContext
            .conductorThreadFactory(threadFactory)
            .receiverThreadFactory(threadFactory)
            .senderThreadFactory(threadFactory)
            .sharedNetworkThreadFactory(threadFactory)
            .sharedThreadFactory(threadFactory)

        mediaDriverAlreadyStarted = isRunning(mediaDriverContext)
    }

    private fun setupAeron(): Aeron.Context {
        val aeronDriverContext = Aeron.Context()
        aeronDriverContext
            .aeronDirectoryName(mediaDriverContext.aeronDirectory().path)
            .concludeAeronDirectory()

        aeronDriverContext
            .threadFactory(threadFactory)
            .idleStrategy(BackoffIdleStrategy())

        // we DO NOT want to abort the JVM if there are errors.
        // this replaces the default handler with one that doesn't abort the JVM
        aeronDriverContext.errorHandler { error ->
            ListenerManager.cleanStackTrace(error)
            logger.error("Error in Aeron", error)
        }

        return aeronDriverContext
    }


    /**
     * @return true if the media driver was started, false if it was not started
     */
    private fun startDriver(): Boolean {
        if (closeRequested.value) {
            return false
        }

        if (mediaDriver == null) {
            // only start if we didn't already start... There will be several checks.

            if (!isRunning(mediaDriverContext)) {
                logger.debug("Starting Aeron Media driver in '${mediaDriverContext.aeronDirectory()}'")


                // try to start. If we start/stop too quickly, it's a problem
                var count = 10
                while (count-- > 0) {
                    try {
                        mediaDriver = MediaDriver.launch(mediaDriverContext)
                        return true
                    } catch (e: Exception) {
                        logger.warn(e) { "Unable to start the Aeron Media driver. Retrying $count more times..." }
                        runBlocking {
                            delay(mediaDriverContext.driverTimeoutMs())
                        }
                    }
                }
            } else {
                logger.debug("Not starting Aeron Media driver. It was already running in '${mediaDriverContext.aeronDirectory()}'")
            }
        }

        return false
    }

    private fun startAeron(didStartDriver: Boolean) {
        if (closeRequested.value) {
            return
        }

        // the media driver MIGHT already be started in a different process! We still ALWAYS want to connect to
        // aeron (which connects to the other media driver process), especially if we haven't already connected to
        // it (or if there was an error connecting because a different media driver was shutting down)

        if (didStartDriver || aeron == null) {
            aeron?.close()

            // this might succeed if we can connect to the media driver
            aeron = Aeron.connect(setupAeron())
        }
    }

    /**
     * If the driver is not already running, this will start the driver
     *
     * @throws Exception if there is a problem starting the media driver
     */
    fun start() {
        // we want to be able to "restart" aeron, as necessary, after a [close] method call
        closeRequested.lazySet(false)
        startOrRestart()
    }

    /**
     * if we did NOT close, then will start the media driver + aeron,
     */
    private fun startOrRestart() {
        if (closeRequested.value) {
            return
        }

        val didStartDriver = startDriver()
        startAeron(didStartDriver)
    }


    /**
     * @return the aeron media driver log file for a specific publication. This should be removed when a publication is closed (but is not always!)
     */
    fun getMediaDriverPublicationFile(publicationRegId: Long): File {
        return mediaDriverContext.aeronDirectory().resolve("publications").resolve("${publicationRegId}.logbuffer")
    }


    suspend fun addPublicationWithRetry(publicationUri: ChannelUriStringBuilder, streamId: Int): Publication {
        val uri = publicationUri.build()

        // If we start/stop too quickly, we might have the address already in use! Retry a few times.
        var count = 10
        var exception: Exception? = null
        while (count-- > 0) {
            try {
                // if aeron is null, an exception is thrown
                return aeron!!.addPublication(uri, streamId)
            } catch (e: Exception) {
                // NOTE: this error will be logged in the `aeronDriverContext` logger
                exception = e
                logger.warn { "Unable to add a publication to Aeron. Retrying $count more times..." }

                if (e is DriverTimeoutException) {
                    delay(mediaDriverContext.driverTimeoutMs())
                }

                // reasons we cannot add a pub/sub to aeron
                // 1) the driver was closed
                // 2) aeron was unable to connect to the driver
                // 3) the address already in use

                // configuring pub/sub to aeron is LINEAR -- and it happens in 2 places.
                // 1) starting up the client/server
                // 2) creating a new client-server connection pair (the media driver won't be "dead" at this poitn)

                // try to start/restart aeron
                try {
                    startOrRestart()
                } catch (e2: Exception) {
                    // ignored
                }
            }
        }

        throw exception!!
    }

    suspend fun addSubscriptionWithRetry(subscriptionUri: ChannelUriStringBuilder, streamId: Int): Subscription {
        val uri = subscriptionUri.build()

        // If we start/stop too quickly, we might have the address already in use! Retry a few times.
        var count = 10
        var exception: Exception? = null
        while (count-- > 0) {
            try {
                val aeron = aeron
                if (aeron != null) {
                    return aeron.addSubscription(uri, streamId)
                }
            } catch (e: Exception) {
                // NOTE: this error will be logged in the `aeronDriverContext` logger
                exception = e
                logger.warn { "Unable to add a sublication to Aeron. Retrying $count more times..." }

                if (e is DriverTimeoutException) {
                    delay(mediaDriverContext.driverTimeoutMs())
                }

                // reasons we cannot add a pub/sub to aeron
                // 1) the driver was closed
                // 2) aeron was unable to connect to the driver
                // 3) the address already in use

                // configuring pub/sub to aeron is LINEAR -- and it happens in 2 places.
                // 1) starting up the client/server
                // 2) creating a new client-server connection pair (the media driver won't be "dead" at this poitn)

                // try to start/restart aeron
                try {
                    startOrRestart()
                } catch (e2: Exception) {
                   // ignored
                }
            }
        }

        throw exception!!
    }

    /**
     * Checks to see if an endpoint (using the specified configuration) is running.
     *
     * @return true if the media driver is active and running
     */
    fun isRunning(timeout: Long = mediaDriverContext.driverTimeoutMs()): Boolean {
        // if the media driver is running, it will be a quick connection. Usually 100ms or so
        return mediaDriverContext.isDriverActive(timeout) { }
    }

    /**
     * A safer way to try to close the media driver
     *
     * NOTE: We must be *super* careful trying to delete directories, because if we have multiple AERON/MEDIA DRIVERS connected to the
     *   same directory, deleting the directory will cause any other aeron connection to fail! (which makes sense).
     */
    suspend fun close() {
        closeRequested.lazySet(true)

        try {
            aeron?.close()
        } catch (e: Exception) {
            logger.error("Error stopping aeron.", e)
        }

        aeron = null

        val mediaDriver = mediaDriver
        if (mediaDriver == null) {
            logger.debug("No driver started for this instance. Not Stopping.")
            return
        }

        if (!mediaDriverAlreadyStarted) {
            logger.debug("We did not start the media driver, so we are not stopping it.")
            return
        }


        logger.debug("Stopping driver at '${mediaDriverContext.aeronDirectory()}'...")

        if (!isRunning(mediaDriverContext)) {
            // not running
            logger.debug { "Driver is not running at '${mediaDriverContext.aeronDirectory()}' for this context. Not Stopping." }
            return
        }

        try {
            mediaDriver.close()

            // on close, the publication CAN linger (in case a client goes away, and then comes back)
            // AERON_PUBLICATION_LINGER_TIMEOUT, 5s by default (this can also be set as a URI param)
            delay(AERON_PUBLICATION_LINGER_TIMEOUT)

            this@AeronDriver.mediaDriver = null

            // wait for the media driver to actually stop
            var count = 10
            while (count-- >= 0 && isRunning(mediaDriverContext)) {
                logger.warn { "Aeron Media driver at '${mediaDriverContext.aeronDirectory()}' is still running. Waiting for it to stop. Trying $count more times." }
                delay(mediaDriverContext.driverTimeoutMs())
            }
        } catch (e: Exception) {
            logger.error("Error closing the media driver at '${mediaDriverContext.aeronDirectory()}'", e)
        }

        // Destroys this thread group and all of its subgroups.
        // This thread group must be empty, indicating that all threads that had been in this thread group have since stopped.
        threadFactory.group.destroy()

        logger.debug { "Closed the media driver at '${mediaDriverContext.aeronDirectory()}'" }
    }
}
