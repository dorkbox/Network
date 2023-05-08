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
import io.aeron.Subscription
import mu.KLogger

abstract class MediaDriverServer(val aeronDriver: AeronDriver,
                                 val port: Int,
                                 val streamId: Int,
                                 val sessionId: Int,
                                 val connectionTimeoutSec: Int,
                                 val isReliable: Boolean,
                                 val logInfo: String
) : MediaDriverConnection {

    @Volatile
    lateinit var subscription: Subscription

    @Volatile
    var info = ""

    override suspend fun close(logger: KLogger) {
        // on close, we want to make sure this file is DELETED!
        aeronDriver.closeAndDeleteSubscription(subscription, logInfo)
    }

    override fun toString(): String {
        return info
    }
}
