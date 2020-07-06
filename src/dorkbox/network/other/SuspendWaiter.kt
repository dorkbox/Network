package dorkbox.network.other

import kotlinx.coroutines.channels.Channel

// this is bi-directional waiting. The method names to not reflect this, however there is no possibility of race conditions w.r.t. waiting
// https://kotlinlang.org/docs/reference/coroutines/channels.html
inline class SuspendWaiter(private val channel: Channel<Unit> = Channel(0)) {
    // "receive' suspends until another coroutine invokes "send"
    // and
    // "send" suspends until another coroutine invokes "receive".
    suspend fun doWait() { channel.receive() }
    suspend fun doNotify() { channel.send(Unit) }
    fun cancel() { channel.cancel() }
}
