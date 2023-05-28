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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.util.concurrent.*

/**
 * This MUST be run on multiple coroutines! There are deadlock issues if it is only one.
 */
class EventDispatcher {
    companion object {
        private val DEBUG_EVENTS = false
        private val traceId = atomic(0)

        private val logger = KotlinLogging.logger(EventDispatcher::class.java.simpleName)

        enum class EVENT {
            INIT, CONNECT, DISCONNECT, CLOSE, RMI, PING
        }

        private val eventData = Array(EVENT.values().size) {
            // It CANNOT be the default dispatch because there will be thread starvation
            // NOTE: THIS CANNOT CHANGE!! IT WILL BREAK EVERYTHING IF IT CHANGES!
            val executor = Executors.newSingleThreadExecutor(
                NamedThreadFactory("Event Dispatcher-${EVENT.values()[it].name}", Configuration.networkThreadGroup, Thread.NORM_PRIORITY, true)
            ).asCoroutineDispatcher()

            CoroutineScope(executor + SupervisorJob())
        }

        val isActive: Boolean
            get() = eventData.all { it.isActive }

        /**
         * Each event type runs inside its own coroutine dispatcher.
         *
         * We want EACH event type to run in its own dispatcher... on its OWN thread, in order to prevent deadlocks
         * This is because there are blocking dependencies: DISCONNECT -> CONNECT.
         */
        fun launch(event: EVENT, function: suspend CoroutineScope.() -> Unit): Job {
            return if (DEBUG_EVENTS) {
                val id = traceId.getAndIncrement()
                eventData[event.ordinal].launch(block = {
                    logger.debug { "Starting $event : $id" }
                    function()
                    logger.debug { "Finished $event : $id" }
                })
            } else {
                eventData[event.ordinal].launch(block = function)
            }
        }
    }
}
