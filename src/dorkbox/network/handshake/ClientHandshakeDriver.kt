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
import dorkbox.network.aeron.AeronDriver.Companion.streamIdAllocator
import dorkbox.network.aeron.AeronDriver.Companion.uri
import dorkbox.network.aeron.AeronDriver.Companion.uriHandshake
import dorkbox.network.aeron.controlEndpoint
import dorkbox.network.aeron.endpoint
import dorkbox.network.connection.CryptoManagement
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.ListenerManager.Companion.cleanAllStackTrace
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTraceInternal
import dorkbox.network.exceptions.ClientException
import dorkbox.network.exceptions.ClientRetryException
import dorkbox.network.exceptions.ClientTimedOutException
import dorkbox.util.Sys
import io.aeron.CommonContext
import io.aeron.Subscription
import kotlinx.atomicfu.AtomicBoolean
import org.slf4j.Logger
import java.net.Inet4Address
import java.net.InetAddress
import java.util.*



/**
 * Set up the subscription + publication channels to the server
 *
 * @throws ClientRetryException if we need to retry to connect
 * @throws ClientTimedOutException if we cannot connect to the server in the designated time
 */
internal class ClientHandshakeDriver(
    val aeronDriver: AeronDriver,
    val pubSub: PubSub,
    private val logInfo: String,
    val details: String
) {
    companion object {
        fun build(
            endpoint: EndPoint<*>,
            aeronDriver: AeronDriver,
            autoChangeToIpc: Boolean,
            remoteAddress: InetAddress?,
            remoteAddressString: String,
            remotePort1: Int,
            remotePort2: Int,
            clientListenPort: Int,
            handshakeTimeoutNs: Long,
            connectionTimoutInNs: Long,
            reliable: Boolean,
            tagName: String,
            logger: Logger
        ): ClientHandshakeDriver {
            logger.trace("Starting client handshake")

            var isUsingIPC = false

            if (autoChangeToIpc) {
                if (remoteAddress == null) {
                    logger.info("IPC enabled")
                } else {
                    logger.warn("IPC for loopback enabled and aeron is already running. Auto-changing network connection from '$remoteAddressString' -> IPC")
                }
                isUsingIPC = true
            }


            var logInfo = ""

            var details = ""

            // this must be unique otherwise we CANNOT connect to the server!
            val sessionIdPub = CryptoManagement.secureRandom.nextInt()

            // with IPC, the aeron driver MUST be shared, so having a UNIQUE sessionIdPub/Sub is unnecessary.
//          sessionIdPub = sessionIdAllocator.allocate()
//          sessionIdSub = sessionIdAllocator.allocate()
            // streamIdPub is assigned by ipc/udp directly
            var streamIdPub: Int
            val streamIdSub = streamIdAllocator.allocate() // sub stream ID so the server can comm back to the client

            var pubSub: PubSub? = null

            val timeoutInfo = if (connectionTimoutInNs > 0L) {
                "[Handshake: ${Sys.getTimePrettyFull(handshakeTimeoutNs)}, Max connection attempt: ${Sys.getTimePrettyFull(connectionTimoutInNs)}]"
            } else {
                "[Handshake: ${Sys.getTimePrettyFull(handshakeTimeoutNs)}, Max connection attempt: Unlimited]"
            }

            val config = endpoint.config
            val shutdown = endpoint.shutdown

            if (isUsingIPC) {
                streamIdPub = config.ipcId

                logInfo = "HANDSHAKE-IPC"
                details = logInfo


                logger.info("Client connecting via IPC. $timeoutInfo")

                try {
                    pubSub = buildIPC(
                        shutdown = shutdown,
                        aeronDriver = aeronDriver,
                        handshakeTimeoutNs = handshakeTimeoutNs,
                        sessionIdPub = sessionIdPub,
                        streamIdPub = streamIdPub,
                        streamIdSub = streamIdSub,
                        reliable = reliable,
                        tagName = tagName,
                        logInfo = logInfo
                    )
                } catch (exception: Exception) {
                    logger.error("Error initializing IPC connection", exception)

                    // MAYBE the server doesn't have IPC enabled? If no, we need to connect via network instead
                    isUsingIPC = false

                    // we will retry!
                    if (remoteAddress == null) {
                        // the exception will HARD KILL the client, make sure aeron driver is closed.
                        aeronDriver.close()

                        // if we specified that we MUST use IPC, then we have to throw the exception, because there is no IPC
                        val clientException = ClientException("Unable to connect via IPC to server. No address specified so fallback is unavailable", exception)
                        clientException.cleanStackTraceInternal()
                        throw clientException
                    }
                }
            }

            if (!isUsingIPC) {
                if (remoteAddress == null) {
                    val clientException = ClientException("Unable to connect via UDP to server. No address specified!")
                    clientException.cleanStackTraceInternal()
                    throw clientException
                }

                logInfo = if (remoteAddress is Inet4Address) {
                    "HANDSHAKE-IPv4"
                } else {
                    "HANDSHAKE-IPv6"
                }

                streamIdPub = config.udpId


                if (remoteAddress is Inet4Address) {
                    logger.info("Client connecting to IPv4 $remoteAddressString. $timeoutInfo")
                } else {
                    logger.info("Client connecting to IPv6 $remoteAddressString. $timeoutInfo")
                }

                pubSub = buildUDP(
                    shutdown = shutdown,
                    aeronDriver = aeronDriver,
                    handshakeTimeoutNs = handshakeTimeoutNs,
                    remoteAddress = remoteAddress,
                    remoteAddressString = remoteAddressString,
                    portPub = remotePort1,
                    portSub = clientListenPort,
                    port2Server = remotePort2,
                    sessionIdPub = sessionIdPub,
                    streamIdPub = streamIdPub,
                    reliable = reliable,
                    streamIdSub = streamIdSub,
                    tagName = tagName,
                    logInfo = logInfo
                )


                // we have to figure out what our sub port info is, otherwise the server cannot connect back!
                val subscriptionAddress = try {
                    getLocalAddressString(pubSub.sub)
                } catch (e: Exception) {
                    throw ClientRetryException("$logInfo subscription is not properly created!", e)
                }

                details = if (subscriptionAddress == remoteAddressString) {
                    logInfo
                } else {
                    "$logInfo $subscriptionAddress -> $remoteAddressString"
                }
            }

            return ClientHandshakeDriver(aeronDriver, pubSub!!, logInfo, details)
        }

        @Throws(ClientTimedOutException::class)
        private fun buildIPC(
            shutdown: AtomicBoolean,
            aeronDriver: AeronDriver,
            handshakeTimeoutNs: Long,
            sessionIdPub: Int,
            streamIdPub: Int,
            streamIdSub: Int,
            reliable: Boolean,
            tagName: String,
            logInfo: String,
        ): PubSub {
            // Create a publication at the given address and port, using the given stream ID.
            // Note: The Aeron.addPublication method will block until the Media Driver acknowledges the request or a timeout occurs.
            val publicationUri = uri(CommonContext.IPC_MEDIA, sessionIdPub, reliable)

            // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
            //  publication of any state to other threads and not be long running or re-entrant with the client.

            // For publications, if we add them "too quickly" (faster than the 'linger' timeout), Aeron will throw exceptions.
            //      ESPECIALLY if it is with the same streamID
            // this check is in the "reconnect" logic

            // can throw an exception! We catch it in the calling class
            val publication = aeronDriver.addPublication(publicationUri, streamIdPub, logInfo, true)

            // can throw an exception! We catch it in the calling class
            // we actually have to wait for it to connect before we continue
            aeronDriver.waitForConnection(shutdown, publication, handshakeTimeoutNs, logInfo) { cause ->
                ClientTimedOutException("$logInfo publication cannot connect with server in ${Sys.getTimePrettyFull(handshakeTimeoutNs)}", cause)
            }

            // Create a subscription at the given address and port, using the given stream ID.
            val subscriptionUri = uriHandshake(CommonContext.IPC_MEDIA, reliable)
            val subscription = aeronDriver.addSubscription(subscriptionUri, streamIdSub, logInfo, true)

            return PubSub(
                pub = publication,
                sub = subscription,
                sessionIdPub = sessionIdPub,
                sessionIdSub = 0,
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
            remoteAddress: InetAddress,
            remoteAddressString: String,
            portPub: Int, // this is the port1 value from the server
            portSub: Int,
            port2Server: Int, // this is the port2 value from the server
            sessionIdPub: Int,
            streamIdPub: Int,
            reliable: Boolean,
            streamIdSub: Int,
            tagName: String,
            logInfo: String,
        ): PubSub {
            @Suppress("NAME_SHADOWING")
            var portSub = portSub

            // on close, the publication CAN linger (in case a client goes away, and then comes back)
            // AERON_PUBLICATION_LINGER_TIMEOUT, 5s by default (this can also be set as a URI param)

            val isRemoteIpv4 = remoteAddress is Inet4Address

            // Create a publication at the given address and port, using the given stream ID.
            // ANY sessionID for the publication will work, because the SERVER doesn't have it defined
            val publicationUri = uri(CommonContext.UDP_MEDIA, sessionIdPub, reliable)
                .endpoint(isRemoteIpv4, remoteAddressString, portPub)


            // For publications, if we add them "too quickly" (faster than the 'linger' timeout), Aeron will throw exceptions.
            //      ESPECIALLY if it is with the same streamID. This was noticed as a problem with IPC

            // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
            //  publication of any state to other threads and not be long running or re-entrant with the client.


            // can throw an exception! We catch it in the calling class
            val publication = aeronDriver.addPublication(publicationUri, streamIdPub, logInfo, false)

            // can throw an exception! We catch it in the calling class
            // we actually have to wait for it to connect before we continue
            aeronDriver.waitForConnection(shutdown, publication, handshakeTimeoutNs, logInfo) { cause ->
                streamIdAllocator.free(streamIdSub) // we don't continue, so close this as well
                ClientTimedOutException("$logInfo publication cannot connect with server in ${Sys.getTimePrettyFull(handshakeTimeoutNs)}", cause)
            }


            // this will cause us to listen on the interface that connects with the remote address, instead of ALL interfaces.
            val localAddressString = getLocalAddressString(publication, isRemoteIpv4)


            // Create a subscription the given address and port, using the given stream ID.
            var subscription: Subscription? = null

            if (portSub > -1) {
                // this means we have EXPLICITLY defined a port, we must try to use it

                // A control endpoint for the subscriptions will cause a periodic service management "heartbeat" to be sent to the
                // remote endpoint publication, which permits the remote publication to send us data, thereby getting us around NAT
                val subscriptionUri = uriHandshake(CommonContext.UDP_MEDIA, reliable)
                    .endpoint(isRemoteIpv4, localAddressString, portSub)
                    .controlEndpoint(isRemoteIpv4, remoteAddressString, port2Server)
                    .controlMode(CommonContext.MDC_CONTROL_MODE_DYNAMIC)

                subscription = aeronDriver.addSubscription(subscriptionUri, streamIdSub, logInfo, false)
            } else {
                // randomly select what port should be used
                var retryCount = 100
                val random = CryptoManagement.secureRandom
                val isSameMachine = remoteAddress.isLoopbackAddress || remoteAddress == EndPoint.lanAddress

                portSub = random.nextInt(Short.MAX_VALUE-1025) + 1025
                while (subscription == null && retryCount-- > 0) {
                    // find a random port to bind to if we are loopback OR if we are the same IP address (not loopback, but to ourselves)
                    if (isSameMachine) {
                        // range from 1025-65534
                        portSub = random.nextInt(Short.MAX_VALUE-1025) + 1025
                    }

                    try {
                        // A control endpoint for the subscriptions will cause a periodic service management "heartbeat" to be sent to the
                        // remote endpoint publication, which permits the remote publication to send us data, thereby getting us around NAT
                        val subscriptionUri = uriHandshake(CommonContext.UDP_MEDIA, reliable)
                            .endpoint(isRemoteIpv4, localAddressString, portSub)
                            .controlEndpoint(isRemoteIpv4, remoteAddressString, port2Server)
                            .controlMode(CommonContext.MDC_CONTROL_MODE_DYNAMIC)

                        subscription = aeronDriver.addSubscription(subscriptionUri, streamIdSub, logInfo, false)
                    } catch (ignored: Exception) {
                        // whoops keep retrying!!
                    }
                }
            }

            if (subscription == null) {
                val ex = ClientTimedOutException("Cannot create subscription port $logInfo. All attempted ports are invalid")
                ex.cleanAllStackTrace()
                throw ex
            }

            return PubSub(
                pub = publication,
                sub = subscription,
                sessionIdPub = sessionIdPub,
                sessionIdSub = 0,
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

    fun close(endpoint: EndPoint<*>) {
        // only the subs are allocated on the client!
//        sessionIdAllocator.free(pubSub.sessionIdPub)
//        sessionIdAllocator.free(sessionIdSub)
//        streamIdAllocator.free(streamIdPub)
        streamIdAllocator.free(pubSub.streamIdSub)

        // on close, we want to make sure this file is DELETED!

        // we might not be able to close these connections.
        try {
            aeronDriver.close(pubSub.sub, logInfo)
        }
        catch (e: Exception) {
            endpoint.listenerManager.notifyError(e)
        }
        try {
            aeronDriver.close(pubSub.pub, logInfo)
        }
        catch (e: Exception) {
            endpoint.listenerManager.notifyError(e)
        }
    }
}
