/*
 * Copyright 2020 dorkbox, llc
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

import dorkbox.network.rmi.messages.MethodResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KLogger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Manages the "pending response" from method invocation.
 *
 * response ID's and the memory they hold will leak if the response never arrives!
 */
internal class RmiResponseManager(private val logger: KLogger, private val actionDispatch: CoroutineScope) {
    companion object {
        val TIMEOUT_EXCEPTION = Exception()
        val ASYNC_WAITER = RmiWaiter(RemoteObjectStorage.ASYNC_RMI) // this is never waited on, we just need this to optimize how we assigned waiters.
    }


    // Response ID's are for ALL in-flight RMI on the network stack. instead of limited to (originally) 64, we are now limited to 65,535
    // these are just looped around in a ring buffer.
    // These are stored here as int, however these are REALLY shorts and are int-packed when transferring data on the wire
    // 65535 IN FLIGHT RMI method invocations is plenty
    //   0 is reserved for INVALID
    //   1 is reserved for ASYNC
    private val maxValuesInCache = 65535
    private val rmiWaiterCache = Channel<RmiWaiter>(maxValuesInCache)

    private val pendingLock = ReentrantReadWriteLock()
    private val pending = arrayOfNulls<Any>(maxValuesInCache)

    init {
        // create a shuffled list of ID's. This operation is ONLY performed ONE TIME per endpoint!
        val ids = mutableListOf<Int>()
        for (id in Short.MIN_VALUE..-1) {
            ids.add(id)
        }

        // MIN (32768) -> -1 (65535)
        // 2 (2) -> MAX (32767)

        // ZERO is special, and is never added!
        // ONE is special, and is used for ASYNC (the response will never be sent back)

        for (id in 2..Short.MAX_VALUE) {
            ids.add(id)
        }
        ids.shuffle()

        // populate the array of randomly assigned ID's + waiters.
        for (it in ids) {
            rmiWaiterCache.offer(RmiWaiter(it))
        }
    }


    // resume any pending remote object method invocations (if they are not async, or not manually waiting)
    // async RMI will never get here!
    suspend fun onMessage(message: MethodResponse) {
        val rmiId = RmiUtils.unpackUnsignedRight(message.packedId)
        val result = message.result

        logger.trace { "RMI return: $rmiId" }

        val previous = pendingLock.write {
            val previous = pending[rmiId]
            pending[rmiId] = result
            previous
        }

        // if NULL, since either we don't exist (because we were async), or it was cancelled
        if (previous is RmiWaiter) {
            logger.trace { "RMI valid-cancel: $rmiId" }

            // this means we were NOT timed out! (we cannot be timed out here)
            previous.doNotify()

            // since this was the FIRST one to trigger, return it to the cache.
            rmiWaiterCache.send(previous)
        }
    }

    /**
     * gets the RmiWaiter (id + waiter).
     *
     * We ONLY care about the ID to get the correct response info. If there is no response, the ID can be ignored.
     */
    internal suspend fun prep(isAsync: Boolean): RmiWaiter {
        return if (isAsync) {
            ASYNC_WAITER
        } else {
            val responseRmi = rmiWaiterCache.receive()

            // this will replace the waiter if it was cancelled (waiters are not valid if cancelled)
            responseRmi.prep()

            pendingLock.write {
                // this just does a .toUShort().toInt() conversion. This is cleaner than doing it manually
                pending[RmiUtils.unpackUnsignedRight(responseRmi.id)] = responseRmi
            }

            responseRmi
        }
    }

    /**
     * @return the result (can be null) or timeout exception
     */
    suspend fun waitForReply(isAsync: Boolean, rmiWaiter: RmiWaiter, timeoutMillis: Long): Any? {
        if (isAsync) {
            return null
        }

        @Suppress("EXPERIMENTAL_API_USAGE")
        val rmiId = RmiUtils.unpackUnsignedRight(rmiWaiter.id)  // this just does a .toUShort().toInt() conversion. This is cleaner than doing it manually

        // NOTE: we ALWAYS send a response from the remote end.
        //
        // 'async' -> DO NOT WAIT (and no response)
        // 'timeout > 0' -> WAIT
        // 'timeout <= 0' -> same as async (DO NOT WAIT)


        val responseTimeoutJob = actionDispatch.launch {
            delay(timeoutMillis) // this will always wait. if this job is cancelled, this will immediately stop waiting

            // check if we have a result or not
            val maybeResult = pendingLock.read { pending[rmiId] }
            if (maybeResult is RmiWaiter) {
                logger.trace { "RMI timeout ($timeoutMillis) cancel: $rmiId" }

                maybeResult.cancel()
            }
        }

        logger.trace {
            "RMI waiting: $rmiId"
        }

        // wait for the response.
        //
        // If the response is ALREADY here, the doWait() returns instantly (with result)
        // if no response yet, it will suspend and either
        //   A) get response
        //   B) timeout
        rmiWaiter.doWait()

        val resultOrWaiter = pendingLock.write {
            val previous = pending[rmiId]
            pending[rmiId] = null
            previous
        }

        // always cancel the timeout
        responseTimeoutJob.cancel()

        if (resultOrWaiter is RmiWaiter) {
            logger.trace { "RMI was canceled ($timeoutMillis): $rmiId" }

            // since this was the FIRST one to trigger, return it to the cache.
            rmiWaiterCache.send(resultOrWaiter)
            return TIMEOUT_EXCEPTION
        }

        return resultOrWaiter
    }

    fun close() {
        pendingLock.write {
            pending.forEachIndexed { index, any ->
                pending[index] = null
            }
        }
        rmiWaiterCache.close()
    }
}
