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

@file:Suppress("MemberVisibilityCanBePrivate")

package dorkbox.network.aeron

import dorkbox.collections.IntMap
import dorkbox.netUtil.IPv6
import dorkbox.network.Configuration
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.ListenerManager.Companion.cleanAllStackTrace
import dorkbox.network.exceptions.AllocationException
import dorkbox.network.handshake.RandomId65kAllocator
import dorkbox.util.Sys
import io.aeron.*
import io.aeron.driver.reports.LossReportReader
import io.aeron.driver.reports.LossReportUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KLogger
import mu.KotlinLogging
import org.agrona.DirectBuffer
import org.agrona.IoUtil
import org.agrona.LangUtil
import org.agrona.SemanticVersion
import org.agrona.concurrent.AtomicBuffer
import org.agrona.concurrent.UnsafeBuffer
import org.agrona.concurrent.errors.ErrorLogReader
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor
import org.agrona.concurrent.status.CountersReader
import org.slf4j.Logger
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

fun ChannelUriStringBuilder.endpoint(isIpv4: Boolean, addressString: String, port: Int): ChannelUriStringBuilder {
    this.endpoint(AeronDriver.address(isIpv4, addressString, port))
    return this
}
 fun ChannelUriStringBuilder.controlEndpoint(isIpv4: Boolean, addressString: String, port: Int): ChannelUriStringBuilder {
    this.controlEndpoint(AeronDriver.address(isIpv4, addressString, port))
    return this
}

/**
 * Class for managing the Aeron+Media drivers
 */
class AeronDriver private constructor(config: Configuration, val logger: KLogger, val endPoint: EndPoint<*>?) {

    constructor(config: Configuration, logger: KLogger) : this(config, logger, null)

    constructor(endPoint: EndPoint<*>) : this(endPoint.config, endPoint.logger, endPoint)

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

        // guarantee that session/stream ID's will ALWAYS be unique! (there can NEVER be a collision!)
        val sessionIdAllocator = RandomId65kAllocator(RESERVED_SESSION_ID_LOW, RESERVED_SESSION_ID_HIGH)
        val streamIdAllocator = RandomId65kAllocator((Short.MAX_VALUE * 2) - 1) // this is 65k-1 values


        // prevents multiple instances, within the same JVM, from starting at the exact same time.
        private val lock = Mutex()

        // have to keep track of configurations and drivers, as we do not want to start the same media driver configuration multiple times (this causes problems!)
        internal val driverConfigurations = IntMap<AeronDriverInternal>(4)


        /**
         * Ensures that an endpoint (using the specified configuration) is NO LONGER running.
         *
         * @return true if the media driver is STOPPED.
         */
        suspend fun ensureStopped(configuration: Configuration, logger: KLogger, timeout: Long): Boolean {
            if (!isLoaded(configuration.copy(), logger)) {
                return true
            }

            val stopped = AeronDriver(configuration, logger, null).use {
                it.ensureStopped(timeout, 500)
            }

            // hacky, but necessary for multiple checks
            configuration.contextDefined = false
            return stopped
        }

        /**
         * Checks to see if a driver (using the specified configuration) is currently loaded. This specifically does NOT check if the
         * driver is active/running!!
         *
         * @return true if the media driver is loaded.
         */
        suspend fun isLoaded(configuration: Configuration, logger: KLogger): Boolean {
            // not EVERYTHING is used for the media driver. For ** REUSING ** the media driver, only care about those specific settings
            val mediaDriverConfig = getDriverConfig(configuration, logger)

            // assign the driver for this configuration. THIS IS GLOBAL for a JVM, because for a specific configuration, aeron only needs to be initialized ONCE.
            // we have INSTANCE of the "wrapper" AeronDriver, because we want to be able to have references to the logger when doing things,
            // however - the code that actually does stuff is a "singleton" in regard to an aeron configuration
            return lock.withLock {
                driverConfigurations.get(mediaDriverConfig.id) != null
            }
        }

