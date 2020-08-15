package dorkbox.network.connection

import dorkbox.network.aeron.client.ClientException
import dorkbox.network.aeron.client.ClientTimedOutException
import dorkbox.network.aeron.server.ServerException
import io.aeron.Aeron
import io.aeron.ChannelUriStringBuilder
import io.aeron.Publication
import io.aeron.Subscription
import kotlinx.coroutines.delay

interface MediaDriverConnection : AutoCloseable {
    val address: String
    val streamId: Int
    val sessionId: Int

    val subscriptionPort: Int
    val publicationPort: Int

    val subscription: Subscription
    val publication: Publication

    val isReliable: Boolean

    @Throws(ClientTimedOutException::class)
    suspend fun buildClient(aeron: Aeron)

    fun buildServer(aeron: Aeron)

    fun clientInfo() : String
    fun serverInfo() : String
}

/**
 * For a client, the ports specified here MUST be manually flipped because they are in the perspective of the SERVER
 */
class UdpMediaDriverConnection(override val address: String,
                               override val publicationPort: Int,
                               override val subscriptionPort: Int,
                               override val streamId: Int,
                               override val sessionId: Int,
                               private val connectionTimeoutMS: Long = 0,
                               override val isReliable: Boolean = true) : MediaDriverConnection {

    override lateinit var subscription: Subscription
    override lateinit var publication: Publication

    var success: Boolean = false


    private fun uri(): ChannelUriStringBuilder {
        val builder = ChannelUriStringBuilder().reliable(isReliable).media("udp")
        if (sessionId != EndPoint.RESERVED_SESSION_ID_INVALID) {
            builder.sessionId(sessionId)
        }

        return builder
    }

    override suspend fun buildClient(aeron: Aeron) {
        if (address.isEmpty()) {
            throw ClientException("Invalid address : '$address'")
        }

        // Create a subscription with a control port (for dynamic MDC) at the given address and port, using the given stream ID.
        val subscriptionUri = uri()
                .controlEndpoint("$address:$subscriptionPort")
                .controlMode("dynamic")


        // Create a publication at the given address and port, using the given stream ID.
        // Note: The Aeron.addPublication method will block until the Media Driver acknowledges the request or a timeout occurs.
        val publicationUri = uri()
                .endpoint("$address:$publicationPort")


        // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
        //  publication of any state to other threads and not be long running or re-entrant with the client.
        val subscription =  aeron.addSubscription(subscriptionUri.build(), streamId)
        val publication = aeron.addPublication(publicationUri.build(), streamId)

        var success = false

        // this will wait for the server to acknowledge the connection (all via aeron)
        var startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < connectionTimeoutMS) {
            if (subscription.isConnected && subscription.imageCount() > 0) {
                success = true
                break
            }

            delay(timeMillis = 10L)
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

            delay(timeMillis = 10L)
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

    override fun buildServer(aeron: Aeron) {
        if (address.isEmpty()) {
            throw ServerException("Invalid address. It is empty!")
        }

        // Create a subscription with a control port (for dynamic MDC) at the given address and port, using the given stream ID.
        val subscriptionUri = uri()
                .endpoint("$address:$subscriptionPort")


        // Create a publication with a control port (for dynamic MDC) at the given address and port, using the given stream ID.
        // Note: The Aeron.addPublication method will block until the Media Driver acknowledges the request or a timeout occurs.
        val publicationUri = uri()
                .controlEndpoint("$address:$publicationPort")
                .controlMode("dynamic")


        // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
        //  publication of any state to other threads and not be long running or re-entrant with the client.
        subscription = aeron.addSubscription(subscriptionUri.build(), streamId)
        publication = aeron.addPublication(publicationUri.build(), streamId)
    }


    override fun clientInfo(): String {
        return if (sessionId != EndPoint.RESERVED_SESSION_ID_INVALID) {
            "Connecting to $address [$subscriptionPort|$publicationPort] [$streamId|$sessionId] (reliable:$isReliable)"
        } else {
            "Connecting to $address [$subscriptionPort|$publicationPort] [$streamId|*] (reliable:$isReliable)"
        }
    }

    override fun serverInfo(): String {
        return if (sessionId != EndPoint.RESERVED_SESSION_ID_INVALID) {
            "Listening on $address [$subscriptionPort|$publicationPort] [$streamId|$sessionId] (reliable:$isReliable)"
        } else {
            "Listening on $address [$subscriptionPort|$publicationPort] [$streamId|*] (reliable:$isReliable)"
        }
    }

    override fun close() {
        if (success) {
            subscription.close()
            publication.close()
        }
    }

    override fun toString(): String {
        return "$address [$subscriptionPort|$publicationPort] [$streamId|$sessionId] (reliable:$isReliable)"
    }
}

/**
 * For a client, the streamId specified here MUST be manually flipped because they are in the perspective of the SERVER
 */
class IpcMediaDriverConnection(override val streamId: Int,
                               val streamIdSubscription: Int,
                               override val sessionId: Int,
                               private val connectionTimeoutMS: Long = 30_000,
                               override val isReliable: Boolean = true) : MediaDriverConnection {

    override val address = ""
    override val subscriptionPort = 0
    override val publicationPort = 0

    override lateinit var subscription: Subscription
    override lateinit var publication: Publication

    var success: Boolean = false


    init {
    }

    private fun uri(): ChannelUriStringBuilder {
        val builder = ChannelUriStringBuilder().media("ipc")
        if (sessionId != EndPoint.RESERVED_SESSION_ID_INVALID) {
            builder.sessionId(sessionId)
        }

        return builder
    }

    @Throws(ClientTimedOutException::class)
    override suspend fun buildClient(aeron: Aeron) {
        // Create a subscription with a control port (for dynamic MDC) at the given address and port, using the given stream ID.
        val subscriptionUri = uri()
//                .controlEndpoint("$address:$subscriptionPort")
//                .controlMode("dynamic")


        // Create a publication at the given address and port, using the given stream ID.
        // Note: The Aeron.addPublication method will block until the Media Driver acknowledges the request or a timeout occurs.
        val publicationUri = uri()
//                .endpoint("$address:$publicationPort")


        // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
        //  publication of any state to other threads and not be long running or re-entrant with the client.
        val subscription =  aeron.addSubscription(subscriptionUri.build(), streamIdSubscription)
        val publication = aeron.addPublication(publicationUri.build(), streamId)

        var success = false

        // this will wait for the server to acknowledge the connection (all via aeron)
        var startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < connectionTimeoutMS) {
            if (subscription.isConnected && subscription.imageCount() > 0) {
                success = true
                break
            }

            delay(timeMillis = 10L)
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

            delay(timeMillis = 10L)
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

    override fun buildServer(aeron: Aeron) {
        // Create a subscription with a control port (for dynamic MDC) at the given address and port, using the given stream ID.
        val subscriptionUri = uri()
//                .endpoint("$address:$subscriptionPort")


        // Create a publication with a control port (for dynamic MDC) at the given address and port, using the given stream ID.
        // Note: The Aeron.addPublication method will block until the Media Driver acknowledges the request or a timeout occurs.
        val publicationUri = uri()
//                .controlEndpoint("$address:$publicationPort")
//                .controlMode("dynamic")


        // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
        //  publication of any state to other threads and not be long running or re-entrant with the client.
        subscription = aeron.addSubscription(subscriptionUri.build(), streamIdSubscription)
        publication = aeron.addPublication(publicationUri.build(), streamId)
    }

    override fun clientInfo() : String {
        return ""
    }

    override fun serverInfo() : String {
        return ""
    }

    fun connect() : Pair<String, String> {
        return Pair("","")
    }

    override fun close() {

    }

    override fun toString(): String {
        return "$address [$subscriptionPort|$publicationPort] [$streamId|$sessionId]"
    }
}
