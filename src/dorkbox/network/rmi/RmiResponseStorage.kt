package dorkbox.network.rmi

import com.conversantmedia.util.concurrent.MultithreadConcurrentQueue
import dorkbox.network.other.SuspendWaiter
import dorkbox.network.rmi.messages.MethodResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.agrona.collections.Hashing
import org.agrona.collections.Int2NullableObjectHashMap
import org.agrona.collections.IntArrayList
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Manages the "pending response" from method invocation.
 *
 * response ID's and the memory they hold will leak if the response never arrives!
 */
class RmiResponseStorage(private val actionDispatch: CoroutineScope) {
    // Response ID's are for ALL in-flight RMI on the network stack. instead of limited to (originally) 64, we are now limited to 65,535
    // these are just looped around in a ring buffer.
    // These are stored here as int, however these are REALLY shorts and are int-packed when transferring data on the wire
    private val rmiResponseIds = MultithreadConcurrentQueue<Int>(65535)

    private val pendingLock = ReentrantReadWriteLock()
    private val pending = Int2NullableObjectHashMap<Any>(32, Hashing.DEFAULT_LOAD_FACTOR, true)

    init {
        // create a shuffled list of ID's. This operation is ONLY performed ONE TIME per endpoint!
        val ids = IntArrayList()
        for (id in Short.MIN_VALUE..Short.MAX_VALUE) {
            ids.addInt(id)
        }
        ids.shuffle()

        // populate the array of randomly assigned ID's.
        ids.forEach {
            rmiResponseIds.offer(it)
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
        if (previous is SuspendWaiter) {
            // this means we were NOT timed out!
            previous.doNotify()
        }

        // always return the responseId! It will (hopefully) be a while before this ID is used again
        rmiResponseIds.offer(responseId)
    }

    fun prep(rmiObjectId: Int, responseWaiter: SuspendWaiter): Int {
        val responseId = rmiResponseIds.poll()

        // we pack them together so we can fully use the range of ints, so we can service ALL rmi requests in a single spot
        pendingLock.write { pending[RmiUtils.packShorts(rmiObjectId, responseId)] = responseWaiter }

        return responseId
    }

    suspend fun waitForReply(allowWaiting: Boolean, isAsync: Boolean, rmiObjectId: Int, responseId: Int,
                             responseWaiter: SuspendWaiter, timeoutMillis: Long): Any? {

        val pendingId = RmiUtils.packShorts(rmiObjectId, responseId)

        var delayJobForTimeout: Job? = null

        if (!(isAsync && allowWaiting) && timeoutMillis > 0L) {
            // always launch a "cancel" coroutine, unless we want to wait forever
            delayJobForTimeout = actionDispatch.launch {
                delay(timeoutMillis)

                val previous = pendingLock.write {
                    val prev = pending.remove(pendingId)
                    if (prev is SuspendWaiter) {
                        pending[pendingId] = TimeoutException("Response timed out.")
                    }

                    prev
                }

                // if we are NOT SuspendWaiter, then it means we had a result!
                //
                // If there are tight timing issues, then we err on the side of "you timed out"

                if (!isAsync) {
                    // we only cancel waiting because when NON-ASYNC
                    if (previous is SuspendWaiter) {
                        previous.cancel()
                    }
                }
            }
        }

        return if (isAsync) {
            null
        } else {
            waitForReplyManually(pendingId, responseWaiter, delayJobForTimeout)
        }
    }

    // this is called when we MANUALLY want to wait for a reply as part of async!
    // A timeout of 0 means we wait forever
    suspend fun waitForReplyManually(rmiObjectId: Int, responseId: Int, responseWaiter: SuspendWaiter): Any? {
        val pendingId = RmiUtils.packShorts(rmiObjectId, responseId)
        return waitForReplyManually(pendingId, responseWaiter, null)
    }


    // we have to be careful when we resume, because SOMEONE ELSE'S METHOD RESPONSE can resume us (but only from the same object)!
    private suspend fun waitForReplyManually(pendingId: Int,responseWaiter: SuspendWaiter, delayJobForTimeout: Job?): Any? {
        while(true) {
            val checkResult = pendingLock.read { pending[pendingId] }
            if (checkResult !is SuspendWaiter) {
                // this means we have correct data! (or it was an exception) we can safely remove the data from the map
                pendingLock.write { pending.remove(pendingId) }

                delayJobForTimeout?.cancel()
                return checkResult
            }

            // keep waiting, since we don't have a response yet
            responseWaiter.doWait()
        }
    }

    fun close() {
        rmiResponseIds.clear()
        pendingLock.write { pending.clear() }
    }
}
