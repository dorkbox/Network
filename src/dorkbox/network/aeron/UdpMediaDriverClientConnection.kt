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
import dorkbox.network.connection.ListenerManager
import dorkbox.network.exceptions.ClientException
import dorkbox.network.exceptions.ClientTimedOutException
import io.aeron.ChannelUriStringBuilder
import mu.KLogger
import java.lang.Thread.sleep
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
    UdpMediaDriverConnection(publicationPort, subscriptionPort, streamId, sessionId, connectionTimeoutMS, isReliable) {

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
        if (sessionId != AeronDriver.RESERVED_SESSION_ID_INVALID) {
            builder.sessionId(sessionId)
        }

        return builder
    }

    val ip: String by lazy {
        if (address is Inet4Address) {
            "IPv4"
        } else {
            "IPv6"
        }
    }


    @Suppress("DuplicatedCode")
    override fun buildClient(aeronDriver: AeronDriver, logger: KLogger) {
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
            logger.trace("client pub URI: $ip ${publicationUri.build()}")
            logger.trace("client sub URI: $ip ${subscriptionUri.build()}")
        }

        var success = false

        // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
        //  publication of any state to other threads and not be long running or re-entrant with the client.
        // on close, the publication CAN linger (in case a client goes away, and then comes back)
        // AERON_PUBLICATION_LINGER_TIMEOUT, 5s by default (this can also be set as a URI param)
        val publication = aeronDriver.addPublicationWithRetry(publicationUri, streamId)
        val subscription = aeronDriver.addSubscriptionWithRetry(subscriptionUri, streamId)


        // this will wait for the server to acknowledge the connection (all via aeron)
        val timoutInNanos = TimeUnit.MILLISECONDS.toNanos(connectionTimeoutMS)
        var startTime = System.nanoTime()
        while (timoutInNanos == 0L || System.nanoTime() - startTime < timoutInNanos) {
            if (subscription.isConnected) {
                success = true
                break
            }

            sleep(100L)
        }

        if (!success) {
            subscription.close()
            val ex = ClientTimedOutException("Cannot create subscription: $ip ${subscriptionUri.build()} in ${timoutInNanos}ms")
            ListenerManager.cleanStackTrace(ex)
            throw ex
        }


        success = false

        // this will wait for the server to acknowledge the connection (all via aeron)
        startTime = System.nanoTime()
        while (timoutInNanos == 0L || System.nanoTime() - startTime < timoutInNanos) {
            if (publication.isConnected) {
                success = true
                break
            }

            sleep(100L)
        }

        if (!success) {
            subscription.close()
            publication.close()
            val ex = ClientTimedOutException("Cannot create publication: $ip ${publicationUri.build()} in ${timoutInNanos}ms")
            ListenerManager.cleanStackTrace(ex)
            throw ex
        }

        this.success = true
        this.publication = publication
        this.subscription = subscription
    }

    override fun clientInfo(): String {
        return if (sessionId != AeronDriver.RESERVED_SESSION_ID_INVALID) {
            "Connecting to $addressString [$subscriptionPort|$publicationPort] [$streamId|$sessionId] (reliable:$isReliable)"
        } else {
            "Connecting handshake to $addressString [$subscriptionPort|$publicationPort] [$streamId|*] (reliable:$isReliable)"
        }
    }

    override fun buildServer(aeronDriver: AeronDriver, logger: KLogger, pairConnection: Boolean) {
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
