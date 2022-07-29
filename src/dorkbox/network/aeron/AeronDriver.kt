package dorkbox.network.aeron

import dorkbox.network.Configuration
import dorkbox.network.connection.ListenerManager
import dorkbox.network.exceptions.AeronDriverException
import dorkbox.network.exceptions.ClientRetryException
import io.aeron.Aeron
import io.aeron.ChannelUriStringBuilder
import io.aeron.CncFileDescriptor
import io.aeron.Publication
import io.aeron.Subscription
import io.aeron.driver.MediaDriver
import io.aeron.samples.SamplesUtil
import kotlinx.atomicfu.atomic
import mu.KLogger
import mu.KotlinLogging
import org.agrona.DirectBuffer
import org.agrona.SemanticVersion
import org.agrona.concurrent.BackoffIdleStrategy
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor
import org.agrona.concurrent.status.CountersReader
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.Thread.sleep

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

        // prevents multiple instances, within the same JVM, from starting at the exact same time.
        // since this is "global" and cannot be run in parallel, we DO NOT use coroutines!
        private val lock = arrayOf(0)
        private val mediaDriverUsageCount = atomic(0)
        private val aeronClientUsageCount = atomic(0)

        private fun setConfigDefaults(config: Configuration, logger: KLogger) {
            // explicitly don't set defaults if we already have the context defined!
            if (config.contextDefined) {
                return
            }

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
                config.sendBufferSize = io.aeron.driver.Configuration.SOCKET_SNDBUF_LENGTH_DEFAULT
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

        private fun aeronCounters(aeronLocation: File): CountersReader? {
            val resolve = aeronLocation.resolve("cnc.dat")
            return if (resolve.exists()) {
                val cncByteBuffer = SamplesUtil.mapExistingFileReadOnly(resolve)
                val cncMetaDataBuffer: DirectBuffer = CncFileDescriptor.createMetaDataBuffer(cncByteBuffer)

               CountersReader(
                    CncFileDescriptor.createCountersMetaDataBuffer(cncByteBuffer, cncMetaDataBuffer),
                    CncFileDescriptor.createCountersValuesBuffer(cncByteBuffer, cncMetaDataBuffer)
                )
            } else {
                null
            }
        }

        /**
         * @return the internal counters of the Aeron driver in the specified aeron directory
         */
        fun driverCounters(aeronLocation: File, counterFunction: (counterId: Int, counterValue: Long, typeId: Int, keyBuffer: DirectBuffer?, label: String?) -> Unit) {
            val countersReader = aeronCounters(aeronLocation)
            countersReader?.forEach { counterId: Int, typeId: Int, keyBuffer: DirectBuffer?, label: String? ->
                val counterValue = countersReader.getCounterValue(counterId)
                counterFunction(counterId, counterValue, typeId, keyBuffer, label)
            }
        }

        /**
         * @return the backlog statistics for the Aeron driver
         */
        fun driverBacklog(aeronLocation: File): BacklogStat? {
            val countersReader = aeronCounters(aeronLocation)

            return if (countersReader != null) {
                BacklogStat(countersReader)
            } else {
                null
            }
        }

        /**
         * @return the internal heartbeat of the Aeron driver in the specified aeron directory
         */
        fun driverHeartbeatMs(aeronLocation: File): Long {
            val cncByteBuffer = SamplesUtil.mapExistingFileReadOnly(aeronLocation.resolve("cnc.dat"))
            val cncMetaDataBuffer: DirectBuffer = CncFileDescriptor.createMetaDataBuffer(cncByteBuffer)

            val toDriverBuffer = CncFileDescriptor.createToDriverBuffer(cncByteBuffer, cncMetaDataBuffer);
            val timestampOffset = toDriverBuffer.capacity() - RingBufferDescriptor.TRAILER_LENGTH + RingBufferDescriptor.CONSUMER_HEARTBEAT_OFFSET

            return toDriverBuffer.getLongVolatile(timestampOffset)
        }

        /**
         * @return the internal version of the Aeron driver in the specified aeron directory
         */
        fun driverVersion(aeronLocation: File): String {
            val cncByteBuffer = SamplesUtil.mapExistingFileReadOnly(aeronLocation.resolve("cnc.dat"))
            val cncMetaDataBuffer: DirectBuffer = CncFileDescriptor.createMetaDataBuffer(cncByteBuffer)

            val cncVersion = cncMetaDataBuffer.getInt(CncFileDescriptor.cncVersionOffset(0))
            val cncSemanticVersion = SemanticVersion.toString(cncVersion);

            return cncSemanticVersion
        }
    }



    @Volatile
    private var aeron: Aeron? = null
    private var mediaDriver: MediaDriver? = null


    // did WE start the media driver, or did SOMEONE ELSE start it?
    private var mediaDriverWasAlreadyRunning = false


    /**
     * This is the error handler for Aeron *SPECIFIC* error messages.
     */
    private val aeronErrorHandler: (error: Throwable) -> Unit

    @Volatile
    private var context_: AeronContext? = null
    private val context: AeronContext
        get() {
            if (context_ == null) {
                context_ = AeronContext(config, type, logger, aeronErrorHandler)
            }

            return context_!!
        }


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
    }

    /**
     * If the driver is not already running, this will start the driver. This will ALSO connect to the aeron client
     *
     * @return true if we are successfully connected to the aeron client
     */
    fun start(): Boolean {
        synchronized(lock) {
            val mediaDriverLoaded = mediaDriverWasAlreadyRunning || mediaDriver != null
            val isLoaded = mediaDriverLoaded && aeron != null && aeron?.isClosed == false
            if (isLoaded) {
                return true
            }

            if (!mediaDriverWasAlreadyRunning && mediaDriver == null) {
                // only start if we didn't already start... There will be several checks.

                var running = isRunning()
                if (running) {
                    // wait for a bit, because we are running, but we ALSO issued a START, and expect it to start.
                    // SOMETIMES aeron is in the middle of shutting down, and this prevents us from trying to connect to
                    // that instance
                    logger.debug { "Aeron Media driver already running. Double checking status..." }
                    sleep(context.driverTimeout/2)
                    running = isRunning()
                }

                if (!running) {
                    logger.debug { "Starting Aeron Media driver." }

                    // try to start. If we start/stop too quickly, it's a problem
                    var count = 10
                    while (count-- > 0) {
                        try {
                            mediaDriver = MediaDriver.launch(context.context)
                            logger.debug { "Started the Aeron Media driver." }
                            mediaDriverUsageCount.getAndIncrement()
                            break
                        } catch (e: Exception) {
                            logger.warn(e) { "Unable to start the Aeron Media driver at ${context.driverDirectory}. Retrying $count more times..." }
                            sleep(context.driverTimeout)
                        }
                    }
                } else {
                    mediaDriverWasAlreadyRunning = true
                    logger.debug { "Not starting Aeron Media driver. It was already running." }
                }

                // if we were unable to load the aeron driver, don't continue.
                if (!running && mediaDriver == null) {
                    logger.error { "Not running and unable to start the Aeron Media driver at ${context.driverDirectory}." }
                    return false
                }
            }
        }

        // the media driver MIGHT already be started in a different process!
        //
        // We still ALWAYS want to connect to aeron (which connects to the other media driver process), especially if we
        // haven't already connected to it (or if there was an error connecting because a different media driver was shutting down)

        val aeronDriverContext = Aeron.Context()
        aeronDriverContext
            .aeronDirectoryName(context.driverDirectory.path)
            .concludeAeronDirectory()

        aeronDriverContext
            .threadFactory(context.threadFactory)
            .idleStrategy(BackoffIdleStrategy())

        // we DO NOT want to abort the JVM if there are errors.
        // this replaces the default handler with one that doesn't abort the JVM
        aeronDriverContext.errorHandler { error ->
            aeronErrorHandler(AeronDriverException(error))
        }
        aeronDriverContext.subscriberErrorHandler { error ->
            aeronErrorHandler(error)
        }



        // this might succeed if we can connect to the media driver
        aeron = Aeron.connect(aeronDriverContext)
        logger.debug { "Connected to Aeron driver." }
        aeronClientUsageCount.getAndIncrement()

        return true
    }

    fun addPublication(publicationUri: ChannelUriStringBuilder, streamId: Int): Publication {
        val uri = publicationUri.build()

        // reasons we cannot add a pub/sub to aeron
        // 1) the driver was closed
        // 2) aeron was unable to connect to the driver
        // 3) the address already in use

        // configuring pub/sub to aeron is LINEAR -- and it happens in 2 places.
        // 1) starting up the client/server
        // 2) creating a new client-server connection pair (the media driver won't be "dead" at this point)

        // in the client, if we are unable to connect to the server, we will attempt to start the media driver + connect to aeron

        val aeron1 = aeron
        if (aeron1 == null || aeron1.isClosed) {
            // there was an error connecting to the aeron client or media driver.
            val ex = ClientRetryException("Error adding a publication to aeron")
            ListenerManager.cleanAllStackTrace(ex)
            throw ex
        }

        val publication = aeron1.addPublication(uri, streamId)
        if (publication == null) {
            // there was an error connecting to the aeron client or media driver.
            val ex = ClientRetryException("Error adding a publication to the remote endpoint")
            ListenerManager.cleanAllStackTrace(ex)
            throw ex
        }

        return publication
    }

    fun addSubscription(subscriptionUri: ChannelUriStringBuilder, streamId: Int): Subscription {
        val uri = subscriptionUri.build()

        // reasons we cannot add a pub/sub to aeron
        // 1) the driver was closed
        // 2) aeron was unable to connect to the driver
        // 3) the address already in use

        // configuring pub/sub to aeron is LINEAR -- and it happens in 2 places.
        // 1) starting up the client/server
        // 2) creating a new client-server connection pair (the media driver won't be "dead" at this point)

        // in the client, if we are unable to connect to the server, we will attempt to start the media driver + connect to aeron


        // subscriptions do not depend on a response from the remote endpoint, and should always succeed if aeron is available

        val aeron1 = aeron
        if (aeron1 == null || aeron1.isClosed) {
            // there was an error connecting to the aeron client or media driver.
            val ex = ClientRetryException("Error adding a subscription to aeron")
            ListenerManager.cleanAllStackTrace(ex)
            throw ex
        }

        val subscription = aeron1.addSubscription(uri, streamId)
        if (subscription == null) {
            // there was an error connecting to the aeron client or media driver.
            val ex = ClientRetryException("Error adding a subscription to the remote endpoint")
            ListenerManager.cleanAllStackTrace(ex)
            throw ex
        }
        return subscription
    }

    /**
     * Checks to see if an endpoint (using the specified configuration) is running.
     *
     * @return true if the media driver is active and running
     */
    fun isRunning(): Boolean {
        // if the media driver is running, it will be a quick connection. Usually 100ms or so
        return context.isRunning()
    }

    /**
     * A safer way to try to close the media driver if in the ENTIRE JVM, our process is the only one using aeron with it's specific configuration
     *
     * NOTE: We must be *super* careful trying to delete directories, because if we have multiple AERON/MEDIA DRIVERS connected to the
     *   same directory, deleting the directory will cause any other aeron connection to fail! (which makes sense).
     */
    fun closeIfSingle() {
        if (!mediaDriverWasAlreadyRunning && aeronClientUsageCount.value == 1 && mediaDriverUsageCount.value == 1) {
            close()
        }
    }


    /**
     * A safer way to try to close the media driver
     *
     * NOTE: We must be *super* careful trying to delete directories, because if we have multiple AERON/MEDIA DRIVERS connected to the
     *   same directory, deleting the directory will cause any other aeron connection to fail! (which makes sense).
     */
    fun close() {
        synchronized(lock) {
            try {
                aeron?.close()
                aeronClientUsageCount.getAndDecrement()
            } catch (e: Exception) {
                logger.error(e) { "Error stopping aeron." }
            }

            aeron = null

            if (mediaDriver == null) {
                logger.debug { "No driver started for this instance. Not Stopping." }
                return
            }

            if (mediaDriverWasAlreadyRunning) {
                logger.debug { "We did not start the media driver, so we are not stopping it." }
                return
            }


            logger.debug { "Stopping driver at '${context.driverDirectory}'..." }

            if (!isRunning()) {
                // not running
                logger.debug { "Driver is not running at '${context.driverDirectory}' for this context. Not Stopping." }
                return
            }

            // if we are the ones that started the media driver, then we must be the ones to close it
            try {
                mediaDriverUsageCount.getAndDecrement()
                mediaDriver!!.close()
            } catch (e: Exception) {
                logger.error(e) { "Error closing the Aeron media driver" }
            }

            mediaDriver = null

            // it can actually close faster, if everything is ideal.
            if (isRunning()) {
                // on close, we want to wait for the driver to timeout before considering it "closed". Connections can still LINGER (see below)
                // on close, the publication CAN linger (in case a client goes away, and then comes back)
                    // AERON_PUBLICATION_LINGER_TIMEOUT, 5s by default (this can also be set as a URI param)
                sleep(context.driverTimeout + AERON_PUBLICATION_LINGER_TIMEOUT)
            }

            // wait for the media driver to actually stop
            var count = 10
            while (--count >= 0 && isRunning()) {
                logger.warn { "Aeron Media driver at '${context.driverDirectory}' is still running. Waiting for it to stop. Trying to close $count more times." }
                sleep(context.driverTimeout)
            }
            logger.debug { "Closed the media driver at '${context.driverDirectory}'" }

            try {
            } catch (e: Exception) {
                logger.error(e) {"Error closing the media driver at '${context.driverDirectory}'" }
            }

            // make sure the context is also closed.
            context.close()
            try {
                val deletedAeron = context.driverDirectory.deleteRecursively()
                if (!deletedAeron) {
                    logger.error { "Error deleting aeron directory ${context.driverDirectory} on shutdown "}
                }
            } catch (e: Exception) {
                logger.error(e) { "Error deleting Aeron directory at: ${context.driverDirectory}"}
            }

            context_ = null
        }
    }

    /**
     * @return the aeron driver timeout
     */
    fun driverTimeout(): Long {
        return context.driverTimeout
    }


    /**
     * @return the aeron media driver log file for a specific publication. This should be removed when a publication is closed (but is not always!)
     */
    fun getMediaDriverPublicationFile(publicationRegId: Long): File {
        return context.driverDirectory.resolve("publications").resolve("${publicationRegId}.logbuffer")
    }

    /**
     * @return the internal counters of the Aeron driver in the current aeron directory
     */
    fun driverCounters(counterFunction: (counterId: Int, counterValue: Long, typeId: Int, keyBuffer: DirectBuffer?, label: String?) -> Unit) {
        driverCounters(context.driverDirectory, counterFunction)
    }

    /**
     * @return the backlog statistics for the Aeron driver
     */
    fun driverBacklog(): BacklogStat? {
        return driverBacklog(context.driverDirectory)
    }

    /**
     * @return the internal heartbeat of the Aeron driver in the current aeron directory
     */
    fun driverHeartbeatMs(): Long {
        return driverHeartbeatMs(context.driverDirectory)
    }

    /**
     * @return the internal version of the Aeron driver in the current aeron directory
     */
    fun driverVersion(): String {
        return driverVersion(context.driverDirectory)
    }

    /**
     * @return the current aeron context info, if any
     */
    fun contextInfo(): String {
        return context.toString()
    }

    /**
     * @return the publication linger timeout. With IPC connections, another publication WITHIN the linger timeout will
     *         cause errors inside of Aeron
     */
    fun getLingerNs(): Long {
        return context.context.publicationLingerTimeoutNs()
    }
}
