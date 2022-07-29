package dorkbox.network.aeron

import io.aeron.Publication
import io.aeron.Subscription
import java.net.InetAddress

data class MediaDriverConnectInfo(val subscription: Subscription,
                                  val publication: Publication,
                                  val subscriptionPort: Int,
                                  val publicationPort: Int,
                                  val streamId: Int,
                                  val sessionId: Int,
                                  val isReliable: Boolean,
                                  val remoteAddress: InetAddress?,
                                  val remoteAddressString: String,
)
