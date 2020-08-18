package dorkbox.network.other.coroutines

import kotlinx.coroutines.channels.Channel

// this is bi-directional waiting. The method names to not reflect this, however there is no possibility of race conditions w.r.t. waiting
// https://kotlinlang.org/docs/reference/coroutines/channels.html
inline class SuspendWaiter(private val channel: Channel<Unit> = Channel()) {
    // "receive' suspends until another coroutine invokes "send"
    // and
    // "send" suspends until another coroutine invokes "receive".
    suspend fun doWait() {
        try {
            channel.receive()
        } catch (ignored: Exception) {
        }
    }
    suspend fun doNotify() {
        try {
            channel.send(Unit)
        } catch (ignored: Exception) {
        }
    }
    fun cancel() {
        try {
            channel.cancel()
        } catch (ignored: Exception) {
        }
    }
    fun isCancelled(): Boolean {
        // once the channel is cancelled, it can never work again
        @Suppress("EXPERIMENTAL_API_USAGE")
        return channel.isClosedForReceive && channel.isClosedForSend
    }
}
