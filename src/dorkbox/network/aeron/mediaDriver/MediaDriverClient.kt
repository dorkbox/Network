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

package dorkbox.network.aeron.mediaDriver

import dorkbox.util.logger
import io.aeron.Publication
import io.aeron.Subscription

abstract class MediaDriverClient(val port: Int,
                                 val streamId: Int,
                                 val remoteSessionId: Int,
                                 val localSessionId: Int,
                                 val connectionTimeoutSec: Int,
                                 val isReliable: Boolean) : MediaDriverConnection {

    lateinit var subscription: Subscription
    lateinit var publication: Publication

    val subscriptionPort: Int by lazy {
        if (this is ClientIpcDriver) {
            localSessionId
        } else {
            val addressesAndPorts = subscription.localSocketAddresses()
            if (addressesAndPorts.size > 1) {
                logger().error { "Subscription ports for client is MORE than 1. This is 'ok', but we only support the use the first one!" }
            }

            val first = addressesAndPorts.first()

            // split
            val splitPoint = first.lastIndexOf(':')
            val port = first.substring(splitPoint+1)
            port.toInt()
        }
    }
}
