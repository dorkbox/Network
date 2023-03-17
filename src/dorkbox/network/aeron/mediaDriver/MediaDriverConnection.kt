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
import io.aeron.ChannelUriStringBuilder
import mu.KLogger

fun ChannelUriStringBuilder.endpoint(isIpv4: Boolean, addressString: String, port: Int): ChannelUriStringBuilder {
    this.endpoint(MediaDriverConnection.address(isIpv4, addressString, port))
    return this
}
fun ChannelUriStringBuilder.controlEndpoint(isIpv4: Boolean, addressString: String, port: Int): ChannelUriStringBuilder {
    this.controlEndpoint(MediaDriverConnection.address(isIpv4, addressString, port))
    return this
}

// We don't use 'suspend' for these, because we have to pump events from a NORMAL thread. If there are any suspend points, there is
// the potential for a live-lock due to coroutine scheduling
interface MediaDriverConnection {

    val type: String

    fun build(aeronDriver: AeronDriver, logger: KLogger)

    companion object {
        fun uri(type: String, sessionId: Int = AeronDriver.RESERVED_SESSION_ID_INVALID, isReliable: Boolean? = null): ChannelUriStringBuilder {
            val builder = ChannelUriStringBuilder().media(type)
            if (isReliable != null) {
                builder.reliable(isReliable)
            }

            if (sessionId != AeronDriver.RESERVED_SESSION_ID_INVALID) {
                builder.sessionId(sessionId)
            }

            return builder
        }

        fun address(isIpv4: Boolean, addressString: String, port: Int): String {
            return if (isIpv4) {
                "$addressString:$port"
            } else if (addressString[0] == '[') {
                // IPv6 requires the address to be bracketed by [...]
                "$addressString:$port"
            } else {
                // there MUST be [] surrounding the IPv6 address for aeron to like it!
                "[$addressString]:$port"
            }
        }
    }
}
