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

import io.aeron.ChannelUriStringBuilder
import java.net.Inet4Address
import java.net.InetAddress

abstract class MediaDriverConnection(val subscriptionPort: Int,
                                     val streamId: Int, val sessionId: Int,
                                     val connectionTimeoutSec: Int, val isReliable: Boolean) {

    var success: Boolean = false
    abstract val type: String

    companion object {
        fun connectionString(ipAddress: InetAddress): String {
            return if (ipAddress is Inet4Address) {
                ipAddress.hostAddress
            } else {
                // IPv6 requires the address to be bracketed by [...]
                val host = ipAddress.hostAddress
                if (host[0] == '[') {
                    host
                } else {
                    "[${ipAddress.hostAddress}]"
                }
            }
        }

        fun uri(type: String, sessionId: Int, isReliable: Boolean? = null): ChannelUriStringBuilder {
            val builder = ChannelUriStringBuilder().media(type)
            if (isReliable != null) {
                builder.reliable(isReliable)
            }

            if (sessionId != AeronDriver.RESERVED_SESSION_ID_INVALID) {
                builder.sessionId(sessionId)
            }

            return builder
        }

        fun uriEndpoint(type: String, sessionId: Int, isReliable: Boolean, endpoint: String): ChannelUriStringBuilder {
            val builder = uri(type, sessionId, isReliable)
            builder.endpoint(endpoint)
            return builder
        }
    }



    // We don't use 'suspend' for these, because we have to pump events from a NORMAL thread. If there are any suspend points, there is
    // the potential for a live-lock due to coroutine scheduling
    abstract val info : String
}
