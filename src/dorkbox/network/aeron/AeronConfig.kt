package dorkbox.network.aeron

import dorkbox.network.Configuration
import dorkbox.util.NamedThreadFactory
import io.aeron.Publication
import io.aeron.driver.MediaDriver
import mu.KLogger
import mu.KotlinLogging
import java.io.File

/**
 *
 */
internal object AeronConfig {
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


    fun errorCodeName(result: Long): String {
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

    private fun create(config: Configuration, logger: KLogger = KotlinLogging.logger("AeronConfig")): MediaDriver.Context {
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
        val context = MediaDriver.Context()
            .publicationReservedSessionIdLow(RESERVED_SESSION_ID_LOW)
            .publicationReservedSessionIdHigh(RESERVED_SESSION_ID_HIGH)
            .conductorThreadFactory(threadFactory)
            .receiverThreadFactory(threadFactory)
            .senderThreadFactory(threadFactory)
            .sharedNetworkThreadFactory(threadFactory)
            .sharedThreadFactory(threadFactory)
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

        val aeronDir = File(context.aeronDirectoryName()).absoluteFile
        context.aeronDirectoryName(aeronDir.path)

        return context
    }

    /**
     * Creates the Aeron Media Driver context
     *
     * @throws IllegalArgumentException if the aeron media driver directory cannot be setup
     */
    fun createContext(config: Configuration, logger: KLogger = KotlinLogging.logger("AeronConfig")): MediaDriver.Context {
        var context = create(config, logger)

        // will setup the aeron directory or throw IllegalArgumentException if it cannot be configured
        var aeronDir = context.aeronDirectory()

        // this happens EXACTLY once. Must be BEFORE the "isRunning" check!
        context.concludeAeronDirectory()

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
        }

        aeronDir = context.aeronDirectory()


        // make sure we start over!
        if (!isRunning && aeronDir.exists()) {
            // try to delete the dir
            if (!aeronDir.deleteRecursively()) {
                logger.warn { "Unable to delete the aeron directory $aeronDir. Aeron was not running when this was attempted." }
            }
        }

        return context
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
}
