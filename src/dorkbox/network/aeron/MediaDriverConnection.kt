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
@file:Suppress("DuplicatedCode")

package dorkbox.network.aeron

import io.aeron.Publication
import io.aeron.Subscription
import mu.KLogger

abstract class MediaDriverConnection(val publicationPort: Int, val subscriptionPort: Int,
                                     val streamId: Int, val sessionId: Int,
                                     val connectionTimeoutSec: Int, val isReliable: Boolean) : AutoCloseable {

    var success: Boolean = false
    lateinit var subscription: Subscription
    lateinit var publication: Publication


    // We don't use 'suspend' for these, because we have to pump events from a NORMAL thread. If there are any suspend points, there is
    // the potential for a live-lock due to coroutine scheduling
    abstract fun buildClient(aeronDriver: AeronDriver, logger: KLogger)
    abstract fun buildServer(aeronDriver: AeronDriver, logger: KLogger, pairConnection: Boolean = false)

    abstract val clientInfo : String
    abstract val serverInfo : String

    override fun close() {
        if (success) {
            subscription.close()
            publication.close()
        }
    }
}
