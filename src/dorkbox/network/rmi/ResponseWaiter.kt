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
package dorkbox.network.rmi

import kotlinx.atomicfu.locks.withLock
import java.util.concurrent.*
import java.util.concurrent.locks.*

data class ResponseWaiter(val id: Int) {

    private val lock = ReentrantLock()
    private val condition = lock.newCondition()

    @Volatile
    private var signalled = false

    // holds the RMI result or callback. This is ALWAYS accessed from within a lock (so no synchronize/volatile/etc necessary)!
    @Volatile
    var result: Any? = null

    /**
     * this will set the result to null
     */
    fun prep() {
        result = null
        signalled = false
    }

    /**
     * Waits until another thread invokes "doWait"
     */
    fun doNotify() {
        try {
            lock.withLock {
                signalled = true
                condition.signal()
            }
        } catch (ignored: Throwable) {
        }
    }

    /**
     * Waits a specific amount of time until another thread invokes "doNotify"
     */
    fun doWait() {
        try {
            lock.withLock {
                if (signalled) {
                    return
                }
                condition.await()
            }
        } catch (ignored: Throwable) {
        }
    }

    /**
     * Waits a specific amount of time until another thread invokes "doNotify"
     */
    fun doWait(timeout: Long): Boolean {
        return try {
            lock.withLock {
                if (signalled) {
                    true
                } else {
                    condition.await(timeout, TimeUnit.MILLISECONDS)
                }
            }
        } catch (ignored: Throwable) {
            // we were interrupted BEFORE the timeout, so technically, the timeout did not elapse.
            true
        }
    }
}
