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
import dorkbox.network.aeron.mediaDriver.MediaDriverConnection.Companion.uri
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
    MediaDriverServer(
        port = port,
        streamId = streamId,
        sessionId = sessionId,
        connectionTimeoutSec = connectionTimeoutSec,
        isReliable = isReliable
    ) {


    var success: Boolean = false
    override val type = "udp"

    private val isListenIpv4 = listenAddress is Inet4Address
    protected val listenAddressString = IP.toString(listenAddress)

    protected val prettyAddressString = when (listenAddress) {
        IPv4.WILDCARD -> listenAddressString
        IPv6.WILDCARD -> IPv4.WILDCARD.hostAddress + "/" + listenAddressString
        else -> listenAddressString
    }

    override fun build(aeronDriver: AeronDriver, logger: KLogger) {

        // Create a subscription at the given address and port, using the given stream ID.
        val subscriptionUri = uri("udp", sessionId, isReliable).endpoint(isListenIpv4, listenAddressString, port)

        if (logger.isTraceEnabled) {
            if (isListenIpv4) {
                logger.trace("IPV4 server sub URI: ${subscriptionUri.build()},stream-id=$streamId")
            } else {
                logger.trace("IPV6 server sub URI: ${subscriptionUri.build()},stream-id=$streamId")
            }
        }

        this.info = if (sessionId != AeronDriver.RESERVED_SESSION_ID_INVALID) {
            "Listening on $prettyAddressString [$port|${port+1}] [$streamId|$sessionId] (reliable:$isReliable)"
        } else {
            "Listening handshake on $prettyAddressString [$port|${port+1}] [$streamId|*] (reliable:$isReliable)"
        }

        this.success = true
        this.subscription = aeronDriver.addSubscription(subscriptionUri, streamId)
    }
}