        /**
         * Checks to see if a driver (using the specified configuration) is running.
         *
         * @return true if the media driver is active and running
         */
        suspend fun isRunning(configuration: Configuration, logger: KLogger): Boolean {
            val running = AeronDriver(configuration, logger).use {
                it.isRunning()
            }

            return running
        }

        /**
         * @return true if all JVM tracked Aeron drivers are closed, false otherwise
         */
        suspend fun areAllInstancesClosed(logger: Logger): Boolean {
            val logger1 = KotlinLogging.logger(logger)
            return areAllInstancesClosed(logger1)
        }

        /**
         * @return true if all JVM tracked Aeron drivers are closed, false otherwise
         */
        suspend fun areAllInstancesClosed(logger: KLogger = KotlinLogging.logger(AeronDriver::class.java.simpleName)): Boolean {
            return lock.withLock {
                val traceEnabled = logger.isTraceEnabled

                driverConfigurations.forEach { entry ->
                    val driver = entry.value
                    val closed = if (traceEnabled) driver.isInUse(logger) else driver.isRunning()

                    if (closed) {
                        logger.error { "Aeron Driver [${driver.driverId}]: still running during check (${driver.aeronDirectory})" }
                        return@withLock false
                    }
                }

                if (!traceEnabled) {
                    // this is already checked if we are in trace mode.
                    driverConfigurations.forEach { entry ->
                        val driver = entry.value
                        if (driver.isInUse(logger)) {
                            logger.error { "Aeron Driver [${driver.driverId}]: still in use during check (${driver.aeronDirectory})" }
                            return@withLock false
                        }
                    }
                }

                true
            }
        }

