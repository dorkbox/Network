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

import dorkbox.network.Configuration
import dorkbox.util.NamedThreadFactory
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.util.concurrent.*

/**
 * This MUST be run on multiple coroutines! There are deadlock issues if it is only one.
 *
 * This class LITERALLY forces a coroutine dispatcher to be exclusively on a single thread.
 *
 * WARNING: The logic in this class will ONLY work in this class, as it relies on this specific behavior. Do not use it elsewhere!
 */
enum class EventDispatcher {
    // NOTE: CLOSE must be last!
    HANDSHAKE, CONNECT, DISCONNECT, RESPONSE_MANAGER, ERROR, CLOSE;

    companion object {
        private val DEBUG_EVENTS = false
        private val traceId = atomic(0)

        private val logger = KotlinLogging.logger(EventDispatcher::class.java.simpleName)

        private val threadIds = values().map { atomic(0L) }.toTypedArray()


        private val executors = values().map { event ->
            // It CANNOT be the default dispatch because there will be thread starvation
            // NOTE: THIS CANNOT CHANGE!! IT WILL BREAK EVERYTHING IF IT CHANGES!
            Executors.newSingleThreadExecutor(
                NamedThreadFactory("Event Dispatcher-${event.name}",
                                   Configuration.networkThreadGroup, Thread.NORM_PRIORITY, true) { thread ->
                    // when a new thread is created, assign it to the array
                    threadIds[event.ordinal].lazySet(thread.id)
                }
            )
        }

        private val eventData = executors.map { executor ->
            CoroutineScope(executor.asCoroutineDispatcher()  + SupervisorJob())
        }


        init {
            executors.forEachIndexed { _, executor ->
                executor.submit {
                    // this is to create a new thread only, so that the thread ID can be assigned
                }
            }
        }

        /**
         * Checks if the current execution thread is running inside one of the event dispatchers listed.
         *
         * No values specified means we check ALL events
         */
        fun isDispatch(): Boolean {
            return isCurrentEvent(*values())
        }

        /**
         * Checks if the current execution thread is running inside one of the event dispatchers listed.
         *
         * No values specified means we check ALL events
         */
        fun isCurrentEvent(vararg events: EventDispatcher = values()): Boolean {
            val threadId = Thread.currentThread().id

            events.forEach { event ->
                if (threadIds[event.ordinal].value == threadId) {
                    return true
                }
            }

            return false
        }

        /**
         * Checks if the current execution thread is NOT running inside one of the event dispatchers listed.
         *
         * No values specified means we check ALL events
         */
        fun isNotCurrentEvent(vararg events: EventDispatcher = values()): Boolean {
            val currentDispatch = getCurrentEvent() ?: return false

            return events.contains(currentDispatch)
        }

        /**
         * @return which event dispatch thread we are running in, if any
         */
        fun getCurrentEvent(): EventDispatcher? {
            val threadId = Thread.currentThread().id

            values().forEach { event ->
                if (threadIds[event.ordinal].value == threadId) {
                    return event
                }
            }

            return null
        }

        /**
         * Each event type runs inside its own coroutine dispatcher.
         *
         * We want EACH event type to run in its own dispatcher... on its OWN thread, in order to prevent deadlocks
         * This is because there are blocking dependencies: DISCONNECT -> CONNECT.
         *
         * If an event is RE-ENTRANT, then it will immediately execute!
         */
        private fun launch(event: EventDispatcher, function: suspend () -> Unit): Job {
            val eventId = event.ordinal

            return if (DEBUG_EVENTS) {
                val id = traceId.getAndIncrement()
                eventData[eventId].launch(block = {
                    logger.debug { "Starting $event : $id" }
                    function()
                    logger.debug { "Finished $event : $id" }
                })
            } else {
                eventData[eventId].launch {
                    function()
                }
            }
        }

        suspend fun launchSequentially(endEvent: EventDispatcher, function: suspend () -> Unit) {
            // If one of our callbacks requested a shutdown, we wait until all callbacks have run ... THEN shutdown
            val event = getCurrentEvent()

            val index = event?.ordinal ?: -1

            // This will loop through until it runs on the CLOSE EventDispatcher
            if (index < endEvent.ordinal) {
                // If this runs inside EVENT.CONNECT/DISCONNECT/ETC, we must ***WAIT*** until all listeners have been called!
                // this problem is solved by running AGAIN after we have finished running whatever event dispatcher we are currently on
                // MORE SPECIFICALLY, we must run at the end of our current one, but repeatedly until CLOSE

                EventDispatcher.launch(values()[index+1]) {
                    launchSequentially(endEvent, function)
                }
            } else {
                function()
            }
        }
    }

    fun launch(function: suspend () -> Unit): Job {
        return launch(this, function)
    }
}
