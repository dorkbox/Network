/*
 * Copyright 2020 dorkbox, llc
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

package dorkbox.network.aeron

import dorkbox.netUtil.IP
import dorkbox.netUtil.IPv4
import dorkbox.netUtil.IPv6
import dorkbox.network.exceptions.ClientTimedOutException
import io.aeron.Aeron
import io.aeron.ChannelUriStringBuilder
import io.aeron.Publication
import io.aeron.Subscription
import kotlinx.coroutines.delay
import mu.KLogger
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

interface MediaDriverConnection : AutoCloseable {
    val address: InetAddress?
    val streamId: Int
    val sessionId: Int

    val subscriptionPort: Int
    val publicationPort: Int

    val subscription: Subscription
    val publication: Publication

    val isReliable: Boolean

    suspend fun addSubscriptionWithRetry(aeron: Aeron, uri: String, streamId: Int, logger: KLogger): Subscription {
        // If we start/stop too quickly, we might have the address already in use! Retry a few times.
        var count = 10
        var exception: Exception? = null
        while (count-- > 0) {
            try {
                return aeron.addSubscription(uri, streamId)
            } catch (e: Exception) {
                exception = e
                logger.warn { "Unable to add a publication to Aeron. Retrying $count more times..." }
                delay(5000)
            }
        }

        throw exception!!
    }

    suspend fun addPublicationWithRetry(aeron: Aeron, uri: String, streamId: Int, logger: KLogger): Publication {
        // If we start/stop too quickly, we might have the address already in use! Retry a few times.
        var count = 10
        var exception: Exception? = null
        while (count-- > 0) {
            try {
                return aeron.addPublication(uri, streamId)
            } catch (e: Exception) {
                exception = e
                logger.warn { "Unable to add a publication to Aeron. Retrying $count more times..." }
                delay(5_000)
            }
        }

        throw exception!!
    }

    @Throws(ClientTimedOutException::class)
    suspend fun buildClient(aeron: Aeron, logger: KLogger)
    suspend fun buildServer(aeron: Aeron, logger: KLogger)

    fun clientInfo() : String
    fun serverInfo() : String
}

/**
 * For a client, the ports specified here MUST be manually flipped because they are in the perspective of the SERVER.
 * A connection timeout of 0, means to wait forever
 */
