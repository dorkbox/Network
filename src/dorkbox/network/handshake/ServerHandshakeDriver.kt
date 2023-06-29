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

package dorkbox.network.handshake

import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.AeronDriver.Companion.uriHandshake
import dorkbox.network.connection.IpInfo
import io.aeron.ChannelUriStringBuilder
import io.aeron.CommonContext
import io.aeron.Subscription

/**
 * For a client, the ports specified here MUST be manually flipped because they are in the perspective of the SERVER.
 * A connection timeout of 0, means to wait forever
 */
internal class ServerHandshakeDriver(
    private val aeronDriver: AeronDriver,
    val subscription: Subscription,
    val info: String,
    private val logInfo: String)
{
    companion object {
        suspend fun build(
            aeronDriver: AeronDriver,
            isIpc: Boolean,
            ipInfo: IpInfo,
            port: Int,
            streamIdSub: Int, sessionIdSub: Int,
            logInfo: String
        ): ServerHandshakeDriver {

            val info: String
            val subscriptionUri: ChannelUriStringBuilder

            if (isIpc) {
                subscriptionUri = uriHandshake(CommonContext.IPC_MEDIA, true)
                info = "$logInfo [$sessionIdSub|$streamIdSub]"
            } else {
                // are we ipv4 or ipv6 or ipv6wildcard?
                subscriptionUri = uriHandshake(CommonContext.UDP_MEDIA, ipInfo.isReliable)
                    .endpoint(ipInfo.getAeronPubAddress(ipInfo.isIpv4) + ":" + port)

                info = "$logInfo ${ipInfo.listenAddressStringPretty}:$port [$sessionIdSub|$streamIdSub] (reliable:${ipInfo.isReliable})"
            }

            val subscription = aeronDriver.addSubscription(subscriptionUri, streamIdSub, logInfo)
            return ServerHandshakeDriver(aeronDriver, subscription, info, logInfo)
        }
    }

    suspend fun close() {
        aeronDriver.closeAndDeleteSubscription(subscription, logInfo)
    }

    override fun toString(): String {
        return info
    }
}
