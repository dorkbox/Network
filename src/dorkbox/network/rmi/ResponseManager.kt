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
import dorkbox.objectPool.ObjectPool
import dorkbox.objectPool.Pool
import kotlinx.atomicfu.atomic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.locks.*
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
        private val logger: Logger = LoggerFactory.getLogger(ResponseManager::class.java.simpleName)
    }

    private val rmiWaitersInUse = atomic(0)
    private val waiterCache: Pool<ResponseWaiter>

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
        waiterCache = ObjectPool.blocking(ids)
    }

    /**
     * Called when we receive the answer for our initial request. If no response data, then the pending rmi data entry is deleted
     *
     * resume any pending remote object method invocations (if they are not async, or not manually waiting)
     * NOTE: async RMI will never call this (because async doesn't return a response)
     */
    fun notifyWaiter(id: Int, result: Any?, logger: Logger) {
        if (logger.isTraceEnabled) {
            logger.trace("[RM] notify: $id")
        }

        val previous = pendingLock.write {
            val previous = pending[id]
            pending[id] = result
            previous
        }

        // if NULL, since either we don't exist (because we were async), or it was cancelled
        if (previous is ResponseWaiter) {
            if (logger.isTraceEnabled) {
                logger.trace("[RM] valid-notify: $id")
            }

            // this means we were NOT timed out! (we cannot be timed out here)
            previous.doNotify()
        }
    }

    /**
     * Called when we receive the answer for our initial request. If no response data, then the pending rmi data entry is deleted
     *
     * This is ONLY called when we want to get the data out of the stored entry, because we are operating ASYNC. (pure RMI async is different)
     */
    fun <T> removeWaiterCallback(id: Int, logger: Logger): T? {
        if (logger.isTraceEnabled) {
            logger.trace("[RM] get-callback: $id")
        }

        val previous = pendingLock.write {
            val previous = pending[id]
            pending[id] = null
            previous
        }

        @Suppress("UNCHECKED_CAST")
        if (previous is ResponseWaiter) {
            val result = previous.result

            // always return this to the cache!
            previous.result = null
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
    fun prep(logger: Logger): ResponseWaiter {
        val waiter = waiterCache.take()
        rmiWaitersInUse.getAndIncrement()
        if (logger.isTraceEnabled) {
            logger.trace("[RM] prep in-use: ${rmiWaitersInUse.value}")
        }

        // this will initialize the result
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
    fun prepWithCallback(logger: Logger, function: Any): Int {
        val waiter = waiterCache.take()
        rmiWaitersInUse.getAndIncrement()
        if (logger.isTraceEnabled) {
            logger.trace("[RM] prep in-use: ${rmiWaitersInUse.value}")
        }

        // this will initialize the result
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
     * We only wait for a reply if we are SYNC.
     *
     * ASYNC does not send a response and does not call this method
     *
     * @return the result (can be null) or timeout exception
     */
    fun getReply(
        responseWaiter: ResponseWaiter,
        timeoutMillis: Long,
        logger: Logger,
        connection: Connection
    ): Any? {
        val id = RmiUtils.unpackUnsignedRight(responseWaiter.id)

        if (logger.isTraceEnabled) {
            logger.trace("[RM] get: $id")
        }

        // deletes the entry in the map
        val resultOrWaiter = pendingLock.write {
            val previous = pending[id]
            pending[id] = null
            previous
        }

        // always return the waiter to the cache
        responseWaiter.result = null
        waiterCache.put(responseWaiter)
        rmiWaitersInUse.getAndDecrement()

        if (resultOrWaiter is ResponseWaiter) {
            if (logger.isTraceEnabled) {
                logger.trace("[RM] timeout cancel ($timeoutMillis): $id")
            }

            return if (connection.isClosed() || connection.isClosed()) {
                null
            } else {
                TIMEOUT_EXCEPTION
            }
        }

        return resultOrWaiter
    }

    fun abort(responseWaiter: ResponseWaiter, logger: Logger) {
        val id = RmiUtils.unpackUnsignedRight(responseWaiter.id)

        if (logger.isTraceEnabled) {
            logger.trace("[RM] abort: $id")
        }

        // deletes the entry in the map
        pendingLock.write {
            pending[id] = null
        }

        // always return the waiter to the cache
        responseWaiter.result = null
        waiterCache.put(responseWaiter)
        rmiWaitersInUse.getAndDecrement()
    }

    fun close() {
        // technically, this isn't closing it, so much as it's cleaning it out
        if (logger.isDebugEnabled) {
            logger.debug("Closing the response manager for RMI")
        }

        // wait for responses, or wait for timeouts!
        while (rmiWaitersInUse.value > 0) {
            Thread.sleep(50)
        }

        pendingLock.write {
            pending.forEachIndexed { index, _ ->
                pending[index] = null
            }
        }
    }
}
