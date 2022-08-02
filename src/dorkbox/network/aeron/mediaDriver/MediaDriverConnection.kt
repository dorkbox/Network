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

import dorkbox.network.aeron.AeronDriver
import io.aeron.ChannelUriStringBuilder
import java.net.Inet4Address
import java.net.InetAddress

interface MediaDriverConnection {

    val type: String

    // We don't use 'suspend' for these, because we have to pump events from a NORMAL thread. If there are any suspend points, there is
    // the potential for a live-lock due to coroutine scheduling
    val info : String

    companion object {
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

        fun uriEndpoint(type: String, sessionId: Int, isReliable: Boolean, address: InetAddress, addressString: String, port: Int): ChannelUriStringBuilder {
            val builder = uri(type, sessionId, isReliable)

            if (address is Inet4Address) {
                builder.endpoint("$addressString:$port")
            } else {
                // IPv6 requires the address to be bracketed by [...]
                if (addressString[0] == '[') {
                    builder.endpoint("$addressString:$port")
                } else {
                    // there MUST be [] surrounding the IPv6 address for aeron to like it!
                    builder.endpoint("[$addressString]:$port")
                }
            }

            return builder
        }
    }
}
