package dorkbox.network.connection

import dorkbox.network.aeron.client.ClientTimedOutException
import io.aeron.Aeron
import io.aeron.ChannelUriStringBuilder
import io.aeron.Publication
import io.aeron.Subscription

class IpcMediaDriverConnection(override val address: String,
                               override val subscriptionPort: Int,
                               override val publicationPort: Int,
                               override val streamId: Int,
                               override val sessionId: Int,
                               private val connectionTimeoutMS: Long,
                               override val isReliable: Boolean) : MediaDriverConnection {

    override lateinit var subscription: Subscription
    override lateinit var publication: Publication

    init {

    }

    fun subscriptionURI(): ChannelUriStringBuilder {
        return ChannelUriStringBuilder()
                .media("ipc")
                .controlMode("dynamic")
    }

    // Create a publication at the given address and port, using the given stream ID.
    fun publicationURI(): ChannelUriStringBuilder {
        return ChannelUriStringBuilder()
                .media("ipc")
    }


    @Throws(ClientTimedOutException::class)
    override suspend fun buildClient(aeron: Aeron) {

    }

    override fun buildServer(aeron: Aeron) {

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
