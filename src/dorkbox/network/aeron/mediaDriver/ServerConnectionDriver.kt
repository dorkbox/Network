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
import dorkbox.network.aeron.AeronDriver.Companion.uri
import dorkbox.network.connection.IpInfo
import io.aeron.CommonContext
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Set up the subscription + publication channels back to the client
 *
 * Note: this class is NOT closed the traditional way! It's pub/sub objects are used by the connection (which is where they are closed)
 *
 * This represents the connection PAIR between a server<->client
 */
internal class ServerConnectionDriver(val pubSub: PubSub) {
    companion object {
        suspend fun build(isIpc: Boolean,
                  aeronDriver: AeronDriver,
                  sessionIdPub: Int, sessionIdSub: Int,
                  streamIdPub: Int, streamIdSub: Int,

                  ipInfo: IpInfo,
                  remoteAddress: InetAddress?,
                  remoteAddressString: String,
                  portPub: Int, portSub: Int,
                  reliable: Boolean,
                  logInfo: String): ServerConnectionDriver {

            val pubSub: PubSub

            if (isIpc) {
                pubSub = buildIPC(
                        aeronDriver = aeronDriver,
                        sessionIdPub = sessionIdPub,
                        sessionIdSub = sessionIdSub,
                        streamIdPub = streamIdPub,
                        streamIdSub = streamIdSub,
                        reliable = reliable,
                        logInfo = logInfo
                    )
            } else {
                pubSub = buildUdp(
                    aeronDriver = aeronDriver,
                    ipInfo = ipInfo,
                    sessionIdPub = sessionIdPub,
                    sessionIdSub = sessionIdSub,
                    streamIdPub = streamIdPub,
                    streamIdSub = streamIdSub,
                    remoteAddress = remoteAddress!!,
                    remoteAddressString = remoteAddressString,
                    portPub = portPub,
                    portSub = portSub,
                    reliable = reliable,
                    logInfo = logInfo
                )
            }

            return ServerConnectionDriver(pubSub)
        }

        private suspend fun buildIPC(
            aeronDriver: AeronDriver,
            sessionIdPub: Int, sessionIdSub: Int,
            streamIdPub: Int, streamIdSub: Int,
            reliable: Boolean,
            logInfo: String
        ): PubSub {
            // on close, the publication CAN linger (in case a client goes away, and then comes back)
            // AERON_PUBLICATION_LINGER_TIMEOUT, 5s by default (this can also be set as a URI param)

            // create a new publication for the connection (since the handshake ALWAYS closes the current publication)
            val publicationUri = uri(CommonContext.IPC_MEDIA, sessionIdPub, reliable)

            // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
            //  publication of any state to other threads and not be long running or re-entrant with the client.
            val publication = aeronDriver.addExclusivePublication(publicationUri, streamIdPub, logInfo)

            // Create a subscription at the given address and port, using the given stream ID.
            val subscriptionUri = uri(CommonContext.IPC_MEDIA, sessionIdSub, reliable)
            val subscription = aeronDriver.addSubscription(subscriptionUri, streamIdSub, logInfo)

            return PubSub(publication, subscription,
                          sessionIdPub, sessionIdSub,
                          streamIdPub, streamIdSub,
                          reliable)
        }

        private suspend fun buildUdp(
            aeronDriver: AeronDriver,
            ipInfo: IpInfo,
            sessionIdPub: Int, sessionIdSub: Int,
            streamIdPub: Int, streamIdSub: Int,
            remoteAddress: InetAddress, remoteAddressString: String,
            portPub: Int, portSub: Int,
            reliable: Boolean,
            logInfo: String
        ): PubSub {
            // on close, the publication CAN linger (in case a client goes away, and then comes back)
            // AERON_PUBLICATION_LINGER_TIMEOUT, 5s by default (this can also be set as a URI param)

            // connection timeout of 0 doesn't matter. it is not used by the server
            // the client address WILL BE either IPv4 or IPv6
            val isRemoteIpv4 = remoteAddress is Inet4Address

            // create a new publication for the connection (since the handshake ALWAYS closes the current publication)
            val publicationUri = uri("udp", sessionIdPub, reliable)
                .endpoint(ipInfo.getAeronPubAddress(isRemoteIpv4) + ":" + portPub )

            // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
            //  publication of any state to other threads and not be long running or re-entrant with the client.
            val publication = aeronDriver.addExclusivePublication(publicationUri, streamIdPub, logInfo)

            // if we are IPv6 WILDCARD -- then our subscription must ALSO be IPv6, even if our connection is via IPv4

            // Create a subscription at the given address and port, using the given stream ID.
            val subscriptionUri = uri(CommonContext.UDP_MEDIA, sessionIdSub, reliable)
                .endpoint(ipInfo.formattedListenAddressString + ":" + portSub)


            val subscription = aeronDriver.addSubscription(subscriptionUri, streamIdSub, logInfo)

            return PubSub(publication, subscription,
                          sessionIdPub, sessionIdSub,
                          streamIdPub, streamIdSub,
                          reliable,
                          remoteAddress, remoteAddressString,
                          portPub, portSub)
        }
    }
}
