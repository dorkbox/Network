/*
 * Copyright 2024 dorkbox, llc
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

package dorkbox.network.handshake

import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.AeronDriver.Companion.getLocalAddressString
import dorkbox.network.aeron.AeronDriver.Companion.uri
import dorkbox.network.aeron.controlEndpoint
import dorkbox.network.aeron.endpoint
import dorkbox.network.connection.EndPoint
import dorkbox.network.exceptions.ClientRetryException
import dorkbox.network.exceptions.ClientTimedOutException
import io.aeron.CommonContext
import kotlinx.atomicfu.AtomicBoolean
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
internal class ClientConnectionDriver(val connectionInfo: PubSub) {

    companion object {
        fun build(
            shutdown: AtomicBoolean,
            aeronDriver: AeronDriver,
            handshakeTimeoutNs: Long,
            handshakeConnection: ClientHandshakeDriver,
            connectionInfo: ClientConnectionInfo,
            port2Server: Int, // this is the port2 value from the server
            tagName: String
        ): ClientConnectionDriver {
            val handshakePubSub = handshakeConnection.pubSub
            val reliable = handshakePubSub.reliable

            // flipped because we are connecting to these!
            val sessionIdPub = connectionInfo.sessionIdSub
            val sessionIdSub = connectionInfo.sessionIdPub
            val streamIdPub = connectionInfo.streamIdSub
            val streamIdSub = connectionInfo.streamIdPub

            val isUsingIPC = handshakePubSub.isIpc

            val logInfo: String

            val pubSub: PubSub

            if (isUsingIPC) {
                // Create a subscription at the given address and port, using the given stream ID.
                logInfo = "CONNECTION-IPC"

                pubSub = buildIPC(
                    shutdown = shutdown,
                    aeronDriver = aeronDriver,
                    handshakeTimeoutNs = handshakeTimeoutNs,
                    sessionIdPub = sessionIdPub,
                    sessionIdSub = sessionIdSub,
                    streamIdPub = streamIdPub,
                    streamIdSub = streamIdSub,
                    reliable = reliable,
                    tagName = tagName,
                    logInfo = logInfo
                )
            }
            else {
                val remoteAddress = handshakePubSub.remoteAddress
                val remoteAddressString = handshakePubSub.remoteAddressString
                val portPub = handshakePubSub.portPub
                val portSub = handshakePubSub.portSub

                logInfo = if (remoteAddress is Inet4Address) {
                    "CONNECTION-IPv4"
                } else {
                    "CONNECTION-IPv6"
                }

                pubSub = buildUDP(
                    shutdown = shutdown,
                    aeronDriver = aeronDriver,
                    handshakeTimeoutNs = handshakeTimeoutNs,
                    sessionIdPub = sessionIdPub,
                    sessionIdSub = sessionIdSub,
                    streamIdPub = streamIdPub,
                    streamIdSub = streamIdSub,
                    remoteAddress = remoteAddress!!,
                    remoteAddressString = remoteAddressString,
                    portPub = portPub,
                    portSub = portSub,
                    port2Server = port2Server,
                    reliable = reliable,
                    tagName = tagName,
                    logInfo = logInfo
                )
            }

            return ClientConnectionDriver(pubSub)
        }

        @Throws(ClientTimedOutException::class)
        private fun buildIPC(
            shutdown: AtomicBoolean,
            aeronDriver: AeronDriver,
            handshakeTimeoutNs: Long,
            sessionIdPub: Int,
            sessionIdSub: Int,
            streamIdPub: Int,
            streamIdSub: Int,
            reliable: Boolean,
            tagName: String,
            logInfo: String
        ): PubSub {
            // on close, the publication CAN linger (in case a client goes away, and then comes back)
            // AERON_PUBLICATION_LINGER_TIMEOUT, 5s by default (this can also be set as a URI param)

            // Create a publication at the given address and port, using the given stream ID.
            val publicationUri = uri(CommonContext.IPC_MEDIA, sessionIdPub, reliable)

            // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
            //  publication of any state to other threads and not be long running or re-entrant with the client.


            // can throw an exception! We catch it in the calling class
            val publication = aeronDriver.addPublication(publicationUri, streamIdPub, logInfo, true)

            // can throw an exception! We catch it in the calling class
            // we actually have to wait for it to connect before we continue
            aeronDriver.waitForConnection(shutdown, publication, handshakeTimeoutNs, logInfo) { cause ->
                ClientTimedOutException("$logInfo publication cannot connect with server!", cause)
            }


            // Create a subscription at the given address and port, using the given stream ID.
            val subscriptionUri = uri(CommonContext.IPC_MEDIA, sessionIdSub, reliable)
            val subscription = aeronDriver.addSubscription(subscriptionUri, streamIdSub, logInfo, true)


            // wait for the REMOTE end to also connect to us!
            aeronDriver.waitForConnection(shutdown, subscription, handshakeTimeoutNs, logInfo) { cause ->
                ClientTimedOutException("$logInfo subscription cannot connect with server!", cause)
            }


            return PubSub(
                pub = publication,
                sub = subscription,
                sessionIdPub = sessionIdPub,
                sessionIdSub = sessionIdSub,
                streamIdPub = streamIdPub,
                streamIdSub = streamIdSub,
                reliable = reliable,
                remoteAddress = null,
                remoteAddressString = EndPoint.IPC_NAME,
                portPub = 0,
                portSub = 0,
                tagName = tagName
            )
        }

        @Throws(ClientTimedOutException::class)
        private fun buildUDP(
            shutdown: AtomicBoolean,
            aeronDriver: AeronDriver,
            handshakeTimeoutNs: Long,
            sessionIdPub: Int,
            sessionIdSub: Int,
            streamIdPub: Int,
            streamIdSub: Int,
            remoteAddress: InetAddress,
            remoteAddressString: String,
            portPub: Int,
            portSub: Int,
            port2Server: Int, // this is the port2 value from the server
            reliable: Boolean,
            tagName: String,
            logInfo: String,
        ): PubSub {
            val isRemoteIpv4 = remoteAddress is Inet4Address

            // on close, the publication CAN linger (in case a client goes away, and then comes back)
            // AERON_PUBLICATION_LINGER_TIMEOUT, 5s by default (this can also be set as a URI param)

            // Create a publication at the given address and port, using the given stream ID.
            val publicationUri = uri(CommonContext.UDP_MEDIA, sessionIdPub, reliable)
                .endpoint(isRemoteIpv4, remoteAddressString, portPub)


            // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
            //  publication of any state to other threads and not be long running or re-entrant with the client.

            // can throw an exception! We catch it in the calling class
            val publication = aeronDriver.addPublication(publicationUri, streamIdPub, logInfo, false)

            // can throw an exception! We catch it in the calling class
            // we actually have to wait for it to connect before we continue
            aeronDriver.waitForConnection(shutdown, publication, handshakeTimeoutNs, logInfo) { cause ->
                ClientTimedOutException("$logInfo publication cannot connect with server $remoteAddressString", cause)
            }

            // this will cause us to listen on the interface that connects with the remote address, instead of ALL interfaces.
            val localAddressString = getLocalAddressString(publication, isRemoteIpv4)


            // A control endpoint for the subscriptions will cause a periodic service management "heartbeat" to be sent to the
            // remote endpoint publication, which permits the remote publication to send us data, thereby getting us around NAT
            val subscriptionUri = uri(CommonContext.UDP_MEDIA, sessionIdSub, reliable)
                .endpoint(isRemoteIpv4, localAddressString, portSub)
                .controlEndpoint(isRemoteIpv4, remoteAddressString, port2Server)
                .controlMode(CommonContext.MDC_CONTROL_MODE_DYNAMIC)

            val subscription = aeronDriver.addSubscription(subscriptionUri, streamIdSub, logInfo, false)


            // wait for the REMOTE end to also connect to us!
            aeronDriver.waitForConnection(shutdown, subscription, handshakeTimeoutNs, logInfo) { cause ->
                ClientTimedOutException("$logInfo subscription cannot connect with server!", cause)
            }

            return PubSub(
                pub = publication,
                sub = subscription,
                sessionIdPub = sessionIdPub,
                sessionIdSub = sessionIdSub,
                streamIdPub = streamIdPub,
                streamIdSub = streamIdSub,
                reliable = reliable,
                remoteAddress = remoteAddress,
                remoteAddressString = remoteAddressString,
                portPub = portPub,
                portSub = portSub,
                tagName = tagName
            )
        }
    }
}
