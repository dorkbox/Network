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

import kotlinx.coroutines.channels.Channel

internal data class ResponseWaiter(val id: Int) {
    // this is bi-directional waiting. The method names to not reflect this, however there is no possibility of race conditions w.r.t. waiting
    // https://stackoverflow.com/questions/55421710/how-to-suspend-kotlin-coroutine-until-notified
    // https://kotlinlang.org/docs/reference/coroutines/channels.html

    // "receive' suspends until another coroutine invokes "send"
    // and
    // "send" suspends until another coroutine invokes "receive".
    //
    // these are wrapped in a try/catch, because cancel will cause exceptions to be thrown (which we DO NOT want)
    var channel: Channel<Unit> = Channel(Channel.RENDEZVOUS)
    var isCancelled = false

    // holds the RMI result. This is ALWAYS accessed from within a lock!
    var result: Any? = null


    /**
     * this will replace the waiter if it was cancelled (waiters are not valid if cancelled)
     */
    fun prep() {
        if (isCancelled) {
            isCancelled = false
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
            isCancelled = true
            channel.cancel()
        } catch (ignored: Exception) {
        }
    }
}
