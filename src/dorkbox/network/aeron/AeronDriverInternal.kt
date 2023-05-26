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

package dorkbox.network.aeron

import dorkbox.network.Configuration
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.ListenerManager
import dorkbox.network.connection.ListenerManager.Companion.cleanAllStackTrace
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTrace
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTraceInternal
import dorkbox.network.exceptions.AeronDriverException
import dorkbox.network.exceptions.ClientRetryException
import io.aeron.Aeron
import io.aeron.ChannelUriStringBuilder
import io.aeron.Publication
import io.aeron.Subscription
import io.aeron.driver.MediaDriver
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KLogger
import mu.KotlinLogging
import org.agrona.DirectBuffer
import org.agrona.concurrent.BackoffIdleStrategy
import java.io.File
import java.util.concurrent.TimeUnit

internal class AeronDriverInternal(endPoint: EndPoint<*>?, private val config: Configuration.MediaDriverConfig) {
    companion object {
        // on close, the publication CAN linger (in case a client goes away, and then comes back)
        // AERON_PUBLICATION_LINGER_TIMEOUT, 5s by default (this can also be set as a URI param)
        private const val AERON_PUBLICATION_LINGER_TIMEOUT = 5_000L  // in MS

        private val driverLogger = KotlinLogging.logger(AeronDriver::class.java.simpleName)

        private val onErrorGlobalList = atomic(Array<Throwable.() -> Unit>(0) { { } })
        private val onErrorGlobalMutex = Mutex()

        /**
         * Called when there is an Aeron error
         */
        suspend fun onError(function: Throwable.() -> Unit) {
            onErrorGlobalMutex.withLock {
                // we have to follow the single-writer principle!
                onErrorGlobalList.lazySet(ListenerManager.add(function, onErrorGlobalList.value))
            }
        }

        private suspend fun removeOnError(function: Throwable.() -> Unit) {
            onErrorGlobalMutex.withLock {
                // we have to follow the single-writer principle!
                onErrorGlobalList.lazySet(ListenerManager.remove(function, onErrorGlobalList.value))
            }
        }


        /**
         * Invoked when there is a global error (no connection information)
         *
         * The error is also sent to an error log before notifying callbacks
         */
        fun notifyError(exception: Throwable) {
            onErrorGlobalList.value.forEach {
                try {
                    driverLogger.error(exception) { "Aeron error!" }
                    it(exception)
                } catch (t: Throwable) {
                    // NOTE: when we remove stuff, we ONLY want to remove the "tail" of the stacktrace, not ALL parts of the stacktrace
                    t.cleanStackTrace()
                    driverLogger.error("Global error with Aeron", t)
                }
            }
        }
    }

    val driverId = config.id

    private val endPointUsages = mutableListOf<EndPoint<*>>()

    @Volatile
    private var aeron: Aeron? = null
    private var mediaDriver: MediaDriver? = null

    private val onErrorLocalList = mutableListOf<Throwable.() -> Unit>()
    private val onErrorLocalMutex = Mutex()

    private val context: AeronContext
    private val aeronErrorHandler: (Throwable) -> Unit

    private val pubSubs = atomic(0)

    private val stateMutex = Mutex()


    private var closed = false
    suspend fun closed(): Boolean = stateMutex.withLock {
        return closed
    }

    val aeronDirectory: File
        get() {
            return context.context.aeronDirectory()
        }

    init {
        // configure the aeron error handler
        val filter = config.aeronErrorFilter
        aeronErrorHandler = { error ->
            if (filter(error)) {
                error.cleanStackTrace()
                // send this out to the listener-manager so we can be notified of global errors
                notifyError(AeronDriverException(error))
            }
        }

        // @throws IllegalStateException if the configuration has already been used to create a context
        // @throws IllegalArgumentException if the aeron media driver directory cannot be setup
        context = AeronContext(config, aeronErrorHandler)

        addEndpoint(endPoint)
    }

    fun addEndpoint(endPoint: EndPoint<*>?) {
        if (endPoint != null) {
            if (!endPointUsages.contains(endPoint)) {
                endPointUsages.add(endPoint)
            }
        }
    }


    suspend fun addError(function: Throwable.() -> Unit) {
        // always add this to the global one
        onError(function)

        // this is so we can track all the added error listeners (and removed them when we close, since the DRIVER has a global list)
        onErrorLocalMutex.withLock {
            onErrorLocalList.add(function)
        }
    }

    private suspend fun removeErrors() = onErrorLocalMutex.withLock {
        onErrorLocalList.forEach {
            removeOnError(it)
        }
    }

