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

import dorkbox.network.connection.Connection
import dorkbox.network.connection.EventDispatcher
import dorkbox.objectPool.ObjectPool
import dorkbox.objectPool.SuspendingPool
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.delay
import mu.KLogger
import mu.KotlinLogging
import java.util.concurrent.locks.*
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Manages the "pending response" from method invocation.
 *
 *
 * Response IDs are used for in-flight RMI on the network stack. and are limited to 65,535 TOTAL
 *
 *  - these are just looped around in a ring buffer.
 *  - these are stored here as int, however these are REALLY shorts and are int-packed when transferring data on the wire
 *
 *  (By default, for RMI...)
 *  - 0 is reserved for INVALID
 *  - 1 is reserved for ASYNC (the response will never be sent back, and we don't wait for it)
 *
 */
internal class ResponseManager(maxValuesInCache: Int = 65534, minimumValue: Int = 2) {
    companion object {
        val TIMEOUT_EXCEPTION = Exception().apply { stackTrace = arrayOf<StackTraceElement>() }
        private val logger: KLogger = KotlinLogging.logger(ResponseManager::class.java.simpleName)
    }

    private val rmiWaitersInUse = atomic(0)
    private val waiterCache: SuspendingPool<ResponseWaiter>

    private val pendingLock = ReentrantReadWriteLock()
    private val pending = arrayOfNulls<Any?>(maxValuesInCache+1) // +1 because it's possible to have the value 65535 in the cache

    init {
        require(maxValuesInCache <= 65535) { "The maximum size for the values in the response manager is 65535"}
        require(maxValuesInCache > minimumValue) { "< $minimumValue (0 and 1 for RMI) are reserved"}
        require(minimumValue > 0) { "The minimum value $minimumValue must be > 0"}

        // create a shuffled list of ID's. This operation is ONLY performed ONE TIME per endpoint!
        val ids = mutableListOf<ResponseWaiter>()

        // 0 is special, and is never added!
        // 1 is special, and is used for ASYNC (the response will never be sent back)
        for (id in minimumValue..maxValuesInCache) {
            ids.add(ResponseWaiter(id))
        }
        ids.shuffle()

        // populate the array of randomly assigned ID's + waiters.
        waiterCache = ObjectPool.suspending(ids)
    }

    /**
     * Called when we receive the answer for our initial request. If no response data, then the pending rmi data entry is deleted
     *
     * resume any pending remote object method invocations (if they are not async, or not manually waiting)
     * NOTE: async RMI will never call this (because async doesn't return a response)
     */
    suspend fun notifyWaiter(id: Int, result: Any?, logger: KLogger) {
        logger.trace { "[RM] notify: $id" }

        val previous = pendingLock.write {
            val previous = pending[id]
            pending[id] = result
            previous
        }

        // if NULL, since either we don't exist (because we were async), or it was cancelled
        if (previous is ResponseWaiter) {
            logger.trace { "[RM] valid-cancel: $id" }

            // this means we were NOT timed out! (we cannot be timed out here)
            previous.doNotify()
        }
    }

    /**
     * Called when we receive the answer for our initial request. If no response data, then the pending rmi data entry is deleted
     *
     * This is ONLY called when we want to get the data out of the stored entry, because we are operating ASYNC. (pure RMI async is different)
     */
    suspend fun <T> getWaiterCallback(id: Int, logger: KLogger): T? {
        logger.trace { "[RM] get-callback: $id" }

        val previous = pendingLock.write {
            val previous = pending[id]
            pending[id] = null
            previous
        }

        @Suppress("UNCHECKED_CAST")
        if (previous is ResponseWaiter) {
            val result = previous.result

            // always return this to the cache!
            waiterCache.put(previous)
            rmiWaitersInUse.getAndDecrement()

            return result as T
        }

        return null
    }

    /**
     * gets the ResponseWaiter (id + waiter) and prepares the pending response map
     *
     * We ONLY care about the ID to get the correct response info. If there is no response, the ID can be ignored.
     */
    suspend fun prep(logger: KLogger): ResponseWaiter {
        val waiter = waiterCache.take()
        rmiWaitersInUse.getAndIncrement()
        logger.trace { "[RM] prep in-use: ${rmiWaitersInUse.value}" }

        // this will replace the waiter if it was cancelled (waiters are not valid if cancelled)
        waiter.prep()

        pendingLock.write {
            pending[waiter.id] = waiter
        }

        return waiter
    }

    /**
     * gets the ResponseWaiter (id + waiter) and prepares the pending response map
     *
     * We ONLY care about the ID to get the correct response info. If there is no response, the ID can be ignored.
     */
    suspend fun prepWithCallback(logger: KLogger, function: Any): Int {
        val waiter = waiterCache.take()
        rmiWaitersInUse.getAndIncrement()
        logger.trace { "[RM] prep in-use: ${rmiWaitersInUse.value}" }

        // this will replace the waiter if it was cancelled (waiters are not valid if cancelled)
        waiter.prep()

        // assign the callback that will be notified when the return message is received
        waiter.result = function

        val id = RmiUtils.unpackUnsignedRight(waiter.id)

        pendingLock.write {
            pending[id] = waiter
        }

        return id
    }


    /**
     * Cancels the RMI request in the given timeout, the callback is executed inside the read lock
     */
    suspend fun cancelRequest(timeoutMillis: Long, id: Int, logger: KLogger, onCancelled: ResponseWaiter.() -> Unit) {
        EventDispatcher.RESPONSE_MANAGER.launch {
            delay(timeoutMillis) // this will always wait. if this job is cancelled, this will immediately stop waiting

            // check if we have a result or not
            pendingLock.read {
                val maybeResult = pending[id]
                if (maybeResult is ResponseWaiter) {
                    logger.trace { "[RM] timeout ($timeoutMillis) with callback cancel: $id" }

                    maybeResult.cancel()
                    onCancelled(maybeResult)
                }
            }
        }
    }

    /**
     * We only wait for a reply if we are SYNC.
     *
     * ASYNC does not send a response
     *
     * @return the result (can be null) or timeout exception
     */
    suspend fun waitForReply(
        responseWaiter: ResponseWaiter,
        timeoutMillis: Long,
        logger: KLogger,
        connection: Connection
    ): Any? {
        val id = RmiUtils.unpackUnsignedRight(responseWaiter.id)

        logger.trace { "[RM] wait: $id" }

        // NOTE: we ALWAYS send a response from the remote end (except when async).
        //
        // 'async' -> DO NOT WAIT (no response)
        // 'timeout > 0' -> WAIT w/ TIMEOUT
        // 'timeout == 0' -> WAIT FOREVER
        if (timeoutMillis > 0) {
            val responseTimeoutJob = EventDispatcher.RESPONSE_MANAGER.launch {
                delay(timeoutMillis) // this will always wait. if this job is cancelled, this will immediately stop waiting

                // check if we have a result or not
                val maybeResult = pendingLock.read { pending[id] }
                if (maybeResult is ResponseWaiter) {
                    logger.trace { "[RM] timeout ($timeoutMillis) cancel: $id" }

                    maybeResult.cancel()
                }
            }

            // wait for the response.
            //
            // If the response is ALREADY here, the doWait() returns instantly (with result)
            // if no response yet, it will suspend and either
            //   A) get response
            //   B) timeout
            responseWaiter.doWait()

            // always cancel the timeout
            responseTimeoutJob.cancel()
        } else {
            // wait for the response --- THIS WAITS FOREVER (there is no timeout)!
            //
            // If the response is ALREADY here, the doWait() returns instantly (with result)
            // if no response yet, it will suspend and
            //   A) get response
            responseWaiter.doWait()
        }


        // deletes the entry in the map
        val resultOrWaiter = pendingLock.write {
            val previous = pending[id]
            pending[id] = null
            previous
        }

        // always return the waiter to the cache
        waiterCache.put(responseWaiter)
        rmiWaitersInUse.getAndDecrement()

        if (resultOrWaiter is ResponseWaiter) {
            logger.trace { "[RM] timeout cancel ($timeoutMillis): $id" }

            return if (connection.isClosed() || connection.isClosedViaAeron()) {
                null
            } else {
                TIMEOUT_EXCEPTION
            }
        }

        return resultOrWaiter
    }

    suspend fun close() {
        logger.info { "Closing the RMI manager" }

        // wait for responses, or wait for timeouts!
        while (rmiWaitersInUse.value > 0) {
            delay(100)
        }

        pendingLock.write {
            pending.forEachIndexed { index, _ ->
                pending[index] = null
            }
        }
    }
}
