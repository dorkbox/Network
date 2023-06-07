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

import dorkbox.collections.ConcurrentIterator
import dorkbox.network.Configuration
import dorkbox.util.sync.CountDownLatch
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KLogger
import mu.KotlinLogging
import org.agrona.concurrent.IdleStrategy

/**
 * there are threading issues if there are client(s) and server's within the same JVM, where we have thread starvation
 *
 * additionally, if we have MULTIPLE clients on the same machine, we are limited by the CPU core count. Ideally we want to share
 * this among ALL clients within the same JVM so that we can support multiple clients/servers
 */
internal class EventPoller {
    companion object {
        const val REMOVE = -1
        const val DEBUG = false
    }

    private var configured = false
    private lateinit var pollDispatcher: CoroutineDispatcher
    private lateinit var dispatchScope: CoroutineScope

    private lateinit var pollStrategy: CoroutineIdleStrategy
    private lateinit var clonedStrategy: IdleStrategy

    private var running = true
    private var mutex = Mutex()

    // this is thread safe
    private val pollEvents = ConcurrentIterator<Pair<suspend EventPoller.()->Int, suspend ()->Unit>>()
    private val submitEvents = atomic(0)
    private val configureEvents = atomic(0)

    private var shutdownLatch = CountDownLatch(1)

    fun configure(logger: KLogger, config: Configuration) = runBlocking {
        mutex.withLock {
            logger.trace { "Initializing the Network Event Poller..." }
            configureEvents.getAndIncrement()

            if (!configured) {
                logger.debug { "Configuring the Network Event Poller..." }

                running = true
                configured = true
                shutdownLatch = CountDownLatch(1)
                pollDispatcher = config.networkEventPoll
                pollStrategy = config.pollIdleStrategy
                clonedStrategy = config.pollIdleStrategy.cloneToNormal()

                dispatchScope = CoroutineScope(pollDispatcher + SupervisorJob())
                require(!pollDispatcher.isActive) { "Unable to start the event dispatch in the terminated state!" }

                dispatchScope.launch {
                    val eventLogger: KLogger = KotlinLogging.logger(EventPoller::class.java.simpleName)

                    val pollIdleStrategy = clonedStrategy
                    var pollCount = 0

                    while (running) {
                        pollEvents.forEachRemovable {
                            try {
                                // check to see if we should remove this event (when a client/server closes, it is removed)
                                // once ALL endpoint are closed, this is shutdown.
                                val poll = it.first(this@EventPoller)

                                // <0 means we remove the event from processing
                                // 0 means we idle
                                // >0 means reset and don't idle (because there are likely more poll events)
                                if (poll < 0) {
                                    // remove our event, it is no longer valid
                                    pollEvents.remove(this)
                                    it.second()
                                } else if (poll > 0) {
                                    pollCount += poll
                                }
                            } catch (e: Exception) {
                                eventLogger.error(e) { "Unexpected error during server message polling! Aborting event dispatch for it!" }

                                // remove our event, it is no longer valid
                                pollEvents.remove(this)
                                it.second()
                            }
                        }

                        pollIdleStrategy.idle(pollCount)
                    }

                    // now we have to REMOVE all poll events -- so that their remove logic will run.
                    pollEvents.forEachRemovable {
                        // remove our event, it is no longer valid
                        pollEvents.remove(this)
                        it.second()
                    }

                    shutdownLatch.countDown()
                }
            } else {
                require(pollDispatcher == config.networkEventPoll) {
                    "The network event dispatcher is different between the multiple instances of network clients/servers. There **WILL BE** thread starvation, so this behavior is forbidden!"
                }

                require(pollStrategy == config.pollIdleStrategy) {
                    "The network event poll strategy is different between the multiple instances of network clients/servers. There **WILL BE** thread starvation, so this behavior is forbidden!"
                }
            }
        }
    }

    /**
     * Will cause the executing thread to wait until the event has been started
     */
    suspend fun submit(action: suspend EventPoller.() -> Int) {
        submit(action) {}
    }

    /**
     * Will cause the executing thread to wait until the event has been started
     */
    suspend fun submit(action: suspend EventPoller.() -> Int, onShutdown: suspend () -> Unit) = mutex.withLock {
        submitEvents.getAndIncrement()

        // this forces the current thread to WAIT until the network poll system has started
        val pollStartupLatch = CountDownLatch(1)

        pollEvents.add(Pair(action, onShutdown))
        pollEvents.add(Pair(
            {
                pollStartupLatch.countDown()

                // remove ourselves
                REMOVE
            },
            {}
        ))

        pollStartupLatch.await()

        submitEvents.getAndDecrement()
    }

    /**
     * Waits for all events to finish running
     */
    suspend fun close(logger: KLogger) {
        logger.trace { "Requesting close for the Network Event Poller..." }

        val doClose =  mutex.withLock {
            logger.trace { "Attempting close for the Network Event Poller..." }
            // ONLY if there are no more poll-events do we ACTUALLY shut down.
            // when an endpoint closes its polling, it will automatically be removed from this datastructure.
            val cEvents = configureEvents.decrementAndGet()
            val pEvents = pollEvents.size()
            val sEvents = submitEvents.value

            if (running && sEvents == 0 && cEvents == 0 && pEvents == 0) {
                logger.debug { "Closing the Network Event Poller..." }
                running = false
                true
            } else {
                logger.debug { "Not closing the Network Event Poller... (isRunning=$running submitEvents=$sEvents configureEvents=${cEvents} pollEvents=$pEvents)" }
                false
            }
        }

        if (doClose) {
            shutdownLatch.await()
            configured = false

            dispatchScope.cancel("Closed event dispatch")
            logger.debug { "Closed the Network Event Poller..." }
        }
    }
}