        private fun aeronCounters(aeronLocation: File): CountersReader? {
            val resolve = aeronLocation.resolve("cnc.dat")
            return if (resolve.exists()) {
                val cncByteBuffer = mapExistingFileReadOnly(resolve)
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
         * exposes the Aeron driver loss statistics
         *
         * @return the number of errors for the Aeron driver
         */
        fun driverErrors(aeronLocation: File, errorAction: (observationCount: Int, firstObservationTimestamp: Long, lastObservationTimestamp: Long, encodedException: String) -> Unit): Int {
            val errorMmap = mapExistingFileReadOnly(aeronLocation.resolve("cnc.dat"))

            try {
                val buffer: AtomicBuffer = CommonContext.errorLogBuffer(errorMmap)

                return ErrorLogReader.read(buffer) {
                        observationCount: Int, firstObservationTimestamp: Long, lastObservationTimestamp: Long, encodedException: String ->

                    errorAction(observationCount, firstObservationTimestamp, lastObservationTimestamp, encodedException)
                }
            } finally {
                IoUtil.unmap(errorMmap)
            }
        }

        /**
         * exposes the Aeron driver loss statistics
         *
         * @return the number of loss statistics for the Aeron driver
         */
        fun driverLossStats(aeronLocation: File, lossStats: (observationCount: Long,
                                                             totalBytesLost: Long,
                                                             firstObservationTimestamp: Long,
                                                             lastObservationTimestamp: Long,
                                                             sessionId: Int, streamId: Int,
                                                             channel: String, source: String) -> Unit): Int {

            val lossReportFile = aeronLocation.resolve(LossReportUtil.LOSS_REPORT_FILE_NAME)
            return if (lossReportFile.exists()) {
                val mappedByteBuffer = mapExistingFileReadOnly(lossReportFile)
                val buffer: AtomicBuffer = UnsafeBuffer(mappedByteBuffer)

                LossReportReader.read(buffer, lossStats)
            } else {
                0
            }
        }

        /**
         * @return the internal heartbeat of the Aeron driver in the specified aeron directory
         */
        fun driverHeartbeatMs(aeronLocation: File): Long {
            val cncByteBuffer = mapExistingFileReadOnly(aeronLocation.resolve("cnc.dat"))
            val cncMetaDataBuffer: DirectBuffer = CncFileDescriptor.createMetaDataBuffer(cncByteBuffer)

            val toDriverBuffer = CncFileDescriptor.createToDriverBuffer(cncByteBuffer, cncMetaDataBuffer)
            val timestampOffset = toDriverBuffer.capacity() - RingBufferDescriptor.TRAILER_LENGTH + RingBufferDescriptor.CONSUMER_HEARTBEAT_OFFSET

            return toDriverBuffer.getLongVolatile(timestampOffset)
        }

        /**
         * @return the internal version of the Aeron driver in the specified aeron directory
         */
        fun driverVersion(aeronLocation: File): String {
            val cncByteBuffer = mapExistingFileReadOnly(aeronLocation.resolve("cnc.dat"))
            val cncMetaDataBuffer: DirectBuffer = CncFileDescriptor.createMetaDataBuffer(cncByteBuffer)

            val cncVersion = cncMetaDataBuffer.getInt(CncFileDescriptor.cncVersionOffset(0))
            val cncSemanticVersion = SemanticVersion.toString(cncVersion);

            return cncSemanticVersion
        }

        /**
         * Validates that all the resources have been freed (for all connections)
         *
         * note: CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
         */
        fun checkForMemoryLeaks() {
            val sessionCounts = sessionIdAllocator.counts()
            val streamCounts = streamIdAllocator.counts()

            if (sessionCounts > 0 || streamCounts > 0) {
                throw AllocationException("Unequal allocate/free method calls for session/stream allocation: \n" +
                                                  "\tsession counts: $sessionCounts \n" +
                                                  "\tstream counts: $streamCounts"
                )
            }
        }

        fun uri(type: String, sessionId: Int, isReliable: Boolean): ChannelUriStringBuilder {
            val builder = ChannelUriStringBuilder().media(type)
            builder.reliable(isReliable)

            // if a subscription has a session ID, then a publication MUST MATCH for it to connect (even if it has the correct stream ID/port)
            builder.sessionId(sessionId)

            return builder
        }

        /**
         * Do not use a session ID when we are a handshake connection!
         */
        fun uriHandshake(type: String, isReliable: Boolean): ChannelUriStringBuilder {
            val builder = ChannelUriStringBuilder().media(type)
            builder.reliable(isReliable)

            return builder
        }

        fun address(isIpv4: Boolean, addressString: String, port: Int): String {
            return if (isIpv4) {
                "$addressString:$port"
            } else if (addressString[0] == '[') {
                // IPv6 requires the address to be bracketed by [...]
                "$addressString:$port"
            } else {
                // there MUST be [] surrounding the IPv6 address for aeron to like it!
                "[$addressString]:$port"
            }
        }

        /**
         * This will return the local-address of the interface that connects with the remote address (instead of on ALL interfaces)
         */
        fun getLocalAddressString(publication: Publication, isRemoteIpv4: Boolean): String {
            val localSocketAddress = publication.localSocketAddresses()
            if (localSocketAddress == null || localSocketAddress.isEmpty()) {
                throw Exception("The local socket address for the publication ${publication.channel()} is null/empty.")
            }

            val localAddresses = localSocketAddress.first()
            val splitPoint = localAddresses.lastIndexOf(':')
            var localAddressString = localAddresses.substring(0, splitPoint)

            return if (isRemoteIpv4) {
                localAddressString
            } else {
                // this is necessary to clean up the address when adding it to aeron, since different formats mess it up
                // aeron IPv6 addresses all have [...]
                localAddressString = localAddressString.substring(1, localAddressString.length-1)
                IPv6.toString(IPv6.toAddress(localAddressString)!!)
            }
        }

        /**
         * This will return the local-address of the interface that connects with the remote address (instead of on ALL interfaces)
         */
        fun getLocalAddressString(subscription: Subscription): String {
            val localSocketAddress = subscription.localSocketAddresses()
            if (localSocketAddress == null || localSocketAddress.isEmpty()) {
                throw Exception("The local socket address for the subscription ${subscription.channel()} is null/empty.")
            }

            val addressesAndPorts = localSocketAddress.first()
            val splitPoint2 = addressesAndPorts.lastIndexOf(':')
            return addressesAndPorts.substring(0, splitPoint2)
        }



        internal fun getDriverConfig(config: Configuration, logger: KLogger): Configuration.MediaDriverConfig {
            val mediaDriverConfig = Configuration.MediaDriverConfig(config)

            // this happens more than once! (this is ok)
            config.validate()

            mediaDriverConfig.validate()

            require(!config.contextDefined) { "Aeron configuration has already been initialized, unable to reuse this configuration!" }

            // cannot make any more changes to the configuration!
            config.initialize(logger)

            // technically possible, but practically unlikely because of the different values calculated
            require(mediaDriverConfig.id != 0) { "There has been a severe error when calculating the media configuration ID. Aborting" }

            return mediaDriverConfig
        }

        /**
         * Map an existing file as a read only buffer.
         *
         * @param location of file to map.
         * @return the mapped file.
         */
        fun mapExistingFileReadOnly(location: File): MappedByteBuffer? {
            if (!location.exists()) {
                val msg = "file not found: " + location.absolutePath
                throw IllegalStateException(msg)
            }
            var mappedByteBuffer: MappedByteBuffer? = null
            try {
                RandomAccessFile(location, "r").use { file ->
                    file.channel.use { channel ->
                        mappedByteBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                    }
                }
            } catch (ex: IOException) {
                LangUtil.rethrowUnchecked(ex)
            }
            return mappedByteBuffer
        }
    }


    private val logEverything = endPoint != null
    internal val internal: AeronDriverInternal

    init {
        // not EVERYTHING is used for the media driver. For ** REUSING ** the media driver, only care about those specific settings
        val mediaDriverConfig = getDriverConfig(config, logger)

        // assign the driver for this configuration. THIS IS GLOBAL for a JVM, because for a specific configuration, aeron only needs to be initialized ONCE.
        // we have INSTANCE of the "wrapper" AeronDriver, because we want to be able to have references to the logger when doing things,
        // however - the code that actually does stuff is a "singleton" in regard to an aeron configuration
        internal = runBlocking {
            lock.withLock {
                val driverId = mediaDriverConfig.id

                var driver = driverConfigurations.get(driverId)
                if (driver == null) {
                    driver = AeronDriverInternal(endPoint, mediaDriverConfig, logger)

                    driverConfigurations.put(driverId, driver)

                    // register a logger so that we are notified when there is an error in Aeron
                    driver.addError {
                        logger.error(this) { "Aeron Driver [$driverId]: error!" }
                    }

                    if (logEverything) {
                        logger.debug { "Aeron Driver [$driverId]: Creating at '${driver.aeronDirectory}'" }
                    }
                } else {
                    if (logEverything) {
                        logger.debug { "Aeron Driver [$driverId]: Reusing driver" }
                    }

                    // assign our endpoint to the driver
                    driver.addEndpoint(endPoint)
                }

                driver
            }
        }
    }

    /**
     * This does TWO things
     *  - start the media driver if not already running
     *  - connect the aeron client to the running media driver
     *
     *  @return true if we are successfully connected to the aeron client
     */
    suspend fun start()= lock.withLock {
        internal.start(logger)
    }

    /**
     * For publications, if we add them "too quickly" (faster than the 'linger' timeout), Aeron will throw exceptions.
     * ESPECIALLY if it is with the same streamID
     *
     * The Aeron.addPublication method will block until the Media Driver acknowledges the request or a timeout occurs.
     *
     * this check is in the "reconnect" logic
     */
    suspend fun waitForConnection(
        publication: Publication,
        handshakeTimeoutNs: Long,
        logInfo: String,
        onErrorHandler: suspend (Throwable) -> Exception
    ) {
        if (publication.isConnected) {
            return
        }

        val startTime = System.nanoTime()

        while (System.nanoTime() - startTime < handshakeTimeoutNs) {
            if (publication.isConnected) {
                return
            }

            delay(200L)
        }

        close(publication, logInfo)

        val exception = onErrorHandler(Exception("Aeron Driver [${internal.driverId}]: Publication timed out in ${Sys.getTimePrettyFull(handshakeTimeoutNs)} while waiting for connection state: ${publication.channel()} streamId=${publication.streamId()}"))
        exception.cleanAllStackTrace()
        throw exception
    }

    /**
     * Add a [ConcurrentPublication] for publishing messages to subscribers.
     *
     * This guarantees that the publication is added and ACTIVE
     *
     * The publication returned is thread-safe.
     */
    suspend fun addPublication(publicationUri: ChannelUriStringBuilder, streamId: Int, logInfo: String, isIpc: Boolean): Publication {
        return internal.addPublication(logger, publicationUri, streamId, logInfo, isIpc)
    }

    /**
     * Add an [ExclusivePublication] for publishing messages to subscribers from a single thread.
     *
     * This guarantees that the publication is added and ACTIVE
     *
     * This is not a thread-safe publication!
     */
    suspend fun addExclusivePublication(publicationUri: ChannelUriStringBuilder, streamId: Int, logInfo: String, isIpc: Boolean): Publication {
        return internal.addExclusivePublication(logger, publicationUri, streamId, logInfo, isIpc)
    }

    /**
     * Add a new [Subscription] for subscribing to messages from publishers.
     *
     * This guarantees that the subscription is added and ACTIVE
     *
     * The method will set up the [Subscription] to use the
     * {@link Aeron.Context#availableImageHandler(AvailableImageHandler)} and
     * {@link Aeron.Context#unavailableImageHandler(UnavailableImageHandler)} from the {@link Aeron.Context}.
     */
    suspend fun addSubscription(subscriptionUri: ChannelUriStringBuilder, streamId: Int, logInfo: String, isIpc: Boolean): Subscription {
        return internal.addSubscription(logger, subscriptionUri, streamId, logInfo, isIpc)
    }

    /**
     * Guarantee that the publication is closed AND the backing file is removed.
     *
     * On close, the publication CAN linger (in case a client goes away, and then comes back)
     * AERON_PUBLICATION_LINGER_TIMEOUT, 5s by default (this can also be set as a URI param)
     *
     * This can throw exceptions!
     */
    suspend fun close(publication: Publication, logInfo: String) {
        internal.close(publication, logger, logInfo)
    }

    /**
     * Guarantee that the publication is closed AND the backing file is removed
     *
     * This can throw exceptions!
     */
    suspend fun close(subscription: Subscription, logInfo: String) {
        internal.close(subscription, logger, logInfo)
    }


    /**
     * Ensures that an endpoint (using the specified configuration) is NO LONGER running.
     *
     * @return true if the media driver is STOPPED.
     */
    suspend fun ensureStopped(timeoutMS: Long, intervalTimeoutMS: Long): Boolean =
        internal.ensureStopped(timeoutMS, intervalTimeoutMS, logger)

    /**
     * Checks to see if an endpoint (using the specified configuration) is running.
     *
     * @return true if the media driver is active and running
     */
    fun isRunning(): Boolean = internal.isRunning()

    /**
     * Deletes the entire context of the aeron directory in use.
     */
    fun deleteAeronDir() = internal.deleteAeronDir()

    /**
     * Checks to see if an endpoint (using the specified configuration) was previously closed.
     *
     * @return true if the media driver was explicitly closed
     */
    suspend fun closed() = internal.closed()

    suspend fun isInUse(): Boolean = internal.isInUse(logger)

    /**
     * @return the aeron media driver log file for a specific publication.
     */
    fun getMediaDriverFile(publication: Publication): File {
        return internal.getMediaDriverFile(publication)
    }

    /**
     * @return the aeron media driver log file for a specific image (within a subscription, an image is the "connection" with a publication).
     */
    fun getMediaDriverFile(image: Image): File {
        return internal.getMediaDriverFile(image)
    }

    /**
     * Deletes the logfile for this publication
     */
    fun deleteLogFile(publication: Publication) {
        internal.deleteLogFile(publication)
    }

    /**
     * Deletes the logfile for this image (within a subscription, an image is the "connection" with a publication).
     */
    fun deleteLogFile(image: Image) {
        internal.deleteLogFile(image)
    }

    /**
     * expose the internal counters of the Aeron driver
     */
    fun driverCounters(counterFunction: (counterId: Int, counterValue: Long, typeId: Int, keyBuffer: DirectBuffer?, label: String?) -> Unit) =
        internal.driverCounters(counterFunction)

    /**
     * @return the backlog statistics for the Aeron driver
     */
    fun driverBacklog(): BacklogStat? = internal.driverBacklog()

    /**
     * @return the internal heartbeat of the Aeron driver in the current aeron directory
     */
    fun driverHeartbeatMs(): Long = internal.driverHeartbeatMs()


    /**
     * exposes the Aeron driver loss statistics
     *
     * @return the number of errors for the Aeron driver
     */
    fun driverErrors(errorAction: (observationCount: Int, firstObservationTimestamp: Long, lastObservationTimestamp: Long, encodedException: String) -> Unit) =
        internal.driverErrors(errorAction)

    /**
     * exposes the Aeron driver loss statistics
     *
     * @return the number of loss statistics for the Aeron driver
     */
    fun driverLossStats(lossStats: (observationCount: Long,
                                    totalBytesLost: Long,
                                    firstObservationTimestamp: Long,
                                    lastObservationTimestamp: Long,
                                    sessionId: Int, streamId: Int,
                                    channel: String, source: String) -> Unit): Int =
        internal.driverLossStats(lossStats)

    /**
     * @return the internal version of the Aeron driver in the current aeron directory
     */
    fun driverVersion(): String = internal.driverVersion()

    /**
     * @return the current aeron context info, if any
     */
    fun contextInfo(): String = internal.contextInfo()

    /**
     * @return Time in nanoseconds a publication will linger once it is drained to recover potential tail loss.
     */
    fun lingerNs(): Long = internal.lingerNs()

    /**
     * @return Time in nanoseconds a publication will be considered not connected if no status messages are received.
     */
    fun publicationConnectionTimeoutNs(): Long {
        return internal.publicationConnectionTimeoutNs()
    }

    /**
     * Make sure that we DO NOT approach the Aeron linger timeout!
     */
    suspend fun delayLingerTimeout(multiplier: Number) = internal.delayLingerTimeout(multiplier.toDouble())

    /**
     * A safer way to try to close the media driver if in the ENTIRE JVM, our process is the only one using aeron with it's specific configuration
     *
     * NOTE: We must be *super* careful trying to delete directories, because if we have multiple AERON/MEDIA DRIVERS connected to the
     *   same directory, deleting the directory will cause any other aeron connection to fail! (which makes sense).
     *
     * @return true if the driver was successfully stopped.
     */
    suspend fun closeIfSingle(): Boolean = lock.withLock {
        if (!isInUse()) {
            if (logEverything) {
                internal.close(endPoint, logger)
            } else {
                internal.close(endPoint, Configuration.NOP_LOGGER)
            }
        } else {
            false
        }
    }

    override fun toString(): String {
        return internal.toString()
    }

    /**
     * A safer way to try to close the media driver
     *
     * NOTE: We must be *super* careful trying to delete directories, because if we have multiple AERON/MEDIA DRIVERS connected to the
     *   same directory, deleting the directory will cause any other aeron connection to fail! (which makes sense).
     *
     * @return true if the driver was successfully stopped.
     */
    suspend fun close(): Boolean = lock.withLock {
        if (logEverything) {
            internal.close(endPoint, logger)
        } else {
            internal.close(endPoint, Configuration.NOP_LOGGER)
        }
    }

    suspend fun <R> use(block: suspend (AeronDriver) -> R): R {
        return try {
            block(this)
        } finally {
            close()
        }
    }
}
