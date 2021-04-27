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
import dorkbox.network.exceptions.ClientException
import dorkbox.network.exceptions.ClientTimedOutException
import io.aeron.Aeron
import io.aeron.ChannelUriStringBuilder
import kotlinx.coroutines.delay
import mu.KLogger
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * For a client, the ports specified here MUST be manually flipped because they are in the perspective of the SERVER.
 * A connection timeout of 0, means to wait forever
 */
internal class UdpMediaDriverClientConnection(val address: InetAddress,
                                              publicationPort: Int,
                                              subscriptionPort: Int,
                                              streamId: Int,
                                              sessionId: Int,
                                              connectionTimeoutMS: Long = 0,
                                              isReliable: Boolean = true) :
        MediaDriverConnection(publicationPort, subscriptionPort, streamId, sessionId, connectionTimeoutMS, isReliable) {

    var success: Boolean = false

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

    val addressString: String by lazy {
        IP.toString(address)
    }

    private fun uri(): ChannelUriStringBuilder {
        val builder = ChannelUriStringBuilder().reliable(isReliable).media("udp")
        if (sessionId != AeronConfig.RESERVED_SESSION_ID_INVALID) {
            builder.sessionId(sessionId)
        }

        return builder
    }

    @Suppress("DuplicatedCode")
    override suspend fun buildClient(aeron: Aeron, logger: KLogger) {
        val aeronAddressString = aeronConnectionString(address)

        // Create a publication at the given address and port, using the given stream ID.
        // Note: The Aeron.addPublication method will block until the Media Driver acknowledges the request or a timeout occurs.
        val publicationUri = uri()
                .endpoint("$aeronAddressString:$publicationPort")

        // Create a subscription with a control port (for dynamic MDC) at the given address and port, using the given stream ID.
        val subscriptionUri = uri()
                .controlEndpoint("$aeronAddressString:$subscriptionPort")
                .controlMode("dynamic")



        if (logger.isTraceEnabled) {
            if (address is Inet4Address) {
                logger.trace("IPV4 client pub URI: ${publicationUri.build()}")
                logger.trace("IPV4 client sub URI: ${subscriptionUri.build()}")
            } else {
                logger.trace("IPV6 client pub URI: ${publicationUri.build()}")
                logger.trace("IPV6 client sub URI: ${subscriptionUri.build()}")
            }
        }

        // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
        //  publication of any state to other threads and not be long running or re-entrant with the client.
        // on close, the publication CAN linger (in case a client goes away, and then comes back)
        // AERON_PUBLICATION_LINGER_TIMEOUT, 5s by default (this can also be set as a URI param)
        val publication = addPublicationWithRetry(aeron, publicationUri.build(), streamId, logger)
        val subscription = addSubscriptionWithRetry(aeron, subscriptionUri.build(), streamId, logger)

        var success = false

        // this will wait for the server to acknowledge the connection (all via aeron)
        val timoutInNanos = TimeUnit.MILLISECONDS.toNanos(connectionTimeoutMS)
        var startTime = System.nanoTime()
        while (timoutInNanos == 0L || System.nanoTime() - startTime < timoutInNanos) {
            if (subscription.isConnected) {
                success = true
                break
            }

            delay(timeMillis = 100L)
        }

        if (!success) {
            subscription.close()
            throw ClientTimedOutException("Cannot create subscription!")
        }


        success = false

        // this will wait for the server to acknowledge the connection (all via aeron)
        startTime = System.nanoTime()
        while (timoutInNanos == 0L || System.nanoTime() - startTime < timoutInNanos) {
            if (publication.isConnected) {
                success = true
                break
            }

            delay(timeMillis = 100L)
        }

        if (!success) {
            subscription.close()
            publication.close()
            throw ClientTimedOutException("Creating publication connection to aeron")
        }

        this.success = true

        this.publication = publication
        this.subscription = subscription
    }

    override fun clientInfo(): String {
        address

        return if (sessionId != AeronConfig.RESERVED_SESSION_ID_INVALID) {
            "Connecting to ${IP.toString(address)} [$subscriptionPort|$publicationPort] [$streamId|$sessionId] (reliable:$isReliable)"
        } else {
            "Connecting handshake to ${IP.toString(address)} [$subscriptionPort|$publicationPort] [$streamId|*] (reliable:$isReliable)"
        }
    }

    override suspend fun buildServer(aeron: Aeron, logger: KLogger) {
        throw ClientException("Server info not implemented in Client MDC")
    }
    override fun serverInfo(): String {
        throw ClientException("Server info not implemented in Client MDC")
    }

    override fun close() {
        if (success) {
            subscription.close()
            publication.close()
        }
    }

    override fun toString(): String {
        return "$addressString [$subscriptionPort|$publicationPort] [$streamId|$sessionId] (reliable:$isReliable)"
    }
}
