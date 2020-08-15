package dorkbox.network.rmi

import dorkbox.network.rmi.messages.MethodResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.agrona.collections.Hashing
import org.agrona.collections.Int2NullableObjectHashMap
import org.agrona.collections.IntArrayList
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

internal data class RmiWaiter(val id: Int) {
    // this is bi-directional waiting. The method names to not reflect this, however there is no possibility of race conditions w.r.t. waiting
    // https://stackoverflow.com/questions/55421710/how-to-suspend-kotlin-coroutine-until-notified
    // https://kotlinlang.org/docs/reference/coroutines/channels.html

    // "receive' suspends until another coroutine invokes "send"
    // and
    // "send" suspends until another coroutine invokes "receive".
    //
    // these are wrapped in a try/catch, because cancel will cause exceptions to be thrown (which we DO NOT want)
    @Volatile
    var channel: Channel<Unit> = Channel(0)

    /**
     * this will replace the waiter if it was cancelled (waiters are not valid if cancelled)
     */
    fun prep() {
        @Suppress("EXPERIMENTAL_API_USAGE")
        if (channel.isClosedForReceive && channel.isClosedForSend) {
            channel = Channel(0)
        }
    }

    suspend fun doNotify() {
        try {
            channel.send(Unit)
        } catch (ignored: Exception) {
        }
    }

    suspend fun doWait() {
        try {
            channel.receive()
        } catch (ignored: Exception) {
        }
    }

    fun cancel() {
        try {
            channel.cancel()
        } catch (ignored: Exception) {
        }
    }
}



/**
 * Manages the "pending response" from method invocation.
 *
 * response ID's and the memory they hold will leak if the response never arrives!
 */
internal class RmiResponseStorage(private val actionDispatch: CoroutineScope) {

    companion object {
        val TIMEOUT_EXCEPTION = Exception()
    }

    // Response ID's are for ALL in-flight RMI on the network stack. instead of limited to (originally) 64, we are now limited to 65,535
    // these are just looped around in a ring buffer.
    // These are stored here as int, however these are REALLY shorts and are int-packed when transferring data on the wire
    // 64,000 IN FLIGHT RMI method invocations is PLENTY
    private val maxValuesInCache = (Short.MAX_VALUE.toInt() * 2) - 1 // -1 because 0 is reserved
    private val rmiWaiterCache = Channel<RmiWaiter>(maxValuesInCache)

    private val pendingLock = ReentrantReadWriteLock()
    private val pending = Int2NullableObjectHashMap<Any>(32, Hashing.DEFAULT_LOAD_FACTOR, true)


    init {
        // create a shuffled list of ID's. This operation is ONLY performed ONE TIME per endpoint!
        val ids = IntArrayList(maxValuesInCache, Integer.MIN_VALUE)
        for (id in Short.MIN_VALUE..-1) {
            ids.addInt(id)
        }
        // ZERO is special, and is never added!
        for (id in 1..Short.MAX_VALUE) {
            ids.addInt(id)
        }
        ids.shuffle()

        // populate the array of randomly assigned ID's + waiters.
        ids.forEach {
            rmiWaiterCache.offer(RmiWaiter(it))
        }
    }


    // resume any pending remote object method invocations (if they are not async, or not manually waiting)
    suspend fun onMessage(message: MethodResponse) {
        val objectId = message.objectId
        val responseId = message.responseId
        val result = message.result

        val pendingId = RmiUtils.packShorts(objectId, responseId)

        val previous = pendingLock.write { pending.put(pendingId, result) }

        // if NULL, since either we don't exist, or it was cancelled
        if (previous is RmiWaiter) {
            // this means we were NOT timed out! If we were cancelled, then this does nothing.
            previous.doNotify()

            // since this was the FIRST one to trigger, return it to the cache.
            rmiWaiterCache.send(previous)
        }
    }

    /**
     * gets the RmiWaiter (id + waiter)
     */
    internal suspend fun prep(rmiObjectId: Int): RmiWaiter {
        val responseRmi = rmiWaiterCache.receive()

        // this will replace the waiter if it was cancelled (waiters are not valid if cancelled)
        responseRmi.prep()

        // we pack them together so we can fully use the range of ints, so we can service ALL rmi requests in a single spot
        pendingLock.write { pending[RmiUtils.packShorts(rmiObjectId, responseRmi.id)] = responseRmi }

        return responseRmi
    }

    /**
     * @return the result (can be null) or timeout exception
     */
    suspend fun waitForReply(isAsync: Boolean, rmiObjectId: Int, rmiWaiter: RmiWaiter, timeoutMillis: Long): Any? {

        val pendingId = RmiUtils.packShorts(rmiObjectId, rmiWaiter.id)

        // NOTE: we ALWAYS send a response from the remote end.
        //
        // 'async' -> DO NOT WAIT
        // 'timeout > 0' -> WAIT
        // 'timeout == 0' -> same as async (DO NOT WAIT)
        val returnImmediately = isAsync || timeoutMillis <= 0L

        if (returnImmediately) {
            return null
        }

        val responseTimeoutJob = actionDispatch.launch {
            delay(timeoutMillis) // this will always wait

            // check if we have a result or not
            val maybeResult = pendingLock.read { pending[pendingId] }
            if (maybeResult is RmiWaiter) {
                maybeResult.cancel()
            }
        }

        // wait for the response.
        //
        // If the response is ALREADY here, the doWait() returns instantly (with result)
        // if no response yet, it will suspend and either
        //   A) get response
        //   B) timeout
        rmiWaiter.doWait()

        // always cancel the timeout
        responseTimeoutJob.cancel()

        val resultOrWaiter = pendingLock.write { pending.remove(pendingId) }
        if (resultOrWaiter is RmiWaiter) {
            // since this was the FIRST one to trigger, return it to the cache.
            rmiWaiterCache.send(resultOrWaiter)
            return TIMEOUT_EXCEPTION
        }

        return resultOrWaiter
    }

    fun close() {
        rmiWaiterCache.close()
        pendingLock.write { pending.clear() }
    }
}
