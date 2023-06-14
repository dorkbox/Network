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
import io.aeron.ChannelUriStringBuilder
import io.aeron.CncFileDescriptor
import io.aeron.CommonContext
import io.aeron.Publication
import io.aeron.Subscription
import io.aeron.driver.reports.LossReportReader
import io.aeron.driver.reports.LossReportUtil
import io.aeron.samples.SamplesUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KLogger
import mu.KotlinLogging
import org.agrona.DirectBuffer
import org.agrona.IoUtil
import org.agrona.SemanticVersion
import org.agrona.concurrent.AtomicBuffer
import org.agrona.concurrent.UnsafeBuffer
import org.agrona.concurrent.errors.ErrorLogReader
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor
import org.agrona.concurrent.status.CountersReader
import org.slf4j.Logger
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.*

fun ChannelUriStringBuilder.endpoint(isIpv4: Boolean, addressString: String, port: Int): ChannelUriStringBuilder {
    this.endpoint(AeronDriver.address(isIpv4, addressString, port))
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

        const val UDP_HANDSHAKE_STREAM_ID: Int = 0x1337cafe  // 322423550
        const val IPC_HANDSHAKE_STREAM_ID: Int = 0x1337c0de  // 322420958

        // guarantee that session/stream ID's will ALWAYS be unique! (there can NEVER be a collision!)
        val sessionIdAllocator = RandomId65kAllocator(AeronDriver.RESERVED_SESSION_ID_LOW, AeronDriver.RESERVED_SESSION_ID_HIGH)
        val streamIdAllocator = RandomId65kAllocator((Short.MAX_VALUE * 2) - 1) // this is 65k-1 values


        // prevents multiple instances, within the same JVM, from starting at the exact same time.
        private val lock = Mutex()

        // have to keep track of configurations and drivers, as we do not want to start the same media driver configuration multiple times (this causes problems!)
        internal val driverConfigurations = IntMap<AeronDriverInternal>(4)


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
                        logger.error { "Aeron Driver [${driver.driverId}]: still running during check" }
                        return@withLock false
                    }
                }

                if (!traceEnabled) {
                    // this is already checked if we are in trace mode.
                    driverConfigurations.forEach { entry ->
                        val driver = entry.value
                        if (driver.isInUse(logger)) {
                            logger.error { "Aeron Driver [${driver.driverId}]: still in use during check" }
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
         * exposes the Aeron driver loss statistics
         *
         * @return the number of errors for the Aeron driver
         */
        fun driverErrors(aeronLocation: File, errorAction: (observationCount: Int, firstObservationTimestamp: Long, lastObservationTimestamp: Long, encodedException: String) -> Unit): Int {
            val errorMmap = SamplesUtil.mapExistingFileReadOnly(aeronLocation.resolve("cnc.dat"))

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
                val mappedByteBuffer = SamplesUtil.mapExistingFileReadOnly(lossReportFile)
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
            val cncByteBuffer = SamplesUtil.mapExistingFileReadOnly(aeronLocation.resolve("cnc.dat"))
            val cncMetaDataBuffer: DirectBuffer = CncFileDescriptor.createMetaDataBuffer(cncByteBuffer)

            val toDriverBuffer = CncFileDescriptor.createToDriverBuffer(cncByteBuffer, cncMetaDataBuffer)
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
        fun getLocalAddressString(publication: Publication, remoteAddress: InetAddress): String {
            val localAddresses = publication.localSocketAddresses().first()
            val splitPoint = localAddresses.lastIndexOf(':')
            var localAddressString = localAddresses.substring(0, splitPoint)

            return if (remoteAddress is Inet6Address) {
                // this is necessary to clean up the address when adding it to aeron, since different formats mess it up
                // aeron IPv6 addresses all have [...]
                localAddressString = localAddressString.substring(1, localAddressString.length-1)
                IPv6.toString(IPv6.toAddress(localAddressString)!!)
            } else {
                localAddressString
            }
        }


    }


    private val logEverything = endPoint != null
    internal val internal: AeronDriverInternal

    init {
        // not EVERYTHING is used for the media driver. For ** REUSING ** the media driver, only care about those specific settings
        val mediaDriverConfig = Configuration.MediaDriverConfig(config)

        // this happens more than once! (this is ok)
        config.validate()
        mediaDriverConfig.validate()

        require(!config.contextDefined) { "Aeron configuration has already been initialized, unable to reuse this configuration!" }

        // cannot make any more changes to the configuration!
        config.initialize(logger)

        val driverId = mediaDriverConfig.id

        // technically possible, but practically unlikely because of the different values calculated
        require(driverId != 0) { "There has been a severe error when calculating the media configuration ID. Aborting" }

        // assign the driver for this configuration. THIS IS GLOBAL for a JVM, because for a specific configuration, aeron only needs to be initialized ONCE.
        // we have INSTANCE of the "wrapper" AeronDriver, because we want to be able to have references to the logger when doing things,
        // however - the code that actually does stuff is a "singleton" in regard to an aeron configuration
        internal = runBlocking {
            lock.withLock {
                var driver = driverConfigurations.get(driverId)
                if (driver == null) {
                    driver = AeronDriverInternal(endPoint, mediaDriverConfig)

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
    suspend fun addPublicationWithTimeout(
        publicationUri: ChannelUriStringBuilder,
        handshakeTimeoutSec: Int,
        streamId: Int,
        logInfo: String,
        onErrorHandler: suspend (Throwable) -> Exception
    ): Publication {

        val publication = try {
            addPublication(publicationUri, streamId, logInfo)
        } catch (e: Exception) {
            val exception = onErrorHandler(e)
            exception.cleanAllStackTrace()
            throw exception
        }

        // always include the linger timeout, so we don't accidentally kill ourselves by taking too long
        val timeoutInNanos = TimeUnit.SECONDS.toNanos(handshakeTimeoutSec.toLong()) + getLingerNs()
        val startTime = System.nanoTime()

        while (System.nanoTime() - startTime < timeoutInNanos) {
            if (publication.isConnected) {
                return publication
            }

            delay(200L)
        }

        closeAndDeletePublication(publication, logInfo)

        val exception = onErrorHandler(Exception("Aeron Driver [${internal.driverId}]:Publication timed out in $handshakeTimeoutSec seconds while waiting for connection state: $publicationUri streamId=$streamId"))
        exception.cleanAllStackTrace()
        throw exception
    }


    suspend fun addPublication(publicationUri: ChannelUriStringBuilder, streamId: Int, logInfo: String): Publication {
        return internal.addPublication(logger, publicationUri, streamId, logInfo)
    }

    /**
     * This is not a thread-safe publication!
     */
    suspend fun addExclusivePublication(publicationUri: ChannelUriStringBuilder, streamId: Int, logInfo: String): Publication {
        return internal.addExclusivePublication(logger, publicationUri, streamId, logInfo)
    }

    suspend fun addSubscription(subscriptionUri: ChannelUriStringBuilder, streamId: Int, logInfo: String): Subscription {
        return internal.addSubscription(logger, subscriptionUri, streamId, logInfo)
    }

    /**
     * Guarantee that the publication is closed AND the backing file is removed.
     *
     * On close, the publication CAN linger (in case a client goes away, and then comes back)
     * AERON_PUBLICATION_LINGER_TIMEOUT, 5s by default (this can also be set as a URI param)
     *
     * This can throw exceptions!
     */
    suspend fun closeAndDeletePublication(publication: Publication, logInfo: String) {
        internal.closeAndDeletePublication(publication, logger, logInfo)
    }

    /**
     * Guarantee that the publication is closed AND the backing file is removed
     *
     * This can throw exceptions!
     */
    suspend fun closeAndDeleteSubscription(subscription: Subscription, logInfo: String) {
        internal.closeAndDeleteSubscription(subscription, logger, logInfo)
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
     * Checks to see if an endpoint (using the specified configuration) was previously closed.
     *
     * @return true if the media driver was explicitly closed
     */
    suspend fun closed() = internal.closed()

    suspend fun isInUse(): Boolean = internal.isInUse(logger)

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
     * @return the publication linger timeout. With IPC connections, another publication WITHIN the linger timeout will
     *         cause errors inside of Aeron
     */
    fun getLingerNs(): Long = internal.getLingerNs()

    /**
     * Make sure that we DO NOT approach the Aeron linger timeout!
     */
    suspend fun delayLingerTimeout(multiplier: Number) = internal.delayLingerTimeout(multiplier.toDouble())

    /**
     * A safer way to try to close the media driver if in the ENTIRE JVM, our process is the only one using aeron with it's specific configuration
     *
     * NOTE: We must be *super* careful trying to delete directories, because if we have multiple AERON/MEDIA DRIVERS connected to the
     *   same directory, deleting the directory will cause any other aeron connection to fail! (which makes sense).
     */
    suspend fun closeIfSingle() = lock.withLock {
        if (!isInUse()) {
            if (logEverything) {
                internal.close(endPoint, logger)
            } else {
                internal.close(endPoint, Configuration.NOP_LOGGER)
            }
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
     */
    suspend fun close() = lock.withLock {
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
