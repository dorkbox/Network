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
import dorkbox.network.connection.Connection
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.ListenerManager
import dorkbox.network.connection.ListenerManager.Companion.cleanAllStackTrace
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTrace
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTraceInternal
import dorkbox.network.exceptions.AllocationException
import dorkbox.network.handshake.RandomId65kAllocator
import dorkbox.network.serialization.AeronOutput
import dorkbox.util.Sys
import io.aeron.*
import io.aeron.driver.reports.LossReportReader
import io.aeron.driver.reports.LossReportUtil
import io.aeron.logbuffer.BufferClaim
import io.aeron.protocol.DataHeaderFlyweight
import mu.KLogger
import mu.KotlinLogging
import org.agrona.*
import org.agrona.concurrent.AtomicBuffer
import org.agrona.concurrent.IdleStrategy
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
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

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
class AeronDriver constructor(config: Configuration, val logger: KLogger, val endPoint: EndPoint<*>?) {

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
        private val lock = ReentrantReadWriteLock()

        // have to keep track of configurations and drivers, as we do not want to start the same media driver configuration multiple times (this causes problems!)
        internal val driverConfigurations = IntMap<AeronDriverInternal>(4)

        fun new(endPoint: EndPoint<*>): AeronDriver {
            var driver: AeronDriver?
            lock.write {
                driver = AeronDriver(endPoint.config, endPoint.logger, endPoint)
            }

            return driver!!
        }


        fun withLock(action: () -> Unit) {
            lock.write {
                action()
            }
        }

