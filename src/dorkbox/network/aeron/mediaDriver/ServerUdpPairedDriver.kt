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

import dorkbox.netUtil.IPv4
import dorkbox.netUtil.IPv6
import dorkbox.network.aeron.AeronDriver
import io.aeron.Publication
import mu.KLogger
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * This represents the connection PAIR between a server<->client
 * A connection timeout of 0, means to wait forever
 */
internal class ServerUdpPairedDriver(
    listenAddress: InetAddress,
    val remoteAddress: InetAddress,
    port: Int,
    streamId: Int,
    sessionId: Int,
    connectionTimeoutSec: Int,
    isReliable: Boolean
) :
    ServerUdpDriver(
        listenAddress = listenAddress,
        port = port,
        streamId = streamId,
        sessionId = sessionId,
        connectionTimeoutSec = connectionTimeoutSec,
        isReliable = isReliable
    ) {

    lateinit var publication: Publication

    override fun build(aeronDriver: AeronDriver, logger: KLogger) {
        // connection timeout of 0 doesn't matter. it is not used by the server
        // the client address WILL BE either IPv4 or IPv6
        val isIpV4 = remoteAddress is Inet4Address

        // create a new publication for the connection (since the handshake ALWAYS closes the current publication)
        val publicationUri = MediaDriverConnection.uri("udp", sessionId, isReliable).controlEndpoint(isIpV4, listenAddressString, port+1)


        if (logger.isTraceEnabled) {
            if (isIpV4) {
                logger.trace("IPV4 server pub URI: ${publicationUri.build()},stream-id=$streamId")
            } else {
                logger.trace("IPV6 server pub URI: ${publicationUri.build()},stream-id=$streamId")
            }
        }

        val publication = aeronDriver.addExclusivePublication(publicationUri, streamId)


        // Create a subscription at the given address and port, using the given stream ID.
        val subscriptionUri = MediaDriverConnection.uri("udp", sessionId, isReliable).endpoint(isIpV4, listenAddressString, port)


        if (logger.isTraceEnabled) {
            if (isIpV4) {
                logger.trace("IPV4 server sub URI: ${subscriptionUri.build()},stream-id=$streamId")
            } else {
                logger.trace("IPV6 server sub URI: ${subscriptionUri.build()},stream-id=$streamId")
            }
        }

        val remoteAddressString = if (isIpV4) {
            IPv4.toString(remoteAddress as Inet4Address)
        } else {
            IPv6.toString(remoteAddress as Inet6Address)
        }

        this.info = "$remoteAddressString via $prettyAddressString [$port|${port+1}] [$streamId|$sessionId] (reliable:$isReliable)"

        this.success = true
        this.publication = publication
        this.subscription = aeronDriver.addSubscription(subscriptionUri, streamId)
    }
}
