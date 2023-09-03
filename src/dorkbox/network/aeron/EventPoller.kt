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

import dorkbox.bytes.ByteArrayWrapper
import dorkbox.collections.ConcurrentIterator
import dorkbox.network.Configuration
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.EventDispatcher
import dorkbox.util.NamedThreadFactory
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import mu.KLogger
import mu.KotlinLogging
import java.util.concurrent.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

/**
 * there are threading issues if there are client(s) and server's within the same JVM, where we have thread starvation
 *
 * additionally, if we have MULTIPLE clients on the same machine, we are limited by the CPU core count. Ideally we want to share
 * this among ALL clients within the same JVM so that we can support multiple clients/servers
 */
internal class EventPoller {

    private class EventAction(val onAction: EventActionOperator, val onClose: EventCloseOperator)

    companion object {
        internal const val REMOVE = -1

        val eventLogger = KotlinLogging.logger(EventPoller::class.java.simpleName)


        private val pollDispatcher = Executors.newSingleThreadExecutor(
            NamedThreadFactory("Poll Dispatcher", Configuration.networkThreadGroup, true)
        ).asCoroutineDispatcher()
    }

    private var configured = false
    private lateinit var dispatchScope: CoroutineScope

    private lateinit var pollStrategy: CoroutineIdleStrategy

    @Volatile
    private var running = false
    private var lock = ReentrantReadWriteLock()

    // this is thread safe
    private val pollEvents = ConcurrentIterator<EventAction>()
    private val submitEvents = atomic(0)
    private val configureEventsEndpoints = mutableSetOf<ByteArrayWrapper>()

    @Volatile
    private var delayClose = false

    @Volatile
    private var shutdownLatch = CountDownLatch(0)


    @Volatile
    private var threadId = Thread.currentThread().id


    fun inDispatch(): Boolean {
        // this only works because we are a single thread dispatch
        return threadId == Thread.currentThread().id
    }

    fun configure(logger: KLogger, config: Configuration, endPoint: EndPoint<*>) = runBlocking {
        lock.write {
            logger.debug { "Initializing the Network Event Poller..." }
            configureEventsEndpoints.add(ByteArrayWrapper.wrap(endPoint.storage.publicKey))

            if (!configured) {
                logger.trace { "Configuring the Network Event Poller..." }

                delayClose = false
                running = true
                configured = true
                shutdownLatch = CountDownLatch(1)
                pollStrategy = config.pollIdleStrategy.clone()

                dispatchScope = CoroutineScope(pollDispatcher + SupervisorJob())
                require(pollDispatcher.isActive) { "Unable to start the event dispatch in the terminated state!" }

                dispatchScope.launch {
                    val pollIdleStrategy = pollStrategy
                    var pollCount = 0
                    threadId = Thread.currentThread().id

                    pollIdleStrategy.reset()

                    while (running) {
                        pollEvents.forEachRemovable {
                            try {
                                // check to see if we should remove this event (when a client/server closes, it is removed)
                                // once ALL endpoint are closed, this is shutdown.
                                val poll = it.onAction()

                                // <0 means we remove the event from processing
                                // 0 means we idle
                                // >0 means reset and don't idle (because there are likely more poll events)
                                if (poll < 0) {
                                    // remove our event, it is no longer valid
                                    pollEvents.remove(this)
                                    it.onClose() // shutting down

                                    // check to see if we requested a shutdown
                                    if (delayClose) {
                                        doClose(eventLogger)
                                    }
                                } else if (poll > 0) {
                                    pollCount += poll
                                }
                            } catch (e: Exception) {
                                eventLogger.error(e) { "Unexpected error during Network Event Polling! Aborting event dispatch for it!" }

                                // remove our event, it is no longer valid
                                pollEvents.remove(this)
                                it.onClose() // shutting down

                                // check to see if we requested a shutdown
                                if (delayClose) {
                                    doClose(eventLogger)
                                }
                            }
                        }

                        pollIdleStrategy.idle(pollCount)
                    }

                    // now we have to REMOVE all poll events -- so that their remove logic will run.
                    pollEvents.forEachRemovable {
                        // remove our event, it is no longer valid
                        pollEvents.remove(this)
                        it.onClose() // shutting down
                    }

                    shutdownLatch.countDown()
                }
            } else {
                // we don't want to use .equals, because that also compares STATE, which for us is going to be different because we are cloned!
                // toString has the right info to compare types/config accurately
                require(pollStrategy.toString() == config.pollIdleStrategy.toString()) {
                    "The network event poll strategy is different between the multiple instances of network clients/servers. There **WILL BE** thread starvation, so this behavior is forbidden!"
                }
            }
        }
    }

    /**
     * Will cause the executing thread to wait until the event has been started
     */
    fun submit(action: EventActionOperator, onClose: EventCloseOperator) = lock.write {
        submitEvents.getAndIncrement()

        // this forces the current thread to WAIT until the network poll system has started
        val pollStartupLatch = CountDownLatch(1)

        pollEvents.add(EventAction(action, onClose))
        pollEvents.add(EventAction(
            object : EventActionOperator {
                override fun invoke(): Int {
                    pollStartupLatch.countDown()

                    // remove ourselves
                    return REMOVE
                }
            }
            , object : EventCloseOperator {
                override suspend fun invoke() {
                }
            }
        ))

        pollStartupLatch.await()

        submitEvents.getAndDecrement()
    }



    /**
     * Waits for all events to finish running
     */
    fun close(logger: KLogger, endPoint: EndPoint<*>) {
        // make sure that we close on the CLOSE dispatcher if we run on the poll dispatcher!
        if (inDispatch()) {
            EventDispatcher.CLOSE.launch {
                close(logger, endPoint)
            }
            return
        }

        lock.write {
            logger.debug { "Requesting close for the Network Event Poller..." }

            // ONLY if there are no more poll-events do we ACTUALLY shut down.
            // when an endpoint closes its polling, it will automatically be removed from this datastructure.
            val publicKeyWrapped = ByteArrayWrapper.wrap(endPoint.storage.publicKey)
            configureEventsEndpoints.removeIf { it == publicKeyWrapped }
            val cEvents = configureEventsEndpoints.size

            // these prevent us from closing too early
            val pEvents = pollEvents.size()
            val sEvents = submitEvents.value

            if (running && sEvents == 0 && cEvents == 0) {
                when (pEvents) {
                    0 -> {
                        logger.debug { "Closing the Network Event Poller..." }
                        doClose(logger)
                    }
                    1 -> {
                        // this means we are trying to close on our poll event, and obviously it won't work.
                        logger.debug { "Delayed closing the Network Event Poller..." }
                        delayClose = true
                    }
                    else -> {
                        logger.debug { "Not closing the Network Event Poller... (isRunning=$running submitEvents=$sEvents configureEvents=${cEvents} pollEvents=$pEvents)" }
                    }
                }
            } else {
                logger.debug { "Not closing the Network Event Poller... (isRunning=$running submitEvents=$sEvents configureEvents=${cEvents} pollEvents=$pEvents)" }
            }
        }
    }

    private fun doClose(logger: KLogger) {
        val wasRunning = running

        running = false
        while (!shutdownLatch.await(100, TimeUnit.MILLISECONDS)) {
            logger.error { "Waiting for Network Event Poller to close. It should not take this long" }
        }
        configured = false

        if (wasRunning) {
            dispatchScope.cancel("Closed Network Event Poller dispatch")
        }
        logger.error { "Closed Network Event Poller: wasRunning=$wasRunning" }
    }
}