        /**
         * Ensures that an endpoint (using the specified configuration) is NO LONGER running.
         *
         * @return true if the media driver is STOPPED.
         */
        fun ensureStopped(configuration: Configuration, logger: KLogger, timeout: Long): Boolean {
            if (!isLoaded(configuration.copy(), logger)) {
                return true
            }

            var stopped = false
            lock.write {
                stopped = AeronDriver(configuration, logger, null).use {
                    it.ensureStopped(timeout, 500)
                }
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
        fun isLoaded(configuration: Configuration, logger: KLogger): Boolean {
            // not EVERYTHING is used for the media driver. For ** REUSING ** the media driver, only care about those specific settings
            val mediaDriverConfig = getDriverConfig(configuration, logger)

            // assign the driver for this configuration. THIS IS GLOBAL for a JVM, because for a specific configuration, aeron only needs to be initialized ONCE.
            // we have INSTANCE of the "wrapper" AeronDriver, because we want to be able to have references to the logger when doing things,
            // however - the code that actually does stuff is a "singleton" in regard to an aeron configuration
            return lock.read {
                driverConfigurations[mediaDriverConfig.mediaDriverId()] != null
            }
        }

        /**
         * Checks to see if a driver (using the specified configuration) is running.
         *
         * @return true if the media driver is active and running
         */
        fun isRunning(configuration: Configuration, logger: KLogger): Boolean {
            var running = false
            lock.read {
                running = AeronDriver(configuration, logger, null).use {
                    it.isRunning()
                }
            }

            return running
        }

        /**
         * @return true if all JVM tracked Aeron drivers are closed, false otherwise
         */
        fun areAllInstancesClosed(logger: Logger): Boolean {
            val logger1 = KotlinLogging.logger(logger)
            return areAllInstancesClosed(logger1)
        }

        /**
         * @return true if all JVM tracked Aeron drivers are closed, false otherwise
         */
        fun areAllInstancesClosed(logger: KLogger = KotlinLogging.logger(AeronDriver::class.java.simpleName)): Boolean {
            return lock.read {
                val traceEnabled = logger.isTraceEnabled

                driverConfigurations.forEach { entry ->
                    val driver = entry.value
                    val closed = if (traceEnabled) driver.isInUse(null, logger) else driver.isRunning()

                    if (closed) {
                        logger.error { "Aeron Driver [${driver.driverId}]: still running during check (${driver.aeronDirectory})" }
                        return@read false
                    }
                }

                if (!traceEnabled) {
                    // this is already checked if we are in trace mode.
                    driverConfigurations.forEach { entry ->
                        val driver = entry.value
                        if (driver.isInUse(null, logger)) {
                            logger.error { "Aeron Driver [${driver.driverId}]: still in use during check (${driver.aeronDirectory})" }
                            return@read false
                        }
                    }
                }

                true
            }
        }

        /**
         * @return the error code text for the specified number
         */
        internal fun errorCodeName(result: Long): String {
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

            require(!config.contextDefined) { "Aeron configuration [${config.mediaDriverId()}] has already been initialized, unable to reuse this configuration!" }

            // cannot make any more changes to the configuration!
            config.initialize(logger)

            // technically possible, but practically unlikely because of the different values calculated
            require(mediaDriverConfig.mediaDriverId() != 0) { "There has been a severe error when calculating the media configuration ID. Aborting" }

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
        val driverId = mediaDriverConfig.mediaDriverId()

        logger.info { "Aeron Driver [$driverId]: Initializing..." }
        val aeronDriver = driverConfigurations.get(driverId)
        if (aeronDriver == null) {
            val driver = AeronDriverInternal(endPoint, mediaDriverConfig, logger)

            driverConfigurations.put(driverId, driver)

            // register a logger so that we are notified when there is an error in Aeron
            driver.addError {
                logger.error(this) { "Aeron Driver [$driverId]: error!" }
            }

            if (logEverything) {
                logger.debug { "Aeron Driver [$driverId]: Creating at '${driver.aeronDirectory}'" }
            }

            internal = driver
        } else {
            if (logEverything) {
                logger.debug { "Aeron Driver [$driverId]: Reusing driver" }
            }

            // assign our endpoint to the driver
            aeronDriver.addEndpoint(endPoint)

            internal = aeronDriver
        }
    }


    /**
     * This does TWO things
     *  - start the media driver if not already running
     *  - connect the aeron client to the running media driver
     *
     *  @return true if we are successfully connected to the aeron client
     */
    fun start() = lock.write {
        internal.start(logger)
    }

    /**
     * For publications, if we add them "too quickly" (faster than the 'linger' timeout), Aeron will throw exceptions.
     * ESPECIALLY if it is with the same streamID
     *
     * The Aeron.addPublication method will block until the Media Driver acknowledges the request or a timeout occurs.
     */
    fun waitForConnection(
        publication: Publication,
        handshakeTimeoutNs: Long,
        logInfo: String,
        onErrorHandler: (Throwable) -> Exception
    ) {
        if (publication.isConnected) {
            return
        }

        val startTime = System.nanoTime()

        while (System.nanoTime() - startTime < handshakeTimeoutNs) {
            if (publication.isConnected) {
                return
            }

            Thread.sleep(200L)
        }

        close(publication, logInfo)

        val exception = onErrorHandler(Exception("Aeron Driver [${internal.driverId}]: Publication timed out in ${Sys.getTimePrettyFull(handshakeTimeoutNs)} while waiting for connection state: ${publication.channel()} streamId=${publication.streamId()}"))
        exception.cleanAllStackTrace()
        throw exception
    }

    /**
     * For subscriptions, in the client we want to guarantee that the remote server has connected BACK to us!
     */
    fun waitForConnection(
        subscription: Subscription,
        handshakeTimeoutNs: Long,
        logInfo: String,
        onErrorHandler: (Throwable) -> Exception
    ) {
        if (subscription.isConnected) {
            return
        }

        val startTime = System.nanoTime()

        while (System.nanoTime() - startTime < handshakeTimeoutNs) {
            if (subscription.isConnected && subscription.imageCount() > 0) {
                return
            }

            Thread.sleep(200L)
        }

        close(subscription, logInfo)

        val exception = onErrorHandler(Exception("Aeron Driver [${internal.driverId}]: Subscription timed out in ${Sys.getTimePrettyFull(handshakeTimeoutNs)} while waiting for connection state: ${subscription.channel()} streamId=${subscription.streamId()}"))
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
    fun addPublication(publicationUri: ChannelUriStringBuilder, streamId: Int, logInfo: String, isIpc: Boolean): Publication {
        return internal.addPublication(logger, publicationUri, streamId, logInfo, isIpc)
    }

    /**
     * Add an [ExclusivePublication] for publishing messages to subscribers from a single thread.
     *
     * This guarantees that the publication is added and ACTIVE
     *
     * This is not a thread-safe publication!
     */
    fun addExclusivePublication(publicationUri: ChannelUriStringBuilder, streamId: Int, logInfo: String, isIpc: Boolean): Publication {
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
    fun addSubscription(subscriptionUri: ChannelUriStringBuilder, streamId: Int, logInfo: String, isIpc: Boolean): Subscription {
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
    fun close(publication: Publication, logInfo: String) {
        internal.close(publication, logger, logInfo)
    }

    /**
     * Guarantee that the publication is closed AND the backing file is removed
     *
     * This can throw exceptions!
     */
    fun close(subscription: Subscription, logInfo: String) {
        internal.close(subscription, logger, logInfo)
    }


    /**
     * Ensures that an endpoint (using the specified configuration) is NO LONGER running.
     *
     * @return true if the media driver is STOPPED.
     */
    fun ensureStopped(timeoutMS: Long, intervalTimeoutMS: Long): Boolean =
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
    fun closed() = internal.closed()

    fun isInUse(endPoint: EndPoint<*>?): Boolean = internal.isInUse(endPoint, logger)

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
    fun delayLingerTimeout(multiplier: Number = 1) = internal.delayLingerTimeout(multiplier.toDouble())

    /**
     * A safer way to try to close the media driver if in the ENTIRE JVM, our process is the only one using aeron with it's specific configuration
     *
     * NOTE: We must be *super* careful trying to delete directories, because if we have multiple AERON/MEDIA DRIVERS connected to the
     *   same directory, deleting the directory will cause any other aeron connection to fail! (which makes sense).
     *
     * @return true if the driver was successfully stopped.
     */
    fun closeIfSingle(): Boolean = lock.write {
        if (!isInUse(endPoint)) {
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
    fun close(): Boolean = lock.write {
        if (logEverything) {
            internal.close(endPoint, logger)
        } else {
            internal.close(endPoint, Configuration.NOP_LOGGER)
        }
    }

    fun <R> use(block: (AeronDriver) -> R): R {
        return try {
            block(this)
        } finally {
            close()
        }
    }


    /**
     * NOTE: This cannot be on a coroutine, because our kryo instances are NOT threadsafe!
     *
     * the actual bits that send data on the network.
     *
     * There is a maximum length allowed for messages which is the min of 1/8th a term length or 16MB.
     * Messages larger than this should chunked using an application level chunking protocol. Chunking has better recovery
     * properties from failure and streams with mechanical sympathy.
     *
     * This can be overridden if you want to customize exactly how data is sent on the network
     *
     * @param publication the connection specific publication
     * @param internalBuffer the internal buffer that will be copied to the Aeron network driver
     * @param offset the offset in the internal buffer at which to start copying bytes
     * @param objectSize the number of bytes to copy (starting at the offset)
     * @param connection the connection object
     *
     * @return true if the message was successfully sent by aeron, false otherwise. Exceptions are caught and NOT rethrown!
     */
    internal fun <CONNECTION: Connection> send(
        publication: Publication,
        internalBuffer: MutableDirectBuffer,
        bufferClaim: BufferClaim,
        offset: Int,
        objectSize: Int,
        sendIdleStrategy: IdleStrategy,
        connection: Connection,
        abortEarly: Boolean,
        listenerManager: ListenerManager<CONNECTION>
    ): Boolean {
        var result: Long
        while (true) {
            // The maximum claimable length is given by the maxPayloadLength() function, which is the MTU length less header (with defaults this is 1,376 bytes).
            result = publication.tryClaim(objectSize, bufferClaim)
            if (result >= 0) {
                // success!
                try {
                    // both .offer and .putBytes add bytes to the underlying termBuffer -- HOWEVER, putBytes is faster as there are no
                    // extra checks performed BECAUSE we have to do our own data fragmentation management.
                    // It doesn't make sense to use `.offer`, which ALSO has its own fragmentation handling (which is extra overhead for us)
                    bufferClaim.buffer().putBytes(DataHeaderFlyweight.HEADER_LENGTH, internalBuffer, offset, objectSize)
                } finally {
                    // must commit() or abort() before the unblock timeout (default 15 seconds) occurs.
                    bufferClaim.commit()
                }

                return true
            }

            if (internal.mustRestartDriverOnError) {
                logger.error { "Critical error, not able to send data." }
                // there were critical errors. Don't even try anything! we will reconnect automatically (on the client) when it shuts-down (the connection is closed immediately when an error of this type is encountered

                // aeron will likely report this is as "BACK PRESSURE"
                return false
            }

            /**
             * Since the publication is not connected, we weren't able to send data to the remote endpoint.
             */
            if (result == Publication.NOT_CONNECTED) {
                if (abortEarly) {
                    val exception = endPoint!!.newException(
                        "[${publication.sessionId()}] Unable to send message. (Connection in non-connected state, aborted attempt! ${
                            AeronDriver.errorCodeName(result)
                        })"
                    )
                    listenerManager.notifyError(exception)
                    return false
                }
                else if (publication.isConnected) {
                    // more critical error sending the message. we shouldn't retry or anything.
                    val errorMessage = "[${publication.sessionId()}] Error sending message. (Connection in non-connected state longer than linger timeout. ${AeronDriver.errorCodeName(result)})"

                    // either client or server. No other choices. We create an exception, because it's more useful!
                    val exception = endPoint!!.newException(errorMessage)

                    // +3 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
                    // where we see who is calling "send()"
                    exception.cleanStackTrace(3)
                    listenerManager.notifyError(exception)
                    return false
                }
                else {
                    logger.info { "[${publication.sessionId()}] Connection disconnected while sending data, closing connection." }
                    internal.mustRestartDriverOnError = true

                    // publication was actually closed or the server was closed, so no bother throwing an error
                    connection.closeImmediately(sendDisconnectMessage = false,
                                                notifyDisconnect = true)
                    return false
                }
            }

            /**
             * The publication is not connected to a subscriber, this can be an intermittent state as subscribers come and go.
             *  val NOT_CONNECTED: Long = -1
             *
             * The offer failed due to back pressure from the subscribers preventing further transmission.
             *  val BACK_PRESSURED: Long = -2
             *
             * The offer failed due to an administration action and should be retried.
             * The action is an operation such as log rotation which is likely to have succeeded by the next retry attempt.
             *  val ADMIN_ACTION: Long = -3
             */
            if (result >= Publication.ADMIN_ACTION) {
                // we should retry, BUT we want to block ANYONE ELSE trying to write at the same time!
                sendIdleStrategy.idle()
                continue
            }


            if (result == Publication.CLOSED && connection.isClosed()) {
                // this can happen when we use RMI to close a connection. RMI will (in most cases) ALWAYS send a response when it's
                // done executing. If the connection is *closed* first (because an RMI method closed it), then we will not be able to
                // send the message.

                if (!endPoint!!.shutdownInProgress.value) {
                    // we already know the connection is closed. we closed it (so it doesn't make sense to emit an error about this)
                    val exception = endPoint.newException(
                        "[${publication.sessionId()}] Unable to send message. (Connection is closed, aborted attempt! ${errorCodeName(result)})"
                    )
                    listenerManager.notifyError(exception)
                }
                return false
            }

            // more critical error sending the message. we shouldn't retry or anything.
            val errorMessage = "[${publication.sessionId()}] Error sending message. (${AeronDriver.errorCodeName(result)})"

            // either client or server. No other choices. We create an exception, because it's more useful!
            val exception = endPoint!!.newException(errorMessage)

            // +3 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
            // where we see who is calling "send()"
            exception.cleanStackTrace(3)
            listenerManager.notifyError(exception)
            return false
        }

    }

    /**
     * NOTE: this **MUST** stay on the same co-routine that calls "send". This cannot be re-dispatched onto a different coroutine!
     *       CANNOT be called in action dispatch. ALWAYS ON SAME THREAD
     *       Server -> will be network polling thread
     *       Client -> will be thread that calls `connect()`
     *
     * @return true if the message was successfully sent by aeron
     */
    internal fun <CONNECTION: Connection> send(
        publication: Publication,
        buffer: AeronOutput,
        logInfo: String,
        listenerManager: ListenerManager<CONNECTION>,
        handshakeSendIdleStrategy: IdleStrategy
    ): Boolean {
        val objectSize = buffer.position()
        val internalBuffer = buffer.internalBuffer

        var result: Long
        while (true) {
            result = publication.offer(internalBuffer, 0, objectSize)
            if (result >= 0) {
                // success!
                return true
            }

            if (internal.mustRestartDriverOnError) {
                // there were critical errors. Don't even try anything! we will reconnect automatically (on the client) when it shuts-down (the connection is closed immediately when an error of this type is encountered

                // aeron will likely report this is as "BACK PRESSURE"
                return false
            }

            /**
             * Since the publication is not connected, we weren't able to send data to the remote endpoint.
             *
             * According to Aeron Docs, Pubs and Subs can "come and go", whatever that means. We just want to make sure that we
             * don't "loop forever" if a publication is ACTUALLY closed, like on purpose.
             */
            if (result == Publication.NOT_CONNECTED) {
                if (publication.isConnected) {
                    // more critical error sending the message. we shouldn't retry or anything.
                    // this exception will be a ClientException or a ServerException
                    val exception = endPoint!!.newException(
                        "[$logInfo] Error sending message. (Connection in non-connected state longer than linger timeout. ${errorCodeName(result)})",
                        null
                    )

                    exception.cleanStackTraceInternal()
                    listenerManager.notifyError(exception)
                    throw exception
                }
                else {
                    // publication was actually closed, so no bother throwing an error
                    return false
                }
            }

            /**
             * The publication is not connected to a subscriber, this can be an intermittent state as subscribers come and go.
             *  val NOT_CONNECTED: Long = -1
             *
             * The offer failed due to back pressure from the subscribers preventing further transmission.
             *  val BACK_PRESSURED: Long = -2
             *
             * The offer failed due to an administration action and should be retried.
             * The action is an operation such as log rotation which is likely to have succeeded by the next retry attempt.
             *  val ADMIN_ACTION: Long = -3
             */
            if (result >= Publication.ADMIN_ACTION) {
                // we should retry.
                handshakeSendIdleStrategy.idle()
                continue
            }

            if (result == Publication.CLOSED) {

                if (!endPoint!!.shutdownInProgress.value) {
                    // we already know the connection is closed. we closed it (so it doesn't make sense to emit an error about this)

                    val exception = endPoint.newException(
                        "[${publication.sessionId()}] Unable to send message. (Connection is closed, aborted attempt! ${errorCodeName(result)})"
                    )
                    listenerManager.notifyError(exception)
                }
                return false
            }

            // more critical error sending the message. we shouldn't retry or anything.
            val errorMessage = "[${publication.sessionId()}] Error sending message. (${errorCodeName(result)})"

            // either client or server. No other choices. We create an exception, because it's more useful!
            val exception = endPoint!!.newException(errorMessage)

            // +3 more because we do not need to see the "internals" for sending messages. The important part of the stack trace is
            // where we see who is calling "send()"
            exception.cleanStackTrace(3)
            listenerManager.notifyError(exception)
            return false
        }
    }

    fun newIfClosed(): AeronDriver {
        endPoint!!

        var driver: AeronDriver? = null

        withLock {
            driver = if (closed()) {
                // Only starts the media driver if we are NOT already running!
                try {
                    AeronDriver(endPoint.config, endPoint.logger, endPoint)
                } catch (e: Exception) {
                    throw endPoint.newException("Error initializing aeron driver", e)
                }
            } else {
                this
            }
        }

        return driver!!
    }
}
