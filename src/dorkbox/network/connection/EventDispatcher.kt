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
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.*

class EventDispatcher {
    companion object {
        // It CANNOT be the default dispatch because there will be thread starvation
        // NOTE: THIS CANNOT CHANGE!! IT WILL BREAK EVERYTHING
        private val defaultEventDispatcher = Executors.newSingleThreadExecutor(
            NamedThreadFactory("Event Dispatcher", Configuration.networkThreadGroup, Thread.NORM_PRIORITY, true)
        )

        private val asDispatcher = defaultEventDispatcher.asCoroutineDispatcher()

        private val instances = atomic(0)
    }

    @Volatile
    private var dispatchThreadId = 0L

    @Volatile
    private var forceLaunch = false


    val wasForced: Boolean
        get() = forceLaunch


    private val eventDispatch = CoroutineScope(asDispatcher + CoroutineName("asdfasdfasdfasdf"))

    val isActive: Boolean
        get() = eventDispatch.isActive

    val isCurrent: Boolean
        get() = dispatchThreadId == Thread.currentThread().id

    init {
        instances.getAndIncrement()
        eventDispatch.launch {
            dispatchThreadId = Thread.currentThread().id
        }
    }


    /**
     * When this method is called, it can be called from different 'threads'
     *  - from a different thread
     *  - from a different coroutine
     *  - from the "same" coroutine
     *
     *  When it is from the same coroutine or from *something* whose thread originated from us, we DO NOT want to re-dispatch the event,
     *  we want to immediately execute it (via runBlocking)
     */
    fun launch(function: suspend () -> Unit): Job {
        return if (dispatchThreadId == Thread.currentThread().id) {
            kotlinx.coroutines.runBlocking {
                function()
            }
            // this job cannot be cancelled, because it will have already run by now
            Job()
        } else {
            eventDispatch.launch {
                function()
            }
        }
    }

    /**
     * Force launching the function on the event dispatch. This is for the RARE occasion that we want to make sure (that when running inside
     * our current dispatch) to FINISH running the current logic before executing the NEW logic...
     */
    fun forceLaunch(function: suspend () -> Unit): Job {
        forceLaunch = true
        return eventDispatch.launch {
            function()
            forceLaunch = false
        }
    }

    fun cancel(cause: String) {
        // because we have a SHARED thread executor-as-a-coroutine-dispatcher, if we CANCEL on one instance, it will cancel ALL instances.
        val count = instances.decrementAndGet()
        if (count == 0) {
            eventDispatch.cancel(cause)
        }
    }

    fun runBlocking(function: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking {
            function()
        }
    }
}
