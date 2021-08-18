package dorkbox.network.aeron

import dorkbox.network.Configuration
import dorkbox.network.connection.ListenerManager
import dorkbox.network.exceptions.AeronDriverException
import dorkbox.util.NamedThreadFactory
import io.aeron.Aeron
import io.aeron.ChannelUriStringBuilder
import io.aeron.Publication
import io.aeron.Subscription
import io.aeron.driver.MediaDriver
import io.aeron.exceptions.DriverTimeoutException
import kotlinx.atomicfu.atomic
import mu.KLogger
import mu.KotlinLogging
import org.agrona.concurrent.BackoffIdleStrategy
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.Thread.sleep
import java.net.BindException

/**
 * Class for managing the Aeron+Media drivers
 */
class AeronDriver(
    val config: Configuration,
    val type: Class<*> = AeronDriver::class.java,
    val logger: KLogger = KotlinLogging.logger("AeronConfig"),
    aeronErrorLogger: (exception: Throwable) -> Unit = { error -> LoggerFactory.getLogger("AeronDriver").error("Aeron error", error) }
) {

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

        private fun setConfigDefaults(config: Configuration, logger: KLogger) {
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
        }

        private fun create(
            config: Configuration,
            threadFactory: NamedThreadFactory,
            aeronErrorHandler: (error: Throwable) -> Unit

        ): MediaDriver.Context {
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
            val mediaDriverContext = MediaDriver.Context()
                .publicationReservedSessionIdLow(RESERVED_SESSION_ID_LOW)
                .publicationReservedSessionIdHigh(RESERVED_SESSION_ID_HIGH)
                .threadingMode(config.threadingMode)
                .mtuLength(config.networkMtuSize)

                .initialWindowLength(config.initialWindowLength)
                .socketSndbufLength(config.sendBufferSize)
                .socketRcvbufLength(config.receiveBufferSize)

            mediaDriverContext
                .conductorThreadFactory(threadFactory)
                .receiverThreadFactory(threadFactory)
                .senderThreadFactory(threadFactory)
                .sharedNetworkThreadFactory(threadFactory)
                .sharedThreadFactory(threadFactory)

            mediaDriverContext.aeronDirectoryName(config.aeronDirectory!!.absolutePath)

            if (mediaDriverContext.ipcTermBufferLength() != io.aeron.driver.Configuration.ipcTermBufferLength()) {
                // default 64 megs each is HUGE
                mediaDriverContext.ipcTermBufferLength(8 * 1024 * 1024)
            }

            if (mediaDriverContext.publicationTermBufferLength() != io.aeron.driver.Configuration.termBufferLength()) {
                // default 16 megs each is HUGE (we run out of space in production w/ lots of clients)
                mediaDriverContext.publicationTermBufferLength(2 * 1024 * 1024)
            }

            // we DO NOT want to abort the JVM if there are errors.
            // this replaces the default handler with one that doesn't abort the JVM
            mediaDriverContext.errorHandler { error ->
                aeronErrorHandler(AeronDriverException(error))
            }

            return mediaDriverContext
        }
    }

    private val closeRequested = atomic(false)

    private var aeron: Aeron? = null

    private var mediaDriver: MediaDriver? = null

    private var context: MediaDriver.Context? = null

    // the context is validated before the AeronDriver object is created
    private var threadFactory = NamedThreadFactory("Thread", ThreadGroup("${type.simpleName}-AeronDriver"), true)

    // did WE start the media driver, or did SOMEONE ELSE start it?
    private val mediaDriverWasAlreadyRunning: Boolean

    /**
     * @return the configured driver timeout
     */
    val driverTimeout: Long
        get() {
            return context!!.driverTimeoutMs()
        }

    /**
     * This is only valid **AFTER** [context.concludeAeronDirectory()] has been called.
     */
    val driverDirectory: File
        get() {
            return context!!.aeronDirectory()
        }


    /**
     * This is the error handler for Aeron *SPECIFIC* error messages.
     */
    private val aeronErrorHandler: (error: Throwable) -> Unit

    init {
        config.validate() // this happens more than once! (this is ok)
        setConfigDefaults(config, logger)

        // cannot make any more changes to the configuration!
        config.contextDefined = true

        val aeronErrorFilter = config.aeronErrorFilter
        aeronErrorHandler = { error ->
            if (aeronErrorFilter(error)) {
                ListenerManager.cleanStackTrace(error)
                aeronErrorLogger(AeronDriverException(error))
            }
        }

        context = createContext()
        mediaDriverWasAlreadyRunning = isRunning()
    }

    /**
     * Creates the Aeron Media Driver context
     *
     * @throws IllegalStateException if the configuration has already been used to create a context
     * @throws IllegalArgumentException if the aeron media driver directory cannot be setup
     */
    private fun createContext(): MediaDriver.Context {
        if (threadFactory.group.isDestroyed) {
            // on close, we destroy the threadfactory -- and on driver restart, we might want it back
            threadFactory = NamedThreadFactory("Thread", ThreadGroup("${type.simpleName}-AeronDriver"), true)
        }

        var context = create(config, threadFactory, aeronErrorHandler)

        // this happens EXACTLY once. Must be BEFORE the "isRunning" check!
        context.concludeAeronDirectory()

        // will setup the aeron directory or throw IllegalArgumentException if it cannot be configured
        val aeronDir = context.aeronDirectory()

        val driverTimeout = context.driverTimeoutMs()

        // sometimes when starting up, if a PREVIOUS run was corrupted (during startup, for example)
        // we ONLY do this during the initial startup check because it will delete the directory, and we don't
        // always want to do this.
        var isRunning = try {
                context.isDriverActive(driverTimeout) { }
            } catch (e: DriverTimeoutException) {
                // we have to delete the directory, since it was corrupted, and we try again.
                if (aeronDir.deleteRecursively()) {
                    context.isDriverActive(driverTimeout) { }
                } else {
                    // unable to delete the directory
                    throw e
                }
            }

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

                context = create(config, threadFactory, aeronErrorHandler)
                context.aeronDirectoryName(newDir.path)

                // this happens EXACTLY once. Must be BEFORE the "isRunning" check!
                context.concludeAeronDirectory()

                isRunning = context.isDriverActive(driverTimeout) { }
            }

            if (!isRunning) {
                // NOTE: We must be *super* careful trying to delete directories, because if we have multiple AERON/MEDIA DRIVERS connected to the
                //   same directory, deleting the directory will cause any other aeron connection to fail! (which makes sense).
                // since we are forcing a unique directory, we should ALSO delete it when we are done!
                context.dirDeleteOnShutdown()
            }
        }

        logger.info { "Aeron directory: '${context.aeronDirectory()}'" }

        return context
    }


    /**
     * If the driver is not already running, this will start the driver
     *
     * @throws Exception if there is a problem starting the media driver
     */
    @Synchronized
    fun start() {
        if (closeRequested.value) {
            logger.debug("Resetting media driver context")

            // close was requested previously. we have to reset a few things
            context = createContext()

            // we want to be able to "restart" aeron, as necessary, after a [close] method call
            closeRequested.value = false
        }

        restart()
    }

    /**
     * if we did NOT close, then will restart the media driver + aeron. If we manually closed aeron, then we won't try to restart
     */
    private fun restart() {
        if (closeRequested.value) {
            return
        }

        val didStartDriver = startDriver()
        startAeron(didStartDriver)
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

            var running = isRunning()
            if (running) {
                // wait for a bit, because we are running, but we ALSO issued a START, and expect it to start.
                // SOMETIMES aeron is in the middle of shutting down, and this prevents us from trying to connect to
                // that instance
                logger.debug("Aeron Media driver already running. Double checking status...")
                sleep(driverTimeout/2)
                running = isRunning()
            }

            if (!running) {
                logger.debug("Starting Aeron Media driver.")

                // try to start. If we start/stop too quickly, it's a problem
                var count = 10
                while (count-- > 0) {
                    try {
                        mediaDriver = MediaDriver.launch(context)
                        return true
                    } catch (e: Exception) {
                        logger.warn(e) { "Unable to start the Aeron Media driver at ${driverDirectory}. Retrying $count more times..." }
                        sleep(driverTimeout)
                    }
                }
            } else {
                logger.debug("Not starting Aeron Media driver. It was already running.")
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

    private fun setupAeron(): Aeron.Context {
        val aeronDriverContext = Aeron.Context()
        aeronDriverContext
            .aeronDirectoryName(driverDirectory.path)
            .concludeAeronDirectory()

        aeronDriverContext
            .threadFactory(threadFactory)
            .idleStrategy(BackoffIdleStrategy())

        // we DO NOT want to abort the JVM if there are errors.
        // this replaces the default handler with one that doesn't abort the JVM
        aeronDriverContext.errorHandler { error ->
            aeronErrorHandler(AeronDriverException(error))
        }

        return aeronDriverContext
    }

    /**
     * @return the aeron media driver log file for a specific publication. This should be removed when a publication is closed (but is not always!)
     */
    @Synchronized
    fun getMediaDriverPublicationFile(publicationRegId: Long): File {
        return driverDirectory.resolve("publications").resolve("${publicationRegId}.logbuffer")
    }

    @Synchronized
    fun addPublicationWithRetry(publicationUri: ChannelUriStringBuilder, streamId: Int): Publication {
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

                // if exceptions are added here, make sure to ALSO suppress them in the context error handler
                if (e is DriverTimeoutException) {
                    sleep(driverTimeout)
                }

                if (e.cause is BindException) {
                    // was starting too fast!
                    sleep(driverTimeout)
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
                    restart()
                } catch (e2: Exception) {
                    // ignored
                }
            }
        }

        throw exception!!
    }

    @Synchronized
    fun addSubscriptionWithRetry(subscriptionUri: ChannelUriStringBuilder, streamId: Int): Subscription {
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
                logger.warn { "Unable to add a subscription to Aeron. Retrying $count more times..." }

                // if exceptions are added here, make sure to ALSO suppress them in the context error handler
                if (e is DriverTimeoutException) {
                    sleep(driverTimeout)
                }

                if (e.cause is BindException) {
                    // was starting too fast!
                    sleep(driverTimeout)
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
                    restart()
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
    @Synchronized
    fun isRunning(timeout: Long = context!!.driverTimeoutMs()): Boolean {
        // if the media driver is running, it will be a quick connection. Usually 100ms or so
        return context!!.isDriverActive(timeout) { }
    }

    /**
     * A safer way to try to close the media driver
     *
     * NOTE: We must be *super* careful trying to delete directories, because if we have multiple AERON/MEDIA DRIVERS connected to the
     *   same directory, deleting the directory will cause any other aeron connection to fail! (which makes sense).
     */
    @Synchronized
    fun close() {
        closeRequested.value = true

        try {
            aeron?.close()
        } catch (e: Exception) {
            logger.error("Error stopping aeron.", e)
        }

        aeron = null

        val mediaDriver = mediaDriver
        this@AeronDriver.mediaDriver = null

        if (mediaDriver == null) {
            logger.debug("No driver started for this instance. Not Stopping.")
            return
        }

        if (mediaDriverWasAlreadyRunning) {
            logger.debug("We did not start the media driver, so we are not stopping it.")
            return
        }

        logger.debug("Stopping driver at '${driverDirectory}'...")

        if (!isRunning()) {
            // not running
            logger.debug { "Driver is not running at '${driverDirectory}' for this context. Not Stopping." }
            return
        }

        try {
            mediaDriver.close()

            // make sure the context is also closed.
            context!!.close()

            // it can actually close faster, if everything is ideal.
            if (isRunning()) {
                // on close, we want to wait for the driver to timeout before considering it "closed". Connections can still LINGER (see below)
                // on close, the publication CAN linger (in case a client goes away, and then comes back)
                    // AERON_PUBLICATION_LINGER_TIMEOUT, 5s by default (this can also be set as a URI param)
                sleep(driverTimeout + AERON_PUBLICATION_LINGER_TIMEOUT)
            }

            // wait for the media driver to actually stop
            var count = 10
            while (--count >= 0 && isRunning()) {
                logger.warn { "Aeron Media driver at '${driverDirectory}' is still running. Waiting for it to stop. Trying to close $count more times." }
                sleep(driverTimeout)
            }
            logger.debug { "Closed the media driver at '${driverDirectory}'" }

            val deletedAeron = driverDirectory.deleteRecursively()
            if (!deletedAeron) {
                logger.error { "Error deleting aeron directory $driverDirectory on shutdown "}
            }
        } catch (e: Exception) {
            logger.error("Error closing the media driver at '${driverDirectory}'", e)
        }

        // Destroys this thread group and all of its subgroups.
        // This thread group must be empty, indicating that all threads that had been in this thread group have since stopped.
        threadFactory.group.destroy()
    }
}
