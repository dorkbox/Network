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

package dorkbox.network.connection

import dorkbox.collections.ConcurrentIterator
import dorkbox.network.Configuration
import dorkbox.network.aeron.CoroutineIdleStrategy
import mu.KLogger
import mu.KotlinLogging
import org.agrona.concurrent.IdleStrategy
import java.io.Closeable
import java.util.concurrent.*

/**
 * there are threading issues if there are client(s) and server's within the same JVM, where we have thread starvation
 *
 * additionally, if we have MULTIPLE clients on the same machine, we are limited by the CPU core count. Ideally we want to share
 * this among ALL clients within the same JVM so that we can support multiple clients/servers
 */
class EventPoller: Closeable {
    private val logger: KLogger = KotlinLogging.logger(EventPoller::class.java.simpleName)

    private var configured = false
    private lateinit var dispatch: ExecutorService
    private lateinit var pollStrategy: CoroutineIdleStrategy
    private lateinit var clonedStrategy: IdleStrategy

    @Volatile
    private var running = true

    // this is thread safe
    private val pollEvents = ConcurrentIterator<Pair<EventPoller.()->Int, ()->Unit>>()

    @Volatile
    private var shutdownLatch = CountDownLatch(1)

    @Synchronized
    fun configure(config: Configuration) {
        if (!configured) {
            running = true
            configured = true
            shutdownLatch = CountDownLatch(1)
            dispatch = config.networkEventPoll
            pollStrategy = config.pollIdleStrategy
            clonedStrategy = config.pollIdleStrategy.cloneToNormal()

            require(!dispatch.isTerminated) { "Unable to start the event dispatch in the terminated state!"}

            dispatch.submit {
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
                            }
                            if (poll > 0) {
                                pollCount += poll
                            }
                        } catch (e: Exception) {
                            logger.error(e) { "Unexpected error during server message polling! Aborting event dispatch for it!" }

                            // remove our event, it is no longer valid
                            pollEvents.remove(this)
                            it.second()
                        }
                    }

                    pollIdleStrategy.idle(pollCount)
                }

                shutdownLatch.countDown()
            }
        } else {
            require(dispatch == config.networkEventPoll) {
                "The network event dispatcher is different between the multiple instances of network clients/servers. There **WILL BE** thread starvation, so this behavior is forbidden!"
            }

            require(pollStrategy == config.pollIdleStrategy) {
                "The network event poll strategy is different between the multiple instances of network clients/servers. There **WILL BE** thread starvation, so this behavior is forbidden!"
            }
        }
    }

    /**
     * Will cause the executing thread to wait until the event has been started
     */
    fun submit(action: EventPoller.() -> Int, onShutdown: ()->Unit = {}) {
        // this forces the current thread to WAIT until the network poll system has started
        val pollStartupLatch = CountDownLatch(1)

        pollEvents.add(Pair(action, onShutdown))
        pollEvents.add(Pair(
            {
                pollStartupLatch.countDown()

                // remove ourselves
                -1
            },
            {}
        ))

        pollStartupLatch.await()
    }

    /**
     * Waits for all events to finish running
     */
    @Synchronized
    override fun close() {
        // ONLY if there are no more poll-events do we ACTUALLY shut down.
        // when an endpoint closes its polling, it will automatically be removed from this datastructure.
        if (pollEvents.size() == 0) {
            running = false
            shutdownLatch.await()
            configured = false
        }
    }
}