    /**
     * This does TWO things
     *  - start the media driver if not already running
     *  - connect the aeron client to the running media driver
     *
     *  @return true if we are successfully connected to the aeron client
     */
    suspend fun start(logger: KLogger): Boolean = stateMutex.withLock {
        require(!closed) { "Aeron Driver [$driverId]: Cannot start a driver that was closed. A new driver + context must be created" }

        val isLoaded = mediaDriver != null && aeron != null && aeron?.isClosed == false
        if (isLoaded) {
            logger.debug { "Aeron Driver [$driverId]: Already running... Not starting again." }
            return true
        }

        if (mediaDriver == null) {
            // only start if we didn't already start... There will be several checks.

            var running = isRunning()
            if (running) {
                // wait for a bit, because we are running, but we ALSO issued a START, and expect it to start.
                // SOMETIMES aeron is in the middle of shutting down, and this prevents us from trying to connect to
                // that instance
                logger.debug { "Aeron Driver [$driverId]: Already running. Double checking status..." }
                delay(context.driverTimeout / 2)
                running = isRunning()
            }

            if (!running) {
                // try to start. If we start/stop too quickly, it's a problem
                var count = 10
                while (count-- > 0) {
                    try {
                        mediaDriver = MediaDriver.launch(context.context)
                        logger.debug { "Aeron Driver [$driverId]: Successfully started" }
                        break
                    } catch (e: Exception) {
                        logger.warn(e) { "Aeron Driver [$driverId]: Unable to start at ${context.directory}. Retrying $count more times..." }
                        delay(context.driverTimeout)
                    }
                }
            } else {
                logger.debug { "Aeron Driver [$driverId]: Not starting. It was already running." }
            }

            // if we were unable to load the aeron driver, don't continue.
            if (!running && mediaDriver == null) {
                logger.error { "Aeron Driver [$driverId]: Not running and unable to start at ${context.directory}." }
                return false
            }
        }

        // the media driver MIGHT already be started in a different process!
        //
        // We still ALWAYS want to connect to aeron (which connects to the other media driver process), especially if we
        // haven't already connected to it (or if there was an error connecting because a different media driver was shutting down)

        val aeronDriverContext = Aeron.Context()
        aeronDriverContext
            .aeronDirectoryName(context.directory.path)
            .concludeAeronDirectory()

        aeronDriverContext
            .threadFactory(Configuration.aeronThreadFactory)
            .idleStrategy(BackoffIdleStrategy())

        // we DO NOT want to abort the JVM if there are errors.
        // this replaces the default handler with one that doesn't abort the JVM
        aeronDriverContext.errorHandler(aeronErrorHandler)
        aeronDriverContext.subscriberErrorHandler(aeronErrorHandler)

        // this might succeed if we can connect to the media driver
        aeron = Aeron.connect(aeronDriverContext)
        logger.debug { "Aeron Driver [$driverId]: Connected to '${context.directory}'" }

        return true
    }


    fun addPublication(logger: KLogger, publicationUri: ChannelUriStringBuilder, aeronLogInfo: String, streamId: Int): Publication {
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
            val ex = ClientRetryException("Aeron Driver [$driverId]: Error adding a publication to aeron")
            ex.cleanAllStackTrace()
            throw ex
        }

        val publication = try {
            aeron1.addPublication(uri, streamId)
        } catch (e: Exception) {
            // this happens if the aeron media driver cannot actually establish connection... OR IF IT IS TOO FAST BETWEEN ADD AND REMOVE FOR THE SAME SESSION/STREAM ID!
            e.cleanAllStackTrace()
            val ex = ClientRetryException("Aeron Driver [$driverId]: Error adding a publication", e)
            ex.cleanAllStackTrace()
            throw ex
        }

        if (publication == null) {
            // there was an error connecting to the aeron client or media driver.
            val ex = ClientRetryException("Aeron Driver [$driverId]: Error adding a publication")
            ex.cleanAllStackTrace()
            throw ex
        }

