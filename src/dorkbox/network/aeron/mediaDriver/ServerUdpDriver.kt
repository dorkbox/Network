/*
 * Copyright 2021 dorkbox, llc
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

package dorkbox.network.aeron.mediaDriver

import dorkbox.netUtil.IP
import dorkbox.netUtil.IPv4
import dorkbox.netUtil.IPv6
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.mediaDriver.MediaDriverConnection.Companion.uriEndpoint
import mu.KLogger
import java.net.Inet4Address
import java.net.InetAddress

/**
 * For a client, the ports specified here MUST be manually flipped because they are in the perspective of the SERVER.
 * A connection timeout of 0, means to wait forever
 */
internal open class ServerUdpDriver(val listenAddress: InetAddress,
                                    port: Int,
                                    streamId: Int,
                                    sessionId: Int,
                                    connectionTimeoutSec: Int,
                                    isReliable: Boolean) :
    MediaDriverServer(port, streamId, sessionId, connectionTimeoutSec, isReliable) {


    var success: Boolean = false
    override val type = "udp"

    fun build(aeronDriver: AeronDriver, logger: KLogger) {
        // Create a subscription at the given address and port, using the given stream ID.
        val subscriptionUri = uriEndpoint("udp", sessionId, isReliable, listenAddress, IP.toString(listenAddress), port)

        if (logger.isTraceEnabled) {
            if (listenAddress is Inet4Address) {
                logger.trace("IPV4 server sub URI: ${subscriptionUri.build()}")
            } else {
                logger.trace("IPV6 server sub URI: ${subscriptionUri.build()}")
            }
        }

        this.success = true
        this.subscription = aeronDriver.addSubscription(subscriptionUri, streamId)
    }

    override val info: String by lazy {
        val address = if (listenAddress == IPv4.WILDCARD || listenAddress == IPv6.WILDCARD) {
            if (listenAddress == IPv4.WILDCARD) {
                listenAddress.hostAddress
            } else {
                IPv4.WILDCARD.hostAddress + "/" + listenAddress.hostAddress
            }
        } else {
            IP.toString(listenAddress)
        }

        if (sessionId != AeronDriver.RESERVED_SESSION_ID_INVALID) {
            "Listening on $address [$port] [$streamId|$sessionId] (reliable:$isReliable)"
        } else {
            "Listening handshake on $address [$port] [$streamId|*] (reliable:$isReliable)"
        }
    }

    override fun toString(): String {
        return info
    }
}
