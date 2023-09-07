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
import java.util.concurrent.locks.ReentrantLock

data class ResponseWaiter(val id: Int) {
//    @Volatile
//    private var latch = dorkbox.util.sync.CountDownLatch(1)

    private val lock = ReentrantLock()
    private val condition = lock.newCondition()

    // holds the RMI result or callback. This is ALWAYS accessed from within a lock (so no synchronize/volatile/etc necessary)!
    @Volatile
    var result: Any? = null

    /**
     * this will set the result to null
     */
    fun prep() {
        result = null
//        latch = dorkbox.util.sync.CountDownLatch(1)
    }

    /**
     * Waits until another thread invokes "doWait"
     */
    fun doNotify() {
//        latch.countDown()
        try {
            lock.withLock {
                condition.signal()
            }
        } catch (ignored: Throwable) {
        }
    }

    /**
     * Waits a specific amount of time until another thread invokes "doNotify"
     */
    fun doWait() {
//        latch.await()
        try {
            lock.withLock {
                condition.await()
            }
        } catch (ignored: Throwable) {
        }
    }

    /**
     * Waits a specific amount of time until another thread invokes "doNotify"
     */
    fun doWait(timeout: Long) {
//        latch.await(timeout, TimeUnit.MILLISECONDS)
        try {
            lock.withLock {
                condition.await(timeout, TimeUnit.MILLISECONDS)
            }
        } catch (ignored: Throwable) {
        }
    }
}