        pubSubs.incrementAndGet()
//        logger.trace { "Creating publication ${publication.registrationId()}" }
        logger.trace { "Creating publication $aeronLogInfo :: regId=${publication.registrationId()}, sessionId=${publication.sessionId()}, streamId=${publication.streamId()}" }
        return publication
    }

    /**
     * This is not a thread-safe publication!
     */
    fun addExclusivePublication(logger: KLogger, publicationUri: ChannelUriStringBuilder, aeronLogInfo: String, streamId: Int): Publication {
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
            val ex = ClientRetryException("Aeron Driver [$driverId]: Error adding an ex-publication to aeron")
            ex.cleanAllStackTrace()
            throw ex
        }

        val publication = try {
            aeron1.addExclusivePublication(uri, streamId)
        } catch (e: Exception) {
            // this happens if the aeron media driver cannot actually establish connection... OR IF IT IS TOO FAST BETWEEN ADD AND REMOVE FOR THE SAME SESSION/STREAM ID!
            Thread.sleep(10000)
            try {
                println()
                println("HAD TO WAIT FOR PUBLICATION!")
                println()
                println()

                aeron1.addExclusivePublication(uri, streamId)
            } catch (e: Exception) {
                e.cleanStackTraceInternal()
                e.cause?.cleanStackTraceInternal()
                val ex = ClientRetryException("Error adding a publication", e)
                ex.cleanAllStackTrace()
                throw ex
            }
        }

        if (publication == null) {
            // there was an error connecting to the aeron client or media driver.
            val ex = ClientRetryException("Aeron Driver [$driverId]: Error adding an ex-publication")
            ex.cleanAllStackTrace()
            throw ex
        }

        pubSubs.incrementAndGet()
//        logger.trace { "Creating ex-publication ${publication.registrationId()} :: $type e-pub URI: $uri,stream-id=$streamId" }
        logger.trace { "Creating ex-publication $aeronLogInfo :: regId=${publication.registrationId()}, sessionId=${publication.sessionId()}, streamId=${publication.streamId()}" }
        return publication
    }

    fun addSubscription(logger: KLogger, subscriptionUri: ChannelUriStringBuilder, aeronLogInfo: String, streamId: Int): Subscription {
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
            val ex = ClientRetryException("Aeron Driver [$driverId]: Error adding a subscription to aeron")
            ex.cleanAllStackTrace()
            throw ex
        }

        val subscription = try {
            aeron1.addSubscription(uri, streamId)
        } catch (e: Exception) {
            e.cleanAllStackTrace()
            val ex = ClientRetryException("Aeron Driver [$driverId]: Error adding a subscription", e)
            ex.cleanAllStackTrace()
            throw ex
        }

        if (subscription == null) {
            // there was an error connecting to the aeron client or media driver.
            val ex = ClientRetryException("Aeron Driver [$driverId]: Error adding a subscription")
            ex.cleanAllStackTrace()
            throw ex
        }

        pubSubs.incrementAndGet()
