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

import dorkbox.network.ClientConfiguration
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.AeronDriver.Companion.getLocalAddressString
import dorkbox.network.aeron.AeronDriver.Companion.streamIdAllocator
import dorkbox.network.aeron.AeronDriver.Companion.uri
import dorkbox.network.aeron.AeronDriver.Companion.uriHandshake
import dorkbox.network.aeron.endpoint
import dorkbox.network.connection.CryptoManagement
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.ListenerManager.Companion.cleanAllStackTrace
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTraceInternal
import dorkbox.network.exceptions.ClientException
import dorkbox.network.exceptions.ClientRetryException
import dorkbox.network.exceptions.ClientTimedOutException
import io.aeron.CommonContext
import io.aeron.Subscription
import mu.KLogger
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
    private val aeronDriver: AeronDriver,
    val pubSub: PubSub,
    private val logInfo: String,
    val details: String
) {
    companion object {
        suspend fun build(
            aeronDriver: AeronDriver,
            autoChangeToIpc: Boolean,
            remoteAddress: InetAddress?,
            remoteAddressString: String,
            config: ClientConfiguration,
            handshakeTimeoutSec: Int = 10,
            reliable: Boolean,
            logger: KLogger): ClientHandshakeDriver {

            var isUsingIPC = false

            if (autoChangeToIpc) {
                if (remoteAddress == null) {
                    logger.info { "IPC enabled" }
                } else {
                    logger.warn { "IPC for loopback enabled and aeron is already running. Auto-changing network connection from '$remoteAddressString' -> IPC" }
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

            if (isUsingIPC) {
                streamIdPub = AeronDriver.IPC_HANDSHAKE_STREAM_ID

                logInfo = "HANDSHAKE-IPC"
                details = logInfo

                try {
                    pubSub = buildIPC(
                        aeronDriver = aeronDriver,
                        handshakeTimeoutSec = handshakeTimeoutSec,
                        sessionIdPub = sessionIdPub,
                        streamIdPub = streamIdPub,
                        streamIdSub = streamIdSub,
                        reliable = reliable,
                        logInfo = logInfo
                    )
                } catch (exception: Exception) {
                    logger.error(exception) { "Error initializing IPC connection" }

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



                streamIdPub = AeronDriver.UDP_HANDSHAKE_STREAM_ID


                // NOTE: for the PORT ... these are the same, because USUALLY for UDP connections, it is connecting to a different computer!
                // if it is the SAME computer, then the client with auto-select a random port.

                pubSub = buildUDP(
                    aeronDriver = aeronDriver,
                    handshakeTimeoutSec = handshakeTimeoutSec,
                    remoteAddress = remoteAddress,
                    remoteAddressString = remoteAddressString,
                    portPub = config.port,
                    portSub = config.port,
                    sessionIdPub = sessionIdPub,
                    streamIdPub = streamIdPub,
                    reliable = reliable,
                    streamIdSub = streamIdSub,
                    logInfo = logInfo
                )


                // we have to figure out what our sub port info is, otherwise the server cannot connect back!
                val addressesAndPorts = pubSub.sub.localSocketAddresses().first()
                val splitPoint2 = addressesAndPorts.lastIndexOf(':')
                val subscriptionAddress = addressesAndPorts.substring(0, splitPoint2)

                details = if (subscriptionAddress == remoteAddressString) {
                    logInfo
                } else {
                    "$logInfo $subscriptionAddress -> $remoteAddressString"
                }
            }

            return ClientHandshakeDriver(aeronDriver, pubSub!!, logInfo, details)
        }

        @Throws(ClientTimedOutException::class)
        private suspend fun buildIPC(
            aeronDriver: AeronDriver,
            handshakeTimeoutSec: Int,
            sessionIdPub: Int,
            streamIdPub: Int, streamIdSub: Int,
            reliable: Boolean,
            logInfo: String
        ): PubSub {
            // Create a publication at the given address and port, using the given stream ID.
            // Note: The Aeron.addPublication method will block until the Media Driver acknowledges the request or a timeout occurs.
            val publicationUri = uri(CommonContext.IPC_MEDIA, sessionIdPub, reliable)

            // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
            //  publication of any state to other threads and not be long running or re-entrant with the client.

            // For publications, if we add them "too quickly" (faster than the 'linger' timeout), Aeron will throw exceptions.
            //      ESPECIALLY if it is with the same streamID
            // this check is in the "reconnect" logic

            val publication = aeronDriver.addPublicationWithTimeout(publicationUri, handshakeTimeoutSec, streamIdPub, logInfo)
            { cause ->
                ClientTimedOutException("$logInfo publication cannot connect with server!", cause)
            }

            // Create a subscription at the given address and port, using the given stream ID.
            val subscriptionUri = uriHandshake(CommonContext.IPC_MEDIA, reliable)
            val subscription = aeronDriver.addSubscription(subscriptionUri, streamIdSub, logInfo)

            return PubSub(publication, subscription,
                          sessionIdPub, 0,
                          streamIdPub, streamIdSub,
                          reliable)
        }

        @Throws(ClientTimedOutException::class)
        private suspend fun buildUDP(
            aeronDriver: AeronDriver,
            handshakeTimeoutSec: Int,
            remoteAddress: InetAddress,
            remoteAddressString: String,
            portPub: Int,
            portSub: Int,
            sessionIdPub: Int,
            streamIdPub: Int,
            reliable: Boolean,
            streamIdSub: Int,
            logInfo: String,
        ): PubSub {
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
            val publication = aeronDriver.addPublicationWithTimeout(publicationUri, handshakeTimeoutSec, streamIdPub, logInfo)
            { cause ->
                streamIdAllocator.free(streamIdSub) // we don't continue, so close this as well
                ClientTimedOutException("$logInfo publication cannot connect with server!", cause)
            }


            // this will cause us to listen on the interface that connects with the remote address, instead of ALL interfaces.
            val localAddressString = getLocalAddressString(publication, remoteAddress)


            // Create a subscription the given address and port, using the given stream ID.
            var subscription: Subscription? = null
            var retryCount = 100
            val random = Random()
            val isSameMachine = remoteAddress.isLoopbackAddress || remoteAddress == EndPoint.lanAddress

            var actualPortSub = portSub
            while (subscription == null && retryCount-- > 0) {
                // find a random port to bind to if we are loopback OR if we are the same IP address (not loopback, but to ourselves)
                if (isSameMachine) {
                    // range from 1025-65534
                    actualPortSub = random.nextInt(Short.MAX_VALUE-1025) + 1025
                }

                try {
                    val subscriptionUri = uriHandshake(CommonContext.UDP_MEDIA, reliable)
                        .endpoint(isRemoteIpv4, localAddressString, actualPortSub)

                    subscription = aeronDriver.addSubscription(subscriptionUri, streamIdSub, logInfo)
                } catch (ignored: Exception) {
                    // whoops keep retrying!!
                }
            }

            if (subscription == null) {
                val ex = ClientTimedOutException("Cannot create subscription port $logInfo. All attempted ports are invalid")
                ex.cleanAllStackTrace()
                throw ex
            }

            return PubSub(publication, subscription,
                          sessionIdPub, 0,
                          streamIdPub, streamIdSub,
                          reliable,
                          remoteAddress, remoteAddressString,
                          portPub, actualPortSub)
        }
    }

    suspend fun close() {
        // only the subs are allocated on the client!
//        sessionIdAllocator.free(pubSub.sessionIdPub)
//        sessionIdAllocator.free(sessionIdSub)
//        streamIdAllocator.free(streamIdPub)
        streamIdAllocator.free(pubSub.streamIdSub)

        // on close, we want to make sure this file is DELETED!
        aeronDriver.closeAndDeleteSubscription(pubSub.sub, logInfo)
        aeronDriver.closeAndDeletePublication(pubSub.pub, logInfo)
    }
}
