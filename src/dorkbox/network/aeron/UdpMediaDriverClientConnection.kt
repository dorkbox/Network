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
import io.aeron.Publication
import io.aeron.Subscription
import kotlinx.coroutines.delay
import mu.KLogger
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.*

/**
 * For a client, the ports specified here MUST be manually flipped because they are in the perspective of the SERVER.
 * A connection timeout of 0, means to wait forever
 */
internal class UdpMediaDriverClientConnection(val address: InetAddress,
                                              publicationPort: Int,
                                              subscriptionPort: Int,
                                              streamId: Int,
                                              sessionId: Int,
                                              connectionTimeoutSec: Int = 0,
                                              isReliable: Boolean = true) :
    MediaDriverConnection(publicationPort, subscriptionPort, streamId, sessionId, connectionTimeoutSec, isReliable) {

    @Volatile
    private var tempSubscription: Subscription? = null

    @Volatile
    private var tempPublication: Publication? = null

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
    override suspend fun buildClient(aeronDriver: AeronDriver, logger: KLogger) {
        val aeronAddressString = if (address is Inet4Address) {
            address.hostAddress
        } else {
            // IPv6 requires the address to be bracketed by [...]
            val host = address.hostAddress
            if (host[0] == '[') {
                host
            } else {
                "[${address.hostAddress}]"
            }
        }

        var success = false

        // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
        //  publication of any state to other threads and not be long running or re-entrant with the client.
        // on close, the publication CAN linger (in case a client goes away, and then comes back)
        // AERON_PUBLICATION_LINGER_TIMEOUT, 5s by default (this can also be set as a URI param)

        if (tempPublication == null) {
            // Create a publication at the given address and port, using the given stream ID.
            // Note: The Aeron.addPublication method will block until the Media Driver acknowledges the request or a timeout occurs.
            val publicationUri = uri()
                .endpoint("$aeronAddressString:$publicationPort")

            logger.trace("client pub URI: $ip ${publicationUri.build()}")

            tempPublication = aeronDriver.addPublicationWithRetry(publicationUri, streamId)
        }

        if (tempSubscription == null) {
            // Create a subscription with a control port (for dynamic MDC) at the given address and port, using the given stream ID.
            val subscriptionUri = uri()
                .controlEndpoint("$aeronAddressString:$subscriptionPort")
                .controlMode("dynamic")

                logger.trace("client sub URI: $ip ${subscriptionUri.build()}")

            tempSubscription = aeronDriver.addSubscriptionWithRetry(subscriptionUri, streamId)
        }

        val publication = tempPublication!!
        val subscription = tempSubscription!!


        // this will wait for the server to acknowledge the connection (all via aeron)
        val timoutInNanos = TimeUnit.SECONDS.toNanos(connectionTimeoutSec.toLong())
        var startTime = System.nanoTime()
        while (System.nanoTime() - startTime < timoutInNanos) {
            if (subscription.isConnected) {
                success = true
                break
            }

            delay(500L)
        }

        if (!success) {
            subscription.close()

            val ex = ClientTimedOutException("Cannot create subscription to $ip in $connectionTimeoutSec seconds")
            ListenerManager.cleanAllStackTrace(ex)
            throw ex
        }


        success = false

        // this will wait for the server to acknowledge the connection (all via aeron)
        startTime = System.nanoTime()
        while (System.nanoTime() - startTime < timoutInNanos) {
            if (publication.isConnected) {
                success = true
                break
            }

            delay(500L)
        }

        if (!success) {
            subscription.close()
            publication.close()

            val ex = ClientTimedOutException("Cannot create publication to $ip in $connectionTimeoutSec seconds")
            ListenerManager.cleanAllStackTrace(ex)
            throw ex
        }

        this.success = true
        this.publication = publication
        this.subscription = subscription

        this.tempPublication = null
        this.tempSubscription = null
    }

    override val clientInfo: String by lazy {
        if (sessionId != AeronDriver.RESERVED_SESSION_ID_INVALID) {
            "Connecting to $addressString [$subscriptionPort|$publicationPort] [$streamId|$sessionId] (reliable:$isReliable)"
        } else {
            "Connecting handshake to $addressString [$subscriptionPort|$publicationPort] [$streamId|*] (reliable:$isReliable)"
        }
    }

    override fun buildServer(aeronDriver: AeronDriver, logger: KLogger, pairConnection: Boolean) {
        throw ClientException("Server info not implemented in Client MediaDriver Connection")
    }
    override val serverInfo: String
    get() {
        throw ClientException("Server info not implemented in Client MediaDriver Connection")
    }

    override fun toString(): String {
        return clientInfo
    }
}
