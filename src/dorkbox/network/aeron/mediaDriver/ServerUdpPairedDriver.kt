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
import dorkbox.network.connection.EndPoint
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
        val isRemoteIpv4 = remoteAddress is Inet4Address

        // if we are connecting to localhost IPv4 (but our server is IPv6+4), then aeron MUST publish on the IPv4 version
        val properPubAddress = EndPoint.getWildcard(listenAddress, listenAddressString, isRemoteIpv4)

        // create a new publication for the connection (since the handshake ALWAYS closes the current publication)
        val publicationUri = MediaDriverConnection.uri("udp", sessionId, isReliable).controlEndpoint(isRemoteIpv4, properPubAddress, port+1)


        if (logger.isTraceEnabled) {
            if (isRemoteIpv4) {
                logger.trace("IPV4 server e-pub URI: ${publicationUri.build()},stream-id=$streamId")
            } else {
                logger.trace("IPV6 server e-pub URI: ${publicationUri.build()},stream-id=$streamId")
            }
        }

        val publication = aeronDriver.addExclusivePublication(publicationUri, streamId)


        // if we are IPv6 WILDCARD -- then our subscription must ALSO be IPv6, even if our connection is via IPv4
        var subShouldBeIpv4 = isRemoteIpv4

        val properSubAddress = if (listenAddress == IPv6.WILDCARD) {
            subShouldBeIpv4 = false
            IPv6.WILDCARD_STRING
        } else {
            // this will cause us to listen on the interface that connects with the remote address, instead of ALL interfaces.
            val localAddresses = publication.localSocketAddresses().first()
            val splitPoint = localAddresses.lastIndexOf(':')
            var localAddressString = localAddresses.substring(0, splitPoint)

            if (!isRemoteIpv4) {
                // this is necessary to clean up the address when adding it to aeron, since different formats mess it up
                // aeron IPv6 addresses all have [...]
                localAddressString = localAddressString.substring(1, localAddressString.length-1)
                localAddressString = IPv6.toString(IPv6.toAddress(localAddressString)!!)
            }

            localAddressString
        }



        // Create a subscription at the given address and port, using the given stream ID.
        val subscriptionUri = MediaDriverConnection.uri("udp", sessionId, isReliable).endpoint(subShouldBeIpv4, properSubAddress, port)


        if (logger.isTraceEnabled) {
            if (isRemoteIpv4) {
                logger.trace("IPV4 server sub URI: ${subscriptionUri.build()},stream-id=$streamId")
            } else {
                logger.trace("IPV6 server sub URI: ${subscriptionUri.build()},stream-id=$streamId")
            }
        }

        val remoteAddressString = if (isRemoteIpv4) {
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
