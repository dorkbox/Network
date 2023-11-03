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
import org.slf4j.LoggerFactory
import java.util.concurrent.*

/**
 * Event logic throughout the network MUST be run on multiple threads! There are deadlock issues if it is only one, or if the client + server
 * share an event dispatcher (multiple network restarts were required to check this)
 *
 * WARNING: The logic in this class will ONLY work in this class, as it relies on this specific behavior. Do not use it elsewhere!
 */
internal class EventDispatcher(val type: String) {
    enum class EDType {
        // CLOSE must be last!
        CONNECT, ERROR, CLOSE
    }

    internal class ED(private val dispatcher: EventDispatcher, private val type: EDType) {
        fun launch(function: () -> Unit) {
            dispatcher.launch(type, function)
        }

        fun isDispatch(): Boolean {
            return dispatcher.isDispatch(type)
        }
    }

    internal class Dispatch() {
        companion object {
            val executor = Executors.newCachedThreadPool(
                NamedThreadFactory("Multi", Configuration.networkThreadGroup)
            )
        }

        fun launch(function: () -> Unit) {
            executor.submit(function)
        }
    }

    companion object {
        private val DEBUG_EVENTS = false
        private val traceId = atomic(0)

        private val typedEntries: Array<EDType>

        val MULTI = Dispatch()

        init {
            typedEntries = EDType.entries.toTypedArray()
        }
    }

    private val logger = LoggerFactory.getLogger("$type Dispatch")

    private val threadIds = EDType.entries.map { atomic(0L) }.toTypedArray()

    private val executors = EDType.entries.map { event ->
        // It CANNOT be the default dispatch because there will be thread starvation
        // NOTE: THIS CANNOT CHANGE!! IT WILL BREAK EVERYTHING IF IT CHANGES!
        Executors.newSingleThreadExecutor(
            NamedThreadFactory(
                namePrefix = "$type-${event.name}",
                group = Configuration.networkThreadGroup,
                threadPriority = Thread.NORM_PRIORITY,
                daemon = true
            ) { thread ->
                // when a new thread is created, assign it to the array
                threadIds[event.ordinal].lazySet(thread.id)
            }
        )
    }.toTypedArray()



    val CONNECT: ED
    val ERROR: ED
    val CLOSE: ED


    init {
        executors.forEachIndexed { _, executor ->
            executor.submit {
                // this is to create a new thread only, so that the thread ID can be assigned
            }
        }

        CONNECT = ED(this, EDType.CONNECT)
        ERROR = ED(this, EDType.ERROR)
        CLOSE = ED(this, EDType.CLOSE)
    }


    /**
     *  Checks if the current execution thread is running inside one of the event dispatchers.
     */
    fun isDispatch(): Boolean {
        val threadId = Thread.currentThread().id

        typedEntries.forEach { event ->
            if (threadIds[event.ordinal].value == threadId) {
                return true
            }
        }

        return false
    }

    /**
     *  Checks if the current execution thread is running inside one of the event dispatchers.
     */
    private fun isDispatch(type: EDType): Boolean {
        val threadId = Thread.currentThread().id

        return threadIds[type.ordinal].value == threadId
    }

    /**
     * Each event type runs inside its own coroutine dispatcher.
     *
     * We want EACH event type to run in its own dispatcher... on its OWN thread, in order to prevent deadlocks
     * This is because there are blocking dependencies: DISCONNECT -> CONNECT.
     *
     * If an event is RE-ENTRANT, then it will immediately execute!
     */
    private fun launch(event: EDType, function: () -> Unit) {
        val eventId = event.ordinal

        try {
            if (DEBUG_EVENTS) {
                val id = traceId.getAndIncrement()
                executors[eventId].submit {
                    if (logger.isDebugEnabled) {
                        logger.debug("Starting $event : $id")
                    }
                    function()
                    if (logger.isDebugEnabled) {
                        logger.debug("Finished $event : $id")
                    }
                }
            } else {
                executors[eventId].submit(function)
            }
        } catch (e: Exception) {
            logger.error("Error during event dispatch!", e)
        }
    }
}
