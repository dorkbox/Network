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

import dorkbox.collections.LockFreeHashSet
import dorkbox.network.Configuration
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.ListenerManager
import dorkbox.network.connection.ListenerManager.Companion.cleanAllStackTrace
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTrace
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTraceInternal
import dorkbox.network.exceptions.AeronDriverException
import dorkbox.network.exceptions.ClientRetryException
import io.aeron.*
import io.aeron.driver.MediaDriver
import io.aeron.status.ChannelEndpointStatus
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KLogger
import mu.KotlinLogging
import org.agrona.DirectBuffer
import org.agrona.concurrent.BackoffIdleStrategy
import java.io.File
import java.util.concurrent.*

internal class AeronDriverInternal(endPoint: EndPoint<*>?, private val config: Configuration.MediaDriverConfig, logger: KLogger) {
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
                    it(exception)
                } catch (t: Throwable) {
                    // NOTE: when we remove stuff, we ONLY want to remove the "tail" of the stacktrace, not ALL parts of the stacktrace
                    t.cleanStackTrace()
                    driverLogger.error("Global error with Aeron", t)
                }
            }
        }

        init {
            // fix the transport poller for java 17!
            FixTransportPoller.init()
        }
    }

    val driverId = config.mediaDriverId()

    private val endPointUsages = mutableListOf<EndPoint<*>>()

    @Volatile
    private var aeron: Aeron? = null
    private var mediaDriver: MediaDriver? = null

    private val onErrorLocalList = mutableListOf<Throwable.() -> Unit>()
    private val onErrorLocalMutex = Mutex()

    private val context: AeronContext
    private val aeronErrorHandler: (Throwable) -> Unit

    private val registeredPublications = atomic(0)
    private val registeredSubscriptions = atomic(0)

    private val registeredPublicationsTrace: LockFreeHashSet<Long> = LockFreeHashSet()
    private val registeredSubscriptionsTrace: LockFreeHashSet<Long> = LockFreeHashSet()

    private val stateMutex = Mutex()

    /**
     * Checks to see if there are any critical network errors (for example, a VPN connection getting disconnected while running)
     */
    @Volatile
    internal var criticalDriverError = false

    @Volatile
    private var closed = false

    fun closed(): Boolean {
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
            // if the network interface is removed (for example, a VPN connection).
            if (error is io.aeron.exceptions.ChannelEndpointException ||
                error.cause is BindException ||
                error.cause is SocketException ||
                error.cause is IOException) {
                // this is bad! We must close this connection. THIS WILL BE CALLED AS FAST AS THE CPU CAN RUN (because of how aeron works).
                if (!criticalDriverError) {
                    criticalDriverError = true

                    logger.error { "Aeron Driver [$driverId]: Critical driver error!" }


                    // make a copy
                    val endpoints = endPointUsages.toTypedArray()
                    runBlocking {
                        endpoints.forEach {
                            it.connections.forEach {conn ->
                               conn.closeImmediately(false, false)
                            }
                        }

                        // closing the driver here will SEGFAULT the jvm!! (cannot have reentrant code on this thread)
                    }

                    if (error.message?.startsWith("ERROR - channel error - Network is unreachable") == true) {
                        val exception = AeronDriverException("Network is disconnected or unreachable.")
                        exception.cleanAllStackTrace()
                        notifyError(exception)
                    } else if (error.message?.startsWith("WARN - failed to send") == true) {
                        val exception = AeronDriverException("Network socket error, can't send data.")
                        exception.cleanAllStackTrace()
                        notifyError(exception)
                    }
                    else if (error.message == "Can't assign requested address") {
                        val exception = AeronDriverException("Network socket error, can't assign requested address.")
                        exception.cleanAllStackTrace()
                        notifyError(exception)
                    } else {
                        error.cleanStackTrace()
                        // send this out to the listener-manager so we can be notified of global errors
                        notifyError(AeronDriverException(error.cause!!))
                    }
                }
            }
            else if (filter(error)) {
                error.cleanStackTrace()
                // send this out to the listener-manager so we can be notified of global errors
                notifyError(AeronDriverException(error))
            }
        }

        // @throws IllegalStateException if the configuration has already been used to create a context
        // @throws IllegalArgumentException if the aeron media driver directory cannot be setup
        context = AeronContext(config, logger, aeronErrorHandler)

        addEndpoint(endPoint)
    }

    // always called within a mutex!
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
        criticalDriverError = false
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


    /**
     * Add a [ConcurrentPublication] for publishing messages to subscribers.
     *
     * This guarantees that the publication is added and ACTIVE
     *
     * The publication returned is threadsafe.
     */
    @Suppress("DEPRECATION")
    suspend fun addPublication(
        logger: KLogger,
        publicationUri: ChannelUriStringBuilder,
        streamId: Int,
        logInfo: String,
        isIpc: Boolean
    ): Publication = stateMutex.withLock {

        val uri = publicationUri.build()

        // reasons we cannot add a pub/sub to aeron
        // 1) the driver was closed
        // 2) aeron was unable to connect to the driver
        // 3) the address already in use
        // 4) the SESSION ID is already in use (note: a subscription with NO sessionID will let ANY publication sessionID connect to it)

        // configuring pub/sub to aeron is LINEAR -- and it happens in 2 places.
        // 1) starting up the client/server
        // 2) creating a new client-server connection pair (the media driver won't be "dead" at this point)

        // in the client, if we are unable to connect to the server, we will attempt to start the media driver + connect to aeron

        // adding a publication can fail

        val aeron1 = aeron
        if (aeron1 == null || aeron1.isClosed) {
            logger.error { "Aeron Driver [$driverId]: Aeron is closed, error creating publication [$logInfo] :: sessionId=${publicationUri.sessionId()}, streamId=$streamId" }
            // there was an error connecting to the aeron client or media driver.
            val ex = ClientRetryException("Aeron Driver [$driverId]: Error adding a publication to aeron")
            ex.cleanAllStackTrace()
            throw ex
        }

        val publication: ConcurrentPublication? = try {
            aeron1.addPublication(uri, streamId)
        } catch (e: Exception) {
            logger.error(e) { "Aeron Driver [$driverId]: Error creating publication [$logInfo] :: sessionId=${publicationUri.sessionId()}, streamId=$streamId" }

            // this happens if the aeron media driver cannot actually establish connection... OR IF IT IS TOO FAST BETWEEN ADD AND REMOVE FOR THE SAME SESSION/STREAM ID!
            e.cleanAllStackTrace()
            val ex = ClientRetryException("Aeron Driver [$driverId]: Error adding a publication", e)
            ex.cleanAllStackTrace()
            throw ex
        }

        if (publication == null) {
            logger.error { "Aeron Driver [$driverId]: Error creating publication (is null) [$logInfo] :: sessionId=${publicationUri.sessionId()}, streamId=$streamId" }

            // there was an error connecting to the aeron client or media driver.
            val ex = ClientRetryException("Aeron Driver [$driverId]: Error adding a publication")
            ex.cleanAllStackTrace()
            throw ex
        }

        var hasDelay = false
        while (publication.channelStatus() != ChannelEndpointStatus.ACTIVE || (!isIpc && publication.localSocketAddresses().isEmpty())) {
            if (publication.channelStatus() == ChannelEndpointStatus.ERRORED) {
                logger.error { "Aeron Driver [$driverId]: Error creating publication (has errors) $logInfo :: sessionId=${publicationUri.sessionId()}, streamId=${streamId}" }

                // there was an error connecting to the aeron client or media driver.
                val ex = ClientRetryException("Aeron Driver [$driverId]: Error adding an publication")
                ex.cleanAllStackTrace()
                throw ex
            }

            if (!hasDelay) {
                hasDelay = true
                logger.debug { "Aeron Driver [$driverId]: Delaying creation of publication [$logInfo] :: sessionId=${publicationUri.sessionId()}, streamId=${streamId}" }
            }
            // the publication has not ACTUALLY been created yet!
            delay(50)
        }

        if (hasDelay) {
            logger.debug { "Aeron Driver [$driverId]: Delayed creation of publication [$logInfo] :: sessionId=${publicationUri.sessionId()}, streamId=${streamId}" }
        }


        registeredPublications.getAndIncrement()
        if (logger.isTraceEnabled) {
            registeredPublicationsTrace.add(publication.registrationId())
        }

        logger.trace { "Aeron Driver [$driverId]: Creating publication [$logInfo] :: regId=${publication.registrationId()}, sessionId=${publication.sessionId()}, streamId=${publication.streamId()}, channel=${publication.channel()}" }
        return publication
    }

    /**
     * Add an [ExclusivePublication] for publishing messages to subscribers from a single thread.
     *
     * This guarantees that the publication is added and ACTIVE
     *
     * This is not a thread-safe publication!
     */
    @Suppress("DEPRECATION")
    suspend fun addExclusivePublication(
        logger: KLogger,
        publicationUri: ChannelUriStringBuilder,
        streamId: Int,
        logInfo: String,
        isIpc: Boolean): Publication = stateMutex.withLock {

        val uri = publicationUri.build()

        // reasons we cannot add a pub/sub to aeron
        // 1) the driver was closed
        // 2) aeron was unable to connect to the driver
        // 3) the address already in use
        // 4) the SESSION ID is already in use (note: a subscription with NO sessionID will let ANY publication sessionID connect to it)

        // configuring pub/sub to aeron is LINEAR -- and it happens in 2 places.
        // 1) starting up the client/server
        // 2) creating a new client-server connection pair (the media driver won't be "dead" at this point)

        // in the client, if we are unable to connect to the server, we will attempt to start the media driver + connect to aeron

        val aeron1 = aeron
        if (aeron1 == null || aeron1.isClosed) {
            logger.error { "Aeron Driver [$driverId]: Aeron is closed, error creating ex-publication $logInfo :: sessionId=${publicationUri.sessionId()}, streamId=${streamId}" }

            // there was an error connecting to the aeron client or media driver.
            val ex = ClientRetryException("Aeron Driver [$driverId]: Error adding an ex-publication to aeron")
            ex.cleanAllStackTrace()
            throw ex
        }

        // adding a publication can fail

        val publication: ExclusivePublication? = try {
            aeron1.addExclusivePublication(uri, streamId)
        } catch (e: Exception) {
            logger.error(e) { "Aeron Driver [$driverId]: Error creating ex-publication $logInfo :: sessionId=${publicationUri.sessionId()}, streamId=${streamId}" }

            // this happens if the aeron media driver cannot actually establish connection... OR IF IT IS TOO FAST BETWEEN ADD AND REMOVE FOR THE SAME SESSION/STREAM ID!
            e.cleanAllStackTrace()
            val ex = ClientRetryException("Aeron Driver [$driverId]: Error adding an ex-publication", e)
            ex.cleanAllStackTrace()
            throw ex
        }

        if (publication == null) {
            logger.error { "Aeron Driver [$driverId]: Error creating ex-publication (is null) $logInfo :: sessionId=${publicationUri.sessionId()}, streamId=${streamId}" }

            // there was an error connecting to the aeron client or media driver.
            val ex = ClientRetryException("Aeron Driver [$driverId]: Error adding an ex-publication")
            ex.cleanAllStackTrace()
            throw ex
        }

        var hasDelay = false
        while (publication.channelStatus() != ChannelEndpointStatus.ACTIVE || (!isIpc && publication.localSocketAddresses().isEmpty())) {
            if (publication.channelStatus() == ChannelEndpointStatus.ERRORED) {
                logger.error { "Aeron Driver [$driverId]: Error creating ex-publication (has errors) $logInfo :: sessionId=${publicationUri.sessionId()}, streamId=${streamId}" }

                // there was an error connecting to the aeron client or media driver.
                val ex = ClientRetryException("Aeron Driver [$driverId]: Error adding an ex-publication")
                ex.cleanAllStackTrace()
                throw ex
            }


            if (!hasDelay) {
                hasDelay = true
                logger.debug { "Aeron Driver [$driverId]: Delaying creation of ex-publication [$logInfo] :: sessionId=${publicationUri.sessionId()}, streamId=${streamId}" }
            }
            // the publication has not ACTUALLY been created yet!
            delay(50)
        }

        if (hasDelay) {
            logger.debug { "Aeron Driver [$driverId]: Delayed creation of publication [$logInfo] :: sessionId=${publicationUri.sessionId()}, streamId=${streamId}" }
        }

        registeredPublications.getAndIncrement()
        if (logger.isTraceEnabled) {
            registeredPublicationsTrace.add(publication.registrationId())
        }

        logger.trace { "Aeron Driver [$driverId]: Creating ex-publication $logInfo :: regId=${publication.registrationId()}, sessionId=${publication.sessionId()}, streamId=${publication.streamId()}, channel=${publication.channel()}" }
        return publication
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
    @Suppress("DEPRECATION")
    suspend fun addSubscription(
        logger: KLogger,
        subscriptionUri: ChannelUriStringBuilder,
        streamId: Int,
        logInfo: String,
        isIpc: Boolean): Subscription = stateMutex.withLock {

        val uri = subscriptionUri.build()

        // reasons we cannot add a pub/sub to aeron
        // 1) the driver was closed
        // 2) aeron was unable to connect to the driver
        // 3) the address already in use
        // 4) the SESSION ID is already in use (note: a subscription with NO sessionID will let ANY publication sessionID connect to it)


        // configuring pub/sub to aeron is LINEAR -- and it happens in 2 places.
        // 1) starting up the client/server
        // 2) creating a new client-server connection pair (the media driver won't be "dead" at this point)

        // in the client, if we are unable to connect to the server, we will attempt to start the media driver + connect to aeron


        // subscriptions do not depend on a response from the remote endpoint, and should always succeed if aeron is available

        val aeron1 = aeron
        if (aeron1 == null || aeron1.isClosed) {
            logger.error { "Aeron Driver [$driverId]: Aeron is closed, error creating subscription [$logInfo] :: sessionId=${subscriptionUri.sessionId()}, streamId=${streamId}" }

            // there was an error connecting to the aeron client or media driver.
            val ex = ClientRetryException("Aeron Driver [$driverId]: Error adding a subscription to aeron")
            throw ex
        }

        val subscription = try {
            aeron1.addSubscription(uri, streamId)
        } catch (e: Exception) {
            logger.error(e) { "Aeron Driver [$driverId]: Error creating subscription [$logInfo] :: sessionId=${subscriptionUri.sessionId()}, streamId=${streamId}" }

            e.cleanAllStackTrace()
            val ex = ClientRetryException("Aeron Driver [$driverId]: Error adding a subscription", e)  // maybe not retry? or not clientRetry?
            ex.cleanStackTraceInternal()
            throw ex
        }

        if (subscription == null) {
            logger.error { "Aeron Driver [$driverId]: Error creating subscription (is null) [$logInfo] :: sessionId=${subscriptionUri.sessionId()}, streamId=${streamId}" }

            // there was an error connecting to the aeron client or media driver.
            val ex = ClientRetryException("Aeron Driver [$driverId]: Error adding a subscription")
            ex.cleanStackTraceInternal()
            throw ex
        }

        var hasDelay = false
        while (subscription.channelStatus() != ChannelEndpointStatus.ACTIVE || (!isIpc && subscription.localSocketAddresses().isEmpty())) {
            if (subscription.channelStatus() == ChannelEndpointStatus.ERRORED) {
                logger.error { "Aeron Driver [$driverId]: Error creating subscription (has errors) $logInfo :: sessionId=${subscriptionUri.sessionId()}, streamId=${streamId}" }

                // there was an error connecting to the aeron client or media driver.
                val ex = ClientRetryException("Aeron Driver [$driverId]: Error adding an subscription")
                ex.cleanAllStackTrace()
                throw ex
            }

            if (!hasDelay) {
                hasDelay = true
                logger.debug { "Aeron Driver [$driverId]: Delaying creation of subscription [$logInfo] :: sessionId=${subscriptionUri.sessionId()}, streamId=${streamId}" }
            }
            // the subscription has not ACTUALLY been created yet!
           delay(50)
        }

        if (hasDelay) {
            logger.debug { "Aeron Driver [$driverId]: Delayed creation of subscription [$logInfo] :: sessionId=${subscriptionUri.sessionId()}, streamId=${streamId}" }
        }

        registeredSubscriptions.getAndIncrement()
        if (logger.isTraceEnabled) {
            registeredSubscriptionsTrace.add(subscription.registrationId())
        }

        logger.trace { "Aeron Driver [$driverId]: Creating subscription [$logInfo] :: regId=${subscription.registrationId()}, sessionId=${subscriptionUri.sessionId()}, streamId=${subscription.streamId()}, channel=${subscription.channel()}" }
        return subscription
    }

    /**
     * Guarantee that the publication is closed AND the backing file is removed
     */
    suspend fun close(publication: Publication, logger: KLogger, logInfo: String) = stateMutex.withLock {
        val name = if (publication is ConcurrentPublication) {
            "publication"
        } else {
            "ex-publication"
        }

        val registrationId = publication.registrationId()

        logger.trace { "Aeron Driver [$driverId]: Closing $name file [$logInfo] :: regId=$registrationId, sessionId=${publication.sessionId()}, streamId=${publication.streamId()}" }


        val aeron1 = aeron
        if (aeron1 == null || aeron1.isClosed) {
            val e = Exception("Aeron Driver [$driverId]: Error closing $name [$logInfo] :: sessionId=${publication.sessionId()}, streamId=${publication.streamId()}")
            throw e
        }

        try {
            // This can throw exceptions!
            publication.close()
        } catch (e: Exception) {
            logger.error(e) { "Aeron Driver [$driverId]: Unable to close [$logInfo] $name $publication" }
        }

        if (publication is ConcurrentPublication) {
            // aeron is async. close() doesn't immediately close, it just submits the close command!
            // THIS CAN TAKE A WHILE TO ACTUALLY CLOSE!
            while (publication.isConnected || publication.channelStatus() == ChannelEndpointStatus.ACTIVE || aeron1.getPublication(registrationId) != null) {
                delay(50)
            }
        } else {
            // aeron is async. close() doesn't immediately close, it just submits the close command!
            // THIS CAN TAKE A WHILE TO ACTUALLY CLOSE!
            while (publication.isConnected || publication.channelStatus() == ChannelEndpointStatus.ACTIVE || aeron1.getExclusivePublication(registrationId) != null) {
                delay(50)
            }
        }

        // deleting log files is generally not recommended in a production environment as it can result in data loss and potential disruption of the messaging system!!

        registeredPublications.decrementAndGet()
        if (logger.isTraceEnabled) {
            registeredPublicationsTrace.remove(publication.registrationId())
        }
    }

    /**
     * Guarantee that the publication is closed AND the backing file is removed
     */
    suspend fun close(subscription: Subscription, logger: KLogger, logInfo: String) {
        logger.trace { "Aeron Driver [$driverId]: Closing subscription [$logInfo] :: regId=${subscription.registrationId()}, sessionId=${subscription.images().firstOrNull()?.sessionId()}, streamId=${subscription.streamId()}" }

        val aeron1 = aeron
        if (aeron1 == null || aeron1.isClosed) {
            val e = Exception("Aeron Driver [$driverId]: Error closing publication [$logInfo] :: sessionId=${subscription.images().firstOrNull()?.sessionId()}, streamId=${subscription.streamId()}")
            throw e
        }

        try {
            // This can throw exceptions!
            subscription.close()
        } catch (e: Exception) {
            logger.error(e) { "Aeron Driver [$driverId]: Unable to close [$logInfo] subscription $subscription" }
        }

        // aeron is async. close() doesn't immediately close, it just submits the close command!
        // THIS CAN TAKE A WHILE TO ACTUALLY CLOSE!
        while (subscription.isConnected || subscription.channelStatus() == ChannelEndpointStatus.ACTIVE || subscription.images().isNotEmpty()) {
            delay(50)
        }

        // deleting log files is generally not recommended in a production environment as it can result in data loss and potential disruption of the messaging system!!

        registeredSubscriptions.decrementAndGet()
        if (logger.isTraceEnabled) {
            registeredSubscriptionsTrace.remove(subscription.registrationId())
        }
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

        val timeoutInNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMS) + lingerNs()
        var didLog = false

        val closeTimeoutTime = System.nanoTime()
        while (isRunning() && System.nanoTime() - closeTimeoutTime < timeoutInNanos) {
            // only emit the log info once. It's rather spammy otherwise!
            if (!didLog) {
                didLog = true
                logger.debug { "Aeron Driver [$driverId]: Still running (${aeronDirectory}). Waiting for it to stop..." }
            }
            delay(intervalTimeoutMS)
        }

        return !isRunning()
    }

    /**
     * Deletes the entire context of the aeron directory in use.
     */
    fun deleteAeronDir(): Boolean {
        return context.deleteAeronDir()
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

    suspend fun isInUse(endPoint: EndPoint<*>?, logger: KLogger): Boolean {
        // as many "sort-cuts" as we can for checking if the current Aeron Driver/client is still in use
        if (!isRunning()) {
            logger.trace { "Aeron Driver [$driverId]: not running" }
            return false
        }

        if (registeredPublications.value > 0) {
            if (logger.isTraceEnabled) {
                val elements = registeredPublicationsTrace.elements
                val joined = elements.joinToString()
                logger.debug { "Aeron Driver [$driverId]: has [$joined] publications (${registeredPublications.value} total)" }
            } else {
                logger.debug { "Aeron Driver [$driverId]: has publications (${registeredPublications.value} total)" }
            }
            return true
        }

        if (registeredSubscriptions.value > 0) {
            if (logger.isTraceEnabled) {
                val elements = registeredSubscriptionsTrace.elements
                val joined = elements.joinToString()
                logger.debug { "Aeron Driver [$driverId]: has [$joined] subscriptions (${registeredSubscriptions.value} total)" }
            } else {
                logger.debug { "Aeron Driver [$driverId]: has subscriptions (${registeredSubscriptions.value} total)" }
            }
            return true
        }

        if (endPointUsages.size > 1 && !endPointUsages.contains(endPoint)) {
            logger.debug { "Aeron Driver [$driverId]: still referenced by ${endPointUsages.size} endpoints" }
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
            delayLingerTimeout()
            currentUsage = driverBacklog()?.snapshot()?.size ?: 0
            count--

            if (currentUsage == 0) {
                return false
            }
        }


        count = 3
        while (count > 0 && currentUsage > 0) {
            logger.debug { "Aeron Driver [$driverId]: in use, double checking status (long)" }
            delayDriverTimeout()
            currentUsage = driverBacklog()?.snapshot()?.size ?: 0
            count--
        }

        if (currentUsage > 0) {
            logger.debug { "Aeron Driver [$driverId]: usage is: $currentUsage" }
        }

        return currentUsage > 0
    }

    /**
     * A safer way to try to close the media driver
     *
     * NOTE: We must be *super* careful trying to delete directories, because if we have multiple AERON/MEDIA DRIVERS connected to the
     *   same directory, deleting the directory will cause any other aeron connection to fail! (which makes sense).
     *
     * @return true if the driver was successfully stopped.
     */
    suspend fun close(endPoint: EndPoint<*>?, logger: KLogger): Boolean = stateMutex.withReentrantLock {
        if (endPoint != null) {
            endPointUsages.remove(endPoint)
        }

        logger.trace { "Aeron Driver [$driverId]: Requested close... (${endPointUsages.size} endpoints still in use)" }

        // ignore the extra driver checks, because in SOME situations, when trying to reconnect upon an error, the
        if (isInUse(endPoint, logger)) {
            if (!criticalDriverError) {
            // driver gets into a bad state. When this happens, we have to ignore "are we already in use" checks, BECAUSE the driver is now corrupted and unusable!
                logger.debug { "Aeron Driver [$driverId]: in use, not shutting down this instance." }
                return@withReentrantLock false
            }
        }

        val removed = AeronDriver.driverConfigurations[driverId]
        if (removed == null) {
            logger.debug { "Aeron Driver [$driverId]: already closed. Ignoring close request." }
            return@withReentrantLock false
        }


        logger.debug { "Aeron Driver [$driverId]: Closing..." }

        // we have to assign context BEFORE we close, because the `getter` for context will create it if necessary
        val aeronContext = context
        val driverDirectory = aeronContext.directory

        try {
            aeron?.close()
        } catch (e: Exception) {
            if (endPoint != null) {
                endPoint.listenerManager.notifyError(AeronDriverException("Aeron Driver [$driverId]: Error stopping", e))
            } else {
                logger.error(e) { "Aeron Driver [$driverId]: Error stopping" }
            }
        }

        aeron = null


        if (mediaDriver == null) {
            logger.debug { "Aeron Driver [$driverId]: No driver started, not stopping driver or context." }

            // reset our contextDefine value, so that this configuration can safely be reused
            endPoint?.config?.contextDefined = false
            return@withReentrantLock false
        }

        logger.debug { "Aeron Driver [$driverId]: Stopping driver at '${driverDirectory}'..." }

        // if we are the ones that started the media driver, then we must be the ones to close it
        try {
            mediaDriver!!.close()
        } catch (e: Exception) {
            if (endPoint != null) {
                endPoint.listenerManager.notifyError(AeronDriverException("Aeron Driver [$driverId]: Error closing", e))
            } else {
                logger.error(e) { "Aeron Driver [$driverId]: Error closing" }
            }
        }

        mediaDriver = null

        // it can actually close faster, if everything is ideal.
        val timeout = (aeronContext.driverTimeout + AERON_PUBLICATION_LINGER_TIMEOUT) / 4

        // it can actually close faster, if everything is ideal.
        try {
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
        }
        catch (e: Exception) {
            if (!criticalDriverError) {
                logger.error(e) { "Error while checking isRunning() state." }
            }
        }

        // make sure the context is also closed, but ONLY if we are the last one
        aeronContext.close()

        // make sure all of our message listeners are removed
        removeErrors()

        try {
            val deletedAeron = driverDirectory.deleteRecursively()
            if (!deletedAeron) {
                if (endPoint != null) {
                    endPoint.listenerManager.notifyError(AeronDriverException("Aeron Driver [$driverId]: Error deleting Aeron directory at: $driverDirectory"))
                } else {
                    logger.error { "Aeron Driver [$driverId]: Error deleting Aeron directory at: $driverDirectory" }
                }
            }
        } catch (e: Exception) {
            if (endPoint != null) {
                endPoint.listenerManager.notifyError(AeronDriverException("Aeron Driver [$driverId]: Error deleting Aeron directory at: $driverDirectory", e))
            } else {
                logger.error(e) { "Aeron Driver [$driverId]: Error deleting Aeron directory at: $driverDirectory" }
            }
        }

        // check to make sure it's actually deleted
        if (driverDirectory.isDirectory) {
            if (endPoint != null) {
                endPoint.listenerManager.notifyError(AeronDriverException("Aeron Driver [$driverId]: Error deleting Aeron directory at: $driverDirectory"))
            } else {
                logger.error { "Aeron Driver [$driverId]: Error deleting Aeron directory at: $driverDirectory" }
            }
        }


        // reset our contextDefine value, so that this configuration can safely be reused
        endPoint?.config?.contextDefined = false

        // actually remove it, since we've passed all the checks to guarantee it's closed...
        AeronDriver.driverConfigurations.remove(driverId)

        logger.debug { "Aeron Driver [$driverId]: Closed the media driver at '${driverDirectory}'" }
        closed = true

        return@withReentrantLock true
    }

    /**
     * @return the aeron driver timeout
     */
    private fun driverTimeout(): Long {
        return context.driverTimeout
    }


    /**
     * @return the aeron media driver log file for a specific publication.
     */
    fun getMediaDriverFile(publication: Publication): File {
        return context.directory.resolve("publications").resolve("${publication.registrationId()}.logbuffer")
    }

    /**
     * @return the aeron media driver log file for a specific image (within a subscription, an image is the "connection" with a publication).
     */
    fun getMediaDriverFile(image: Image): File {
        return context.directory.resolve("images").resolve("${image.correlationId()}.logbuffer")
    }

    /**
     * Deletes the logfile for this publication
     */
    fun deleteLogFile(publication: Publication) {
        getMediaDriverFile(publication).delete()
    }

    /**
     * Deletes the logfile for this image (within a subscription, an image is the "connection" with a publication).
     */
    fun deleteLogFile(image: Image) {
        val file = getMediaDriverFile(image)
        driverLogger.debug { "Deleting log file: $image" }
        file.delete()
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
     * @return Time in nanoseconds a publication will linger once it is drained to recover potential tail loss.
     */
    fun lingerNs(): Long {
        return context.context.publicationLingerTimeoutNs()
    }

    /**
     * @return Time in nanoseconds a publication will be considered not connected if no status messages are received.
     */
    fun publicationConnectionTimeoutNs(): Long {
        return context.context.publicationConnectionTimeoutNs()
    }

    /**
     * Make sure that we DO NOT approach the Aeron linger timeout!
     */
    suspend fun delayDriverTimeout(multiplier: Number = 1) {
        delay((driverTimeout() * multiplier.toDouble()).toLong())
    }

    /**
     * Make sure that we DO NOT approach the Aeron linger timeout!
     */
    suspend fun delayLingerTimeout(multiplier: Number = 1) {
        delay(driverTimeout().coerceAtLeast(TimeUnit.NANOSECONDS.toSeconds((lingerNs() * multiplier.toDouble()).toLong())) )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AeronDriverInternal) return false
        if (!super.equals(other)) return false

        return driverId == other.driverId
    }

    override fun hashCode(): Int {
        return driverId
    }

    override fun toString(): String {
        return "Aeron Driver [${driverId}]"
    }
}
