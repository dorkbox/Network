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
import dorkbox.network.aeron.AeronDriver.Companion.getLocalAddressString
import dorkbox.network.aeron.AeronDriver.Companion.sessionIdAllocator
import dorkbox.network.aeron.AeronDriver.Companion.uri
import dorkbox.network.aeron.endpoint
import dorkbox.network.exceptions.ClientRetryException
import dorkbox.network.exceptions.ClientTimedOutException
import dorkbox.network.handshake.ClientConnectionInfo
import java.net.Inet4Address
import java.net.InetAddress


/**
 * Set up the subscription + publication channels to the server
 *
 * Note: this class is NOT closed the traditional way! It's pub/sub objects are used by the connection (which is where they are closed)
 *
 * @throws ClientRetryException if we need to retry to connect
 * @throws ClientTimedOutException if we cannot connect to the server in the designated time
 */
internal class ClientConnectionDriver(val connectionInfo: PubSub, val info: String) {

    companion object {
        suspend fun build(
            aeronDriver: AeronDriver,
            connectionTimeoutSec: Int,
            handshakeConnection: ClientHandshakeDriver,
            connectionInfo: ClientConnectionInfo
        ): ClientConnectionDriver {
            val reliable = handshakeConnection.pubSub.reliable

            // flipped because we are connecting to these!
            val sessionIdPub = connectionInfo.sessionIdSub
            val sessionIdSub = connectionInfo.sessionIdPub
            val streamIdPub = connectionInfo.streamIdSub
            val streamIdSub = connectionInfo.streamIdPub

            val isUsingIPC = handshakeConnection.pubSub.isIpc

            val logInfo: String
            val info: String

            val pubSub: PubSub

            if (isUsingIPC) {
                // Create a subscription at the given address and port, using the given stream ID.
                logInfo = "CONNECTION-IPC"
                info = "IPC [$streamIdPub|$streamIdSub|$sessionIdPub]"

                pubSub = buildIPC(
                    aeronDriver = aeronDriver,
                    handshakeTimeoutSec = connectionTimeoutSec,
                    sessionIdPub = sessionIdPub,
                    sessionIdSub = sessionIdSub,
                    streamIdPub = streamIdPub,
                    streamIdSub = streamIdSub,
                    reliable = reliable,
                    logInfo = logInfo
                )
            }
            else {
                val remoteAddress = handshakeConnection.pubSub.remoteAddress
                val remoteAddressString = handshakeConnection.pubSub.remoteAddressString

                logInfo = if (remoteAddress is Inet4Address) {
                    "CONNECTION-IPv4"
                } else {
                    "CONNECTION-IPv6"
                }
                info = "$remoteAddressString [$streamIdPub|$streamIdSub|$sessionIdPub|$sessionIdSub] (reliable:${reliable})"

                pubSub = buildUDP(
                    aeronDriver = aeronDriver,
                    handshakeTimeoutSec = connectionTimeoutSec,
                    sessionIdPub = sessionIdPub,
                    sessionIdSub = sessionIdSub,
                    streamIdPub = streamIdPub,
                    streamIdSub = streamIdSub,
                    remoteAddress = remoteAddress!!,
                    remoteAddressString = remoteAddressString,
                    portPub = handshakeConnection.pubSub.portPub,
                    portSub = handshakeConnection.pubSub.portSub,
                    reliable = reliable,
                    logInfo = logInfo
                )
            }

            val driver = ClientConnectionDriver(pubSub, info)
            return driver
        }

        @Throws(ClientTimedOutException::class)
        private suspend fun buildIPC(
            aeronDriver: AeronDriver,
            handshakeTimeoutSec: Int,
            sessionIdPub: Int,
            sessionIdSub: Int,
            streamIdPub: Int,
            streamIdSub: Int,
            reliable: Boolean,
            logInfo: String
        ): PubSub {
            // on close, the publication CAN linger (in case a client goes away, and then comes back)
            // AERON_PUBLICATION_LINGER_TIMEOUT, 5s by default (this can also be set as a URI param)

            // Create a publication at the given address and port, using the given stream ID.
            val publicationUri = uri("ipc", sessionIdPub, reliable)

            // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
            //  publication of any state to other threads and not be long running or re-entrant with the client.
            val publication = aeronDriver.addPublicationWithTimeout(publicationUri, handshakeTimeoutSec, streamIdPub, logInfo)
            { cause ->
                sessionIdAllocator.free(sessionIdPub)
                ClientTimedOutException("$logInfo publication cannot connect with server!", cause)
            }

            // Create a subscription at the given address and port, using the given stream ID.
            val subscriptionUri = uri("ipc", sessionIdSub, reliable)
            val subscription = aeronDriver.addSubscription(subscriptionUri, streamIdSub, logInfo)

            return PubSub(publication, subscription,
                          sessionIdPub, sessionIdSub,
                          streamIdPub, streamIdSub,
                          reliable)
        }

        @Throws(ClientTimedOutException::class)
        private suspend fun buildUDP(
            aeronDriver: AeronDriver,
            handshakeTimeoutSec: Int,
            sessionIdPub: Int,
            sessionIdSub: Int,
            streamIdPub: Int,
            streamIdSub: Int,
            remoteAddress: InetAddress,
            remoteAddressString: String,
            portPub: Int,
            portSub: Int,
            reliable: Boolean,
            logInfo: String,
        ): PubSub {
            val isRemoteIpv4 = remoteAddress is Inet4Address

            // on close, the publication CAN linger (in case a client goes away, and then comes back)
            // AERON_PUBLICATION_LINGER_TIMEOUT, 5s by default (this can also be set as a URI param)

            // Create a publication at the given address and port, using the given stream ID.
            val publicationUri = uri("udp", sessionIdPub, reliable)
                .endpoint(isRemoteIpv4, remoteAddressString, portPub)


            // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
            //  publication of any state to other threads and not be long running or re-entrant with the client.
            val publication = aeronDriver.addPublicationWithTimeout(publicationUri, handshakeTimeoutSec, streamIdPub, logInfo)
            { cause ->
                sessionIdAllocator.free(sessionIdPub)
                ClientTimedOutException("$logInfo publication cannot connect with server $remoteAddressString", cause)
            }

            // this will cause us to listen on the interface that connects with the remote address, instead of ALL interfaces.
            val localAddressString = getLocalAddressString(publication, remoteAddress)

            val subscriptionUri = uri("udp", sessionIdSub, reliable)
                .endpoint(isRemoteIpv4, localAddressString, portSub)

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