class UdpMediaDriverConnection(override val address: InetAddress,
                               override val publicationPort: Int,
                               override val subscriptionPort: Int,
                               override val streamId: Int,
                               override val sessionId: Int,
                               private val connectionTimeoutMS: Long = 0,
                               override val isReliable: Boolean = true) : MediaDriverConnection {

    override lateinit var subscription: Subscription
    override lateinit var publication: Publication

    var success: Boolean = false

    val addressString: String by lazy {
        if (address is Inet4Address) {
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
        // Create a publication at the given address and port, using the given stream ID.
        // Note: The Aeron.addPublication method will block until the Media Driver acknowledges the request or a timeout occurs.
        val publicationUri = uri()
                .endpoint("$addressString:$publicationPort")

        // Create a subscription with a control port (for dynamic MDC) at the given address and port, using the given stream ID.
        val subscriptionUri = uri()
                .controlEndpoint("$addressString:$subscriptionPort")
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
            if (subscription.isConnected && subscription.imageCount() > 0) {
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

        this.subscription = subscription
        this.publication = publication
    }

    override suspend fun buildServer(aeron: Aeron, logger: KLogger) {
        // Create a publication with a control port (for dynamic MDC) at the given address and port, using the given stream ID.
        // Note: The Aeron.addPublication method will block until the Media Driver acknowledges the request or a timeout occurs.
        val publicationUri = uri()
                .controlEndpoint("$addressString:$publicationPort")
                .controlMode("dynamic")

        // Create a subscription with a control port (for dynamic MDC) at the given address and port, using the given stream ID.
        val subscriptionUri = uri()
                .endpoint("$addressString:$subscriptionPort")



        if (logger.isTraceEnabled) {
            if (address is Inet4Address) {
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
        publication = addPublicationWithRetry(aeron, publicationUri.build(), streamId, logger)
        subscription = addSubscriptionWithRetry(aeron, subscriptionUri.build(), streamId, logger)
    }

    override fun clientInfo(): String {
        return if (sessionId != AeronConfig.RESERVED_SESSION_ID_INVALID) {
            "Connecting to ${IP.toString(address)} [$subscriptionPort|$publicationPort] [$streamId|$sessionId] (reliable:$isReliable)"
        } else {
            "Connecting handshake to ${IP.toString(address)} [$subscriptionPort|$publicationPort] [$streamId|*] (reliable:$isReliable)"
        }
    }

    override fun serverInfo(): String {
        val address = if (address == IPv4.WILDCARD || address == IPv6.WILDCARD) {
            if (address == IPv4.WILDCARD) {
                address.hostAddress
            } else {
                IPv4.WILDCARD.hostAddress + "/" + address.hostAddress
            }
        } else {
            IP.toString(address)
        }

        return if (sessionId != AeronConfig.RESERVED_SESSION_ID_INVALID) {
            "Listening on $address [$subscriptionPort|$publicationPort] [$streamId|$sessionId] (reliable:$isReliable)"
        } else {
            "Listening handshake on $address [$subscriptionPort|$publicationPort] [$streamId|*] (reliable:$isReliable)"
        }
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

/**
 * For a client, the streamId specified here MUST be manually flipped because they are in the perspective of the SERVER
 */
class IpcMediaDriverConnection(override val streamId: Int,
                               val streamIdSubscription: Int,
                               override val sessionId: Int,
                               private val connectionTimeoutMS: Long = 30_000,
                               ) : MediaDriverConnection {

    override val address: InetAddress? = null
    override val isReliable = true
    override val subscriptionPort = 0
    override val publicationPort = 0

    override lateinit var subscription: Subscription
    override lateinit var publication: Publication

    var success: Boolean = false

    private fun uri(): ChannelUriStringBuilder {
        val builder = ChannelUriStringBuilder().media("ipc")
        if (sessionId != AeronConfig.RESERVED_SESSION_ID_INVALID) {
            builder.sessionId(sessionId)
        }

        return builder
    }

    @Throws(ClientTimedOutException::class)
    override suspend fun buildClient(aeron: Aeron, logger: KLogger) {
        // Create a publication at the given address and port, using the given stream ID.
        // Note: The Aeron.addPublication method will block until the Media Driver acknowledges the request or a timeout occurs.
        val publicationUri = uri()

        // Create a subscription with a control port (for dynamic MDC) at the given address and port, using the given stream ID.
        val subscriptionUri = uri()


        if (logger.isTraceEnabled) {
            logger.trace("IPC client pub URI: ${publicationUri.build()}")
            logger.trace("IPC server sub URI: ${subscriptionUri.build()}")
        }

        // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
        //  publication of any state to other threads and not be long running or re-entrant with the client.

        // If we start/stop too quickly, we might have the aeron connectivity issues! Retry a few times.
        val publication = addPublicationWithRetry(aeron, publicationUri.build(), streamId, logger)
        val subscription = addSubscriptionWithRetry(aeron, subscriptionUri.build(), streamIdSubscription, logger)

        var success = false

        // this will wait for the server to acknowledge the connection (all via aeron)
        var startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < connectionTimeoutMS) {
            if (subscription.isConnected && subscription.imageCount() > 0) {
                success = true
                break
            }

            delay(timeMillis = 100L)
        }

        if (!success) {
            subscription.close()
            throw ClientTimedOutException("Creating subscription connection to aeron")
        }


        success = false

        // this will wait for the server to acknowledge the connection (all via aeron)
        startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < connectionTimeoutMS) {
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

    override suspend fun buildServer(aeron: Aeron, logger: KLogger) {
        // Create a publication with a control port (for dynamic MDC) at the given address and port, using the given stream ID.
        // Note: The Aeron.addPublication method will block until the Media Driver acknowledges the request or a timeout occurs.
        val publicationUri = uri()

        // Create a subscription with a control port (for dynamic MDC) at the given address and port, using the given stream ID.
        val subscriptionUri = uri()


        if (logger.isTraceEnabled) {
            logger.trace("IPC server pub URI: ${publicationUri.build()}")
            logger.trace("IPC server sub URI: ${subscriptionUri.build()}")
        }

        // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
        //  publication of any state to other threads and not be long running or re-entrant with the client.

        // on close, the publication CAN linger (in case a client goes away, and then comes back)
        // AERON_PUBLICATION_LINGER_TIMEOUT, 5s by default (this can also be set as a URI param)

        // If we start/stop too quickly, we might have the aeron connectivity issues! Retry a few times.
        publication = addPublicationWithRetry(aeron, publicationUri.build(), streamId, logger)
        subscription = addSubscriptionWithRetry(aeron, subscriptionUri.build(), streamIdSubscription, logger)
    }

    override fun clientInfo() : String {
        return if (sessionId != AeronConfig.RESERVED_SESSION_ID_INVALID) {
            "[$sessionId] IPC connection established to [$streamIdSubscription|$streamId]"
        } else {
            "Connecting handshake to IPC [$streamIdSubscription|$streamId]"
        }
    }

    override fun serverInfo() : String {
        return if (sessionId != AeronConfig.RESERVED_SESSION_ID_INVALID) {
            "[$sessionId] IPC listening on [$streamIdSubscription|$streamId] "
        } else {
            "Listening handshake on IPC [$streamIdSubscription|$streamId]"
        }
    }

    override fun close() {
        if (success) {
            subscription.close()
            publication.close()
        }
    }

    override fun toString(): String {
        return "[$streamIdSubscription|$streamId] [$sessionId]"
    }
}
