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
@file:Suppress("DuplicatedCode")

package dorkbox.network.aeron.mediaDriver

import dorkbox.network.aeron.AeronDriver
import io.aeron.Publication
import io.aeron.Subscription
import mu.KLogger

abstract class MediaDriverClient(val aeronDriver: AeronDriver,
                                 val port: Int,
                                 val streamId: Int,
                                 sessionId: Int,
                                 val connectionTimeoutSec: Int,
                                 val isReliable: Boolean,
                                 val listenType: String) : MediaDriverConnection {

    var sessionId: Int

    @Volatile
    lateinit var subscription: Subscription

    @Volatile
    lateinit var publication: Publication

    var subscriptionPort = 0

    @Volatile
    var info = ""

    init {
        this.sessionId = sessionId
    }

    override suspend fun close(logger: KLogger) {
        // on close, we want to make sure this file is DELETED!
        aeronDriver.closeAndDeleteSubscription(subscription, listenType)
        aeronDriver.closeAndDeletePublication(publication, listenType)
    }

    override fun toString(): String {
        return info
    }
}