//        logger.trace { "Creating subscription ${subscription.registrationId()} :: $type sub URI: $uri,stream-id=$streamId" }
        logger.trace { "Creating subscription [$aeronLogInfo] :: regId=${subscription.registrationId()}, sessionId=${subscriptionUri.sessionId()}, streamId=${subscription.streamId()}" }
        return subscription
    }

    /**
     * Guarantee that the publication is closed AND the backing file is removed
     */
    suspend fun closeAndDeletePublication(publication: Publication, aeronLogInfo: String, logger: KLogger) {
        logger.trace { "Closing publication [$aeronLogInfo] :: regId=${publication.registrationId()}, sessionId=${publication.sessionId()}, streamId=${publication.streamId()}" }

        try {
            // This can throw exceptions!
            publication.close()
        } catch (e: Exception) {
            logger.error(e) { "Aeron Driver [$driverId]: Unable to close [$aeronLogInfo] publication $publication" }
        }

        ensureLogfileDeleted("publication", getMediaDriverFile(publication), aeronLogInfo, logger)
    }

    /**
     * Guarantee that the publication is closed AND the backing file is removed
     */
    suspend fun closeAndDeleteSubscription(subscription: Subscription, aeronLogInfo: String, logger: KLogger) {
        logger.trace { "Aeron Driver [$driverId]: Closing subscription [$aeronLogInfo] ::regId=${subscription.registrationId()}, sessionId=${subscription.images().firstOrNull()?.sessionId()}, streamId=${subscription.streamId()}" }

        try {
            // This can throw exceptions!
            subscription.close()
        } catch (e: Exception) {
            // wait 3 seconds and try again!
//
//            try {
//                subscription.close()
//            } catch (e: Exception) {
                logger.error(e) { "Unable to close [$aeronLogInfo] subscription $subscription" }
//            }
        }

        ensureLogfileDeleted("subscription", getMediaDriverFile(subscription), aeronLogInfo, logger)
    }

    private suspend fun ensureLogfileDeleted(type: String, logFile: File, aeronLogInfo: String, logger: KLogger) {
        val timeoutInNanos = TimeUnit.SECONDS.toNanos(config.connectionCloseTimeoutInSeconds.toLong())

        val closeTimeoutTime = System.nanoTime()
        while (logFile.exists() && System.nanoTime() - closeTimeoutTime < timeoutInNanos) {
            if (logFile.delete()) {
                break
            }
            delay(100)
        }

        if (logFile.exists()) {
            logger.error("Aeron Driver [$driverId]: [$aeronLogInfo] Unable to delete aeron $type log on close: $logFile")
        }

        pubSubs.decrementAndGet()
    }

    /**
     * Ensures that an endpoint (using the specified configuration) is NO LONGER running.
     *
     * @return true if the media driver is STOPPED.
     */
    suspend fun ensureStopped(timeoutMS: Long, intervalTimeoutMS: Long, logger: KLogger): Boolean {
        if (closed) {
            return true
        }

        val timeoutInNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMS)
        var didLog = false

        val closeTimeoutTime = System.nanoTime()
        while (isRunning() && System.nanoTime() - closeTimeoutTime < timeoutInNanos) {
            // only emit the log info once. It's rather spammy otherwise!
            if (!didLog) {
                didLog = true
                logger.debug("Aeron Driver [$driverId]: Still running. Waiting for it to stop...")
            }
            delay(intervalTimeoutMS)
        }

        return !isRunning()
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

    suspend fun isInUse(logger: KLogger): Boolean {
        // as many "sort-cuts" as we can for checking if the current Aeron Driver/client is still in use
        if (!isRunning()) {
            logger.trace { "Aeron Driver [$driverId]: not running" }
            return false
        }

        val driverId = config.id
        if (pubSubs.value > 0) {
            logger.debug { "Aeron driver [$driverId] still has ${pubSubs.value} pub/subs" }
            return true
        }

        if (endPointUsages.isNotEmpty()) {
            logger.debug { "Aeron Driver [$driverId]: Still referenced by ${endPointUsages.size} endpoints" }
            return true
        }

        // check to see if we ALREADY have loaded this location.
        // null or empty snapshot means that this location is currently unused
        // >0 can also happen because the location is old. It's not running, but still has info because it hasn't been cleaned up yet
        // NOTE: This is only valid information if the media driver is running
        var currentUsage = driverBacklog()?.snapshot()?.size ?: 0
        var count = 3

        while (count > 0 && currentUsage > 0) {
            logger.debug { "Aeron Driver [$driverId]: in use, double checking status" }
            delayTimeout(.5)
            currentUsage = driverBacklog()?.snapshot()?.size ?: 0
            count--
        }

        count = 3
        while (count > 0 && currentUsage > 0) {
            logger.debug { "Aeron Driver [$driverId]: in use, double checking status (long)" }
            delayLingerTimeout()
            currentUsage = driverBacklog()?.snapshot()?.size ?: 0
            count--
        }

        val isInUse = currentUsage > 0
        if (isInUse) {
            logger.debug { "Aeron Driver [$driverId]: usage is: $currentUsage" }
        }
        return isInUse
    }

    /**
     * A safer way to try to close the media driver
     *
     * NOTE: We must be *super* careful trying to delete directories, because if we have multiple AERON/MEDIA DRIVERS connected to the
     *   same directory, deleting the directory will cause any other aeron connection to fail! (which makes sense).
     */
    suspend fun close(endPoint: EndPoint<*>?, logger: KLogger) = stateMutex.withLock {
        val driverId = config.id

        logger.trace { "Aeron Driver [$driverId]: Requested close... (${endPointUsages.size} endpoints in use)" }

        if (endPoint != null) {
            endPointUsages.remove(endPoint)
        }

        if (isInUse(logger)) {
            logger.debug { "Aeron Driver [$driverId]: in use, not shutting down this instance." }
            return
        }

        val removed = AeronDriver.driverConfigurations.remove(driverId)
        if (removed == null) {
            logger.debug { "Aeron Driver [$driverId]: already closed. Ignoring close request." }
            return
        }

        logger.debug { "Aeron Driver [$driverId]: Closing..." }

        // we have to assign context BEFORE we close, because the `getter` for context will create it if necessary
        val aeronContext = context
        val driverDirectory = aeronContext.directory

        try {
            aeron?.close()
        } catch (e: Exception) {
            logger.error(e) { "Aeron Driver [$driverId]: Error stopping!" }
        }

        aeron = null


        if (mediaDriver == null) {
            logger.debug { "Aeron Driver [$driverId]: No driver started, not Stopping." }
            return
        }

        logger.debug { "Aeron Driver [$driverId]: Stopping driver at '${driverDirectory}'..." }

        if (!isRunning()) {
            // not running
            logger.debug { "Aeron Driver [$driverId]: is not running at '${driverDirectory}' for this context. Not Stopping." }
            return
        }

        // if we are the ones that started the media driver, then we must be the ones to close it
        try {
            mediaDriver!!.close()
        } catch (e: Exception) {
            logger.error(e) { "Aeron Driver [$driverId]: Error closing" }
        }

        mediaDriver = null

        // it can actually close faster, if everything is ideal.
        val timeout = (aeronContext.driverTimeout + AERON_PUBLICATION_LINGER_TIMEOUT)/4


        // it can actually close faster, if everything is ideal.
        if (isRunning()) {
            // on close, we want to wait for the driver to timeout before considering it "closed". Connections can still LINGER (see below)
            // on close, the publication CAN linger (in case a client goes away, and then comes back)
            // AERON_PUBLICATION_LINGER_TIMEOUT, 5s by default (this can also be set as a URI param)
            delay(timeout)
        }

        // wait for the media driver to actually stop
        var count = 10
        while (--count >= 0 && isRunning()) {
            logger.warn { "Aeron Driver [$driverId]: still running at '${driverDirectory}'. Waiting for it to stop. Trying to close $count more times." }
            delay(timeout)
        }

        // make sure the context is also closed, but ONLY if we are the last one
        aeronContext.close()

        // make sure all of our message listeners are removed
        removeErrors()

        try {
            val deletedAeron = driverDirectory.deleteRecursively()
            if (!deletedAeron) {
                logger.error { "Aeron Driver [$driverId]: Error deleting aeron directory $driverDirectory on shutdown "}
            }
        } catch (e: Exception) {
            logger.error(e) { "Aeron Driver [$driverId]: Error deleting Aeron directory at: $driverDirectory"}
        }


        if (driverDirectory.isDirectory) {
            logger.error { "Aeron Driver [$driverId]: Error deleting Aeron directory at: $driverDirectory"}
        }

        logger.debug { "Closed the media driver [$driverId] at '${driverDirectory}'" }
        closed = true
    }

    /**
     * @return the aeron driver timeout
     */
    private fun driverTimeout(): Long {
        return context.driverTimeout
    }


    /**
     * @return the aeron media driver log file for a specific publication. This should be removed when a publication is closed (but is not always!)
     */
    private fun getMediaDriverFile(publication: Publication): File {
        return context.directory.resolve("publications").resolve("${publication.registrationId()}.logbuffer")
    }

    /**
     * @return the aeron media driver log file for a specific subscription. This should be removed when a subscription is closed (but is not always!)
     */
    private fun getMediaDriverFile(subscription: Subscription): File {
        return context.directory.resolve("subscriptions").resolve("${subscription.registrationId()}.logbuffer")
    }

    /**
     * expose the internal counters of the Aeron driver
     */
    fun driverCounters(counterFunction: (counterId: Int, counterValue: Long, typeId: Int, keyBuffer: DirectBuffer?, label: String?) -> Unit) {
        AeronDriver.driverCounters(context.directory, counterFunction)
    }

    /**
     * @return the backlog statistics for the Aeron driver
     */
    fun driverBacklog(): BacklogStat? {
        return AeronDriver.driverBacklog(context.directory)
    }

    /**
     * @return the internal heartbeat of the Aeron driver in the current aeron directory
     */
    fun driverHeartbeatMs(): Long {
        return AeronDriver.driverHeartbeatMs(context.directory)
    }


    /**
     * exposes the Aeron driver loss statistics
     *
     * @return the number of errors for the Aeron driver
     */
    fun driverErrors(errorAction: (observationCount: Int, firstObservationTimestamp: Long, lastObservationTimestamp: Long, encodedException: String) -> Unit) {
        AeronDriver.driverErrors(context.directory, errorAction)
    }

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
                                    channel: String, source: String) -> Unit): Int {
        return AeronDriver.driverLossStats(context.directory, lossStats)
    }

    /**
     * @return the internal version of the Aeron driver in the current aeron directory
     */
    fun driverVersion(): String {
        return AeronDriver.driverVersion(context.directory)
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

    /**
     * Make sure that we DO NOT approach the Aeron linger timeout!
     */
    suspend fun delayTimeout(multiplier: Number = 1) {
        delay((driverTimeout() * multiplier.toDouble()).toLong())
    }

    /**
     * Make sure that we DO NOT approach the Aeron linger timeout!
     */
    suspend fun delayLingerTimeout(multiplier: Number = 1) {
        val lingerNs = getLingerNs()
        delay(driverTimeout().coerceAtLeast(TimeUnit.NANOSECONDS.toSeconds((lingerNs * multiplier.toDouble()).toLong())) )
    }
}