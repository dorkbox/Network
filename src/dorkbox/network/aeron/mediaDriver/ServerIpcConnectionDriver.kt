/*
 * Copyright 2023 dorkbox, llc
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

import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.mediaDriver.MediaDriverConnection.Companion.uri
import io.aeron.Publication
import io.aeron.Subscription
import mu.KLogger

/**
 * Setup the subscription + publication channels on the server.
 *
 * serverAddress is ignored for IPC
 */
/**
 * For a client, the streamId specified here MUST be manually flipped because they are in the perspective of the SERVER
 * NOTE: IPC connection will ALWAYS have a timeout of 10 second to connect. This is IPC, it should connect fast
 */
internal open class ServerIpcConnectionDriver(
    val aeronDriver: AeronDriver,
    val sessionIdPub: Int,
    val sessionIdSub: Int,
    val streamIdPub: Int,
    val streamIdSub: Int,
    val logInfo: String,
    val isReliable: Boolean,
    val logger: KLogger
) {


    lateinit var publication: Publication
    lateinit var subscription: Subscription

    val infoPub: String
    val infoSub: String

    init {
        buildIPC(sessionIdPub, sessionIdSub, streamIdPub, streamIdSub, isReliable)


        infoPub = "[${sessionIdPub}] IPC listening on [$streamIdPub]"
        infoSub = "[${sessionIdSub}] IPC listening on [$streamIdSub]"

        logger.error { "\n" +
                "SERVER PUB: stream: $streamIdPub session: $sessionIdPub \n"+
                "SERVER SUB: stream: $streamIdSub session: $sessionIdSub \n" }
    }

     private fun buildIPC(sessionIdPub: Int, sessionIdSub: Int, streamIdPub: Int, streamIdSub: Int, isReliable: Boolean) {
        // Create a subscription at the given address and port, using the given stream ID.
        val subscriptionUri = uri("ipc", sessionIdSub, isReliable)

        // create a new publication for the connection (since the handshake ALWAYS closes the current publication)
        val publicationUri = uri("ipc", sessionIdPub, isReliable)

        this.publication = aeronDriver.addPublication(publicationUri, logInfo, streamIdPub)
        this.subscription = aeronDriver.addSubscription(subscriptionUri, logInfo, streamIdSub)
    }

    fun connectionInfo(): MediaDriverConnectInfo {
        logger.info { "[$logInfo] Creating new IPC connection from $infoPub" }

       return MediaDriverConnectInfo(
           publication = publication,
           subscription = subscription,
           sessionIdPub = sessionIdSub,
           streamIdPub = streamIdPub,
           streamIdSub = streamIdSub,
           isReliable = isReliable,
           remoteAddress = null,
           remoteAddressString = "ipc"
        )

//
//        return if (isUsingIPC) {
//            logger.info { "Creating new IPC connection to $info" }
//
//            MediaDriverConnectInfo(
//                    subscription = subscription,
//                    publication = publication,
//                    subscriptionPort = sessionId,
//                    publicationPort = streamId,
//                    streamId = 0, // this is because with IPC, we have stream sub/pub (which are replaced as port sub/pub)
//                    sessionId = sessionId,
//                    isReliable = handshakeConnection.reliable,
//                    remoteAddress = null,
//                    remoteAddressString = "ipc"
//            )
//        } else {
//            logger.info { "Creating new connection to $info" }
//
//            MediaDriverConnectInfo(
//                    subscription = subscription,
//                    publication = publication,
//                    subscriptionPort = subscriptionPort,
//                    publicationPort = port,
//                    streamId = streamId,
//                    sessionId = sessionId,
//                    isReliable = handshakeConnection.reliable,
//                    remoteAddress = handshakeConnection.remoteAddress,
//                    remoteAddressString = handshakeConnection.remoteAddressString
//            )
//        }
    }

    override fun toString(): String {
        return infoPub
    }
}
