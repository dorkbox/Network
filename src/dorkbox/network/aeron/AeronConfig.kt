package dorkbox.network.aeron

import dorkbox.network.Configuration
import dorkbox.util.NamedThreadFactory
import io.aeron.driver.MediaDriver
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mu.KLogger
import mu.KotlinLogging
import java.io.File

/**
 *
 */
object AeronConfig {
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

        context
            .aeronDirectoryName(config.aeronDirectory!!.absolutePath)

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
    fun isRunning(context: MediaDriver.Context): Boolean {
        // if the media driver is running, it will be a quick connection. Usually 100ms or so
        return context.isDriverActive(context.driverTimeoutMs()) { }
    }

    /**
     * If the driver is not already running, this will start the driver
     *
     * @throws Exception if there is a problem starting the media driver
     */
    fun startDriver(config: Configuration,
                    type: Class<*> = AeronConfig::class.java,
                    logger: KLogger = KotlinLogging.logger("AeronConfig")): MediaDriver? {

        config.validate()

        if (config.context == null) {
            createContext(config, logger)
        }

        require(config.context != null) { "Configuration context cannot be properly created. Unable to continue!" }

        val context = config.context!!

        if (!isRunning(context)) {
            logger.debug("Starting Aeron Media driver in '${context.aeronDirectory()}'")

            val threadFactory = NamedThreadFactory("Thread", ThreadGroup("${type.simpleName}-AeronDriver"), true)
            context
                .conductorThreadFactory(threadFactory)
                .receiverThreadFactory(threadFactory)
                .senderThreadFactory(threadFactory)
                .sharedNetworkThreadFactory(threadFactory)
                .sharedThreadFactory(threadFactory)


            // try to start. If we start/stop too quickly, it's a problem
            var count = 10
            while (count-- > 0) {
                try {
                    return MediaDriver.launch(context)
                } catch (e: Exception) {
                    logger.warn(e) { "Unable to start the Aeron Media driver. Retrying $count more times..." }
                    runBlocking {
                        delay(context.driverTimeoutMs())
                    }
                }
            }
        } else {
            logger.debug("Not starting Aeron Media driver. It was already running in '${context.aeronDirectory()}'")
        }

        return null
    }

    /**
     * A safer way to try to close the media driver
     *
     * NOTE: We must be *super* careful trying to delete directories, because if we have multiple AERON/MEDIA DRIVERS connected to the
     *   same directory, deleting the directory will cause any other aeron connection to fail! (which makes sense).
     */
    internal fun stopDriver(mediaDriver: MediaDriver?, logger: KLogger = KotlinLogging.logger("AeronConfig")) {
        if (mediaDriver == null) {
            return
        }

        val context = mediaDriver.context()
        if (!isRunning(context)) {
            // not running
            return
        }

        try {
            mediaDriver.close()
            (context.sharedThreadFactory() as NamedThreadFactory).group.destroy()

            // wait for the media driver to actually stop
            var count = 10
            while (count-- >= 0 && isRunning(context)) {
                logger.warn { "Aeron Media driver still running. Waiting for it to stop. Trying $count more times." }
                runBlocking {
                    delay(context.driverTimeoutMs())
                }
            }
        } catch (e: Exception) {
            logger.error("Error closing the media driver", e)
        }

        logger.debug { "Closed the media driver at '${context.aeronDirectory()}'" }
    }
}
