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

package dorkbox.network.aeron

import dorkbox.netUtil.IP
import dorkbox.netUtil.IPv4
import dorkbox.netUtil.IPv6
import dorkbox.network.exceptions.ServerException
import io.aeron.ChannelUriStringBuilder
import mu.KLogger
import java.net.Inet4Address
import java.net.InetAddress

/**
 * For a client, the ports specified here MUST be manually flipped because they are in the perspective of the SERVER.
 * A connection timeout of 0, means to wait forever
 */
internal open class UdpMediaDriverServerConnection(val listenAddress: InetAddress,
                                                   publicationPort: Int,
                                                   subscriptionPort: Int,
                                                   streamId: Int,
                                                   sessionId: Int,
                                                   connectionTimeoutSec: Int,
                                                   isReliable: Boolean = true) :
    MediaDriverConnection(publicationPort, subscriptionPort, streamId, sessionId, connectionTimeoutSec, isReliable) {

    private fun aeronConnectionString(ipAddress: InetAddress): String {
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

    private fun uri(): ChannelUriStringBuilder {
        val builder = ChannelUriStringBuilder().reliable(isReliable).media("udp")
        if (sessionId != AeronDriver.RESERVED_SESSION_ID_INVALID) {
            builder.sessionId(sessionId)
        }

        return builder
    }

    @Suppress("DuplicatedCode")
    override suspend fun buildClient(aeronDriver: AeronDriver, logger: KLogger) {
        throw ServerException("Client info not implemented in Server MediaDriver Connection")
    }

    override fun buildServer(aeronDriver: AeronDriver, logger: KLogger, pairConnection: Boolean) {
        val connectionString = aeronConnectionString(listenAddress)

        // Create a publication with a control port (for dynamic MDC) at the given address and port, using the given stream ID.
        // Note: The Aeron.addPublication method will block until the Media Driver acknowledges the request or a timeout occurs.
        val publicationUri = uri()
                .controlEndpoint("$connectionString:$publicationPort")

        // Create a subscription with a control port (for dynamic MDC) at the given address and port, using the given stream ID.
        val subscriptionUri = uri()
                .endpoint("$connectionString:$subscriptionPort")



        if (logger.isTraceEnabled) {
            if (listenAddress is Inet4Address) {
                logger.trace("IPV4 server pub URI: ${publicationUri.build()}")
                logger.trace("IPV4 server sub URI: ${subscriptionUri.build()}")
            } else {
                logger.trace("IPV6 server pub URI: ${publicationUri.build()}")
                logger.trace("IPV6 server sub URI: ${subscriptionUri.build()}")
            }
        }

        // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
        //  publication of any state to other threads and not be long running or re-entrant with the client.
        // on close, the publication CAN linger (in case a client goes away, and then comes back)
        // AERON_PUBLICATION_LINGER_TIMEOUT, 5s by default (this can also be set as a URI param)

        // If we start/stop too quickly, we might have the address already in use! Retry a few times.
        this.success = true
        this.publication = aeronDriver.addPublication(publicationUri, streamId)
        this.subscription = aeronDriver.addSubscription(subscriptionUri, streamId)
    }

    override val clientInfo: String
        get() {
            throw ServerException("Client info not implemented in Server MediaDriver Connection")
        }

    override val serverInfo: String by lazy {
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
            "Listening on $address [$subscriptionPort|$publicationPort] [$streamId|$sessionId] (reliable:$isReliable)"
        } else {
            "Listening handshake on $address [$subscriptionPort|$publicationPort] [$streamId|*] (reliable:$isReliable)"
        }
    }

    override fun toString(): String {
        return serverInfo
    }
}
