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

import dorkbox.netUtil.IPv6
import dorkbox.network.ClientConfiguration
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.AeronDriver.Companion.sessionIdAllocator
import dorkbox.network.aeron.AeronDriver.Companion.streamIdAllocator
import dorkbox.network.aeron.mediaDriver.MediaDriverConnection.Companion.uri
import dorkbox.network.connection.EndPoint
import dorkbox.network.connection.ListenerManager.Companion.cleanAllStackTrace
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTraceInternal
import dorkbox.network.exceptions.ClientException
import dorkbox.network.exceptions.ClientRetryException
import dorkbox.network.exceptions.ClientTimedOutException
import io.aeron.Publication
import io.aeron.Subscription
import kotlinx.coroutines.runBlocking
import mu.KLogger
import java.net.Inet4Address
import java.net.InetAddress
import java.util.*
import java.util.concurrent.TimeUnit


/**
 * Set up the subscription + publication channels to the server
 *
 * @throws ClientRetryException if we need to retry to connect
 * @throws ClientTimedOutException if we cannot connect to the server in the designated time
 */
internal open class ClientHandshakeDriver(
    val aeronDriver: AeronDriver,
    autoChangeToIpc: Boolean,
    val remoteAddress: InetAddress?,
    val remoteAddressString: String,
    ipcId: Int,
    val config: ClientConfiguration,
    val handshakeTimeoutSec: Int = 10,
    val reliable: Boolean,
    val logger: KLogger,
) {
    val isUsingIPC: Boolean

    val sessionIdPub: Int
    val sessionIdSub: Int

    val streamIdPub: Int
    val streamIdSub: Int

    val portPub: Int
    val portSub: Int

    lateinit var subscription: Subscription
    lateinit var publication: Publication

    val logInfo: String

    val infoPub: String
    val infoSub: String

    val details: String

    init {
        var isUsingIPC = false

        if (autoChangeToIpc) {
            if (remoteAddress == null) {
                logger.info { "IPC enabled" }
            } else {
                logger.warn { "IPC for loopback enabled and aeron is already running. Auto-changing network connection from '$remoteAddressString' -> IPC" }
            }
            isUsingIPC = true
        }

        var sessionIdPub = 0
        var sessionIdSub = 0
        var streamIdPub = 0
        var streamIdSub = 0
        var portPub = 0
        var portSub = 0
        var subscriptionPort = 0
        var logInfo = ""

        var infoPub = ""
        var infoSub = ""
        var details = ""



        if (isUsingIPC) {
            sessionIdPub = AeronDriver.HANDSHAKE_SESSION_ID
            sessionIdSub = sessionIdAllocator.allocate()
            streamIdPub = ipcId  // this is USUALLY the IPC_HANDSHAKE_STREAM_ID
            streamIdSub = streamIdAllocator.allocate()

            logInfo = "HANDSHAKE-IPC"
            portPub = AeronDriver.HANDSHAKE_SESSION_ID
            subscriptionPort = ipcId

            infoPub = "$logInfo Pub: sessionId=${sessionIdPub}, streamId=${streamIdPub}"
            infoSub = "$logInfo Sub: sessionId=${sessionIdSub}, streamId=${streamIdSub}"

            details = "$logInfo P:(${sessionIdPub}|${streamIdPub}) S:(${sessionIdSub}|${streamIdSub})"

            try {
                buildIPC(logInfo = logInfo,
                         sessionIdPub = sessionIdPub,
                         sessionIdSub = sessionIdSub,
                         streamIdPub = streamIdPub,
                         streamIdSub = streamIdSub,
                         reliable = reliable)

            } catch (exception: Exception) {
                // MAYBE the server doesn't have IPC enabled? If no, we need to connect via network instead
                isUsingIPC = false

                // we will retry!
                if (remoteAddress == null) {
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

            val logType = if (remoteAddress is Inet4Address) {
                "IPv4"
            } else {
                "IPv6"
            }

            sessionIdPub = AeronDriver.HANDSHAKE_SESSION_ID
            sessionIdSub = sessionIdAllocator.allocate()
            streamIdPub = AeronDriver.UDP_HANDSHAKE_STREAM_ID
            streamIdSub = streamIdAllocator.allocate()



            logInfo = "HANDSHAKE-$logType"
//            type = "$logInfo '$remoteAddressPrettyString:${config.port}'"

            // these are the same, because USUALLY for UDP connections, it is connecting to a different computer!
            // if it is the SAME computer, then the client with auto-select a random port.
            portPub = config.port
            portSub = config.port

            buildUDP(
                remoteAddress = remoteAddress,
                remoteAddressString = remoteAddressString,
                portPub = portPub,
                portSub = portSub,
                sessionIdPub = sessionIdPub,
                sessionIdSub = sessionIdSub,
                streamIdPub = streamIdPub,
                streamIdSub = streamIdSub,
                reliable = reliable,
                logInfo = logInfo
            )


            val addressesAndPorts = subscription.localSocketAddresses().first()
            val splitPoint2 = addressesAndPorts.lastIndexOf(':')
            val subscriptionAddress = addressesAndPorts.substring(0, splitPoint2)

            subscriptionPort = addressesAndPorts.substring(splitPoint2+1).toInt()


            infoPub = "$logInfo $remoteAddressString [$portPub] [$streamIdPub|$sessionIdPub] (reliable:$reliable)"
            infoSub = "$logInfo $remoteAddressString [$subscriptionPort] [$streamIdSub|$sessionIdSub] (reliable:$reliable)"

            details = "$logType $subscriptionAddress -> $remoteAddressString     lasdkjf;lsj"

            logger.error { "\n" +
                    "C-HANDSHAKE PUB: $infoPub \n" +
                    "C-HANDSHAKE SUB: $infoSub \n" }
        }

        this.isUsingIPC = isUsingIPC

        this.sessionIdPub = sessionIdPub
        this.sessionIdSub = sessionIdSub

        this.streamIdPub = streamIdPub
        this.streamIdSub = streamIdSub

        this.portPub = portPub
        this.portSub = subscriptionPort

        this.logInfo = logInfo
        this.infoPub = infoPub
        this.infoSub = infoSub

        this.details = details
    }


    private fun buildIPC(logInfo: String, sessionIdPub: Int, sessionIdSub: Int,streamIdPub: Int, streamIdSub: Int, reliable: Boolean) {
        // Create a publication at the given address and port, using the given stream ID.
        // Note: The Aeron.addPublication method will block until the Media Driver acknowledges the request or a timeout occurs.
        val publicationUri = uri("ipc", sessionIdPub, reliable)

        var success = false

        // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
        //  publication of any state to other threads and not be long running or re-entrant with the client.

        // For publications, if we add them "too quickly" (faster than the 'linger' timeout), Aeron will throw exceptions.
        //      ESPECIALLY if it is with the same streamID
        // this check is in the "reconnect" logic

        val publication = aeronDriver.addPublication(publicationUri, logInfo, streamIdPub)

        // always include the linger timeout, so we don't accidentally kill ourself by taking too long
        var timeoutInNanos = TimeUnit.SECONDS.toNanos(handshakeTimeoutSec.toLong()) + aeronDriver.getLingerNs()
        val startTime = System.nanoTime()

        while (System.nanoTime() - startTime < timeoutInNanos) {
            if (publication.isConnected) {
                success = true
                break
            }

            Thread.sleep(500L)
        }

        if (!success) {
            runBlocking {
                aeronDriver.closeAndDeletePublication(publication, logInfo)
            }

            sessionIdAllocator.free(sessionIdPub)

            val clientTimedOutException = ClientTimedOutException("Cannot create publication IPC connection to server")
            clientTimedOutException.cleanAllStackTrace()
            throw clientTimedOutException
        }

        this.publication = publication

        // Create a subscription at the given address and port, using the given stream ID.
        val subscriptionUri = uri("ipc", sessionIdSub, reliable)
        val subscription = aeronDriver.addSubscription(subscriptionUri, logInfo, streamIdSub)

        this.subscription = subscription
    }

    private fun buildUDP(
        remoteAddress: InetAddress,
        remoteAddressString: String,
        portPub: Int,
        portSub: Int,
        sessionIdPub: Int,
        sessionIdSub: Int,
        streamIdPub: Int,
        reliable: Boolean,
        streamIdSub: Int,
        logInfo: String,
    ) {
        var success = false

        // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
        //  publication of any state to other threads and not be long running or re-entrant with the client.

        // on close, the publication CAN linger (in case a client goes away, and then comes back)
        // AERON_PUBLICATION_LINGER_TIMEOUT, 5s by default (this can also be set as a URI param)
        val isIpv4 = remoteAddress is Inet4Address

        // Create a publication at the given address and port, using the given stream ID.
        // Note: The Aeron.addPublication method will block until the Media Driver acknowledges the request or a timeout occurs.
        val publicationUri = uri("udp", sessionIdPub, reliable)
            .endpoint(isIpv4, remoteAddressString, portPub)


        // For publications, if we add them "too quickly" (faster than the 'linger' timeout), Aeron will throw exceptions.
        //      ESPECIALLY if it is with the same streamID. This was noticed as a problem with IPC
        val publication = aeronDriver.addPublication(publicationUri, logInfo, streamIdPub)


        // this will cause us to listen on the interface that connects with the remote address, instead of ALL interfaces.
        val localAddresses = publication.localSocketAddresses().first()
        val splitPoint = localAddresses.lastIndexOf(':')
        var localAddressString = localAddresses.substring(0, splitPoint)

        if (!isIpv4) {
            // this is necessary to clean up the address when adding it to aeron, since different formats mess it up
            // aeron IPv6 addresses all have [...]
            localAddressString = localAddressString.substring(1, localAddressString.length-1)
            localAddressString = IPv6.toString(IPv6.toAddress(localAddressString)!!)
        }


        // Create a subscription the given address and port, using the given stream ID.
        var subscription: Subscription? = null
        var retryCount = 100
        val random = Random()
        val isSameMachine = remoteAddress.isLoopbackAddress || remoteAddress == EndPoint.lanAddress

        while (subscription == null && retryCount-- > 0) {
            // find a random port to bind to if we are loopback OR if we are the same IP address (not loopback, but to ourselves)
            var port = portSub
            if (isSameMachine) {
                port = random.nextInt(Short.MAX_VALUE-1025) + 1024 // range from 1-65534
            }

            try {
                val subscriptionUri = uri("udp", sessionIdSub, reliable)
                    .endpoint(isIpv4, localAddressString, port)
//                    .controlEndpoint(isIpv4, remoteAddressString, port)
//                    .controlMode(CommonContext.MDC_CONTROL_MODE_DYNAMIC)

                subscription = aeronDriver.addSubscription(subscriptionUri, logInfo, streamIdSub)
            } catch (e: IllegalArgumentException) {
                // whoops keep retrying!!
            }
        }

        if (subscription == null) {
            val ex = ClientTimedOutException("Cannot create subscription port $logInfo. All attempted ports are invalid")
            ex.cleanAllStackTrace()
            throw ex
        }


        // always include the linger timeout, so we don't accidentally kill ourselves by taking too long
        val timeoutInNanos = TimeUnit.SECONDS.toNanos(handshakeTimeoutSec.toLong()) + aeronDriver.getLingerNs()
        val startTime = System.nanoTime()

        while (System.nanoTime() - startTime < timeoutInNanos) {
            if (publication.isConnected) {
                success = true
                break
            }

            Thread.sleep(500L)
        }

        if (!success) {
            runBlocking {
                aeronDriver.closeAndDeleteSubscription(subscription, logInfo)
                aeronDriver.closeAndDeletePublication(publication, logInfo)
            }

            sessionIdAllocator.free(sessionIdSub)

            val ex = ClientTimedOutException("Cannot create publication $logInfo ${remoteAddressString} in $handshakeTimeoutSec seconds")
            ex.cleanAllStackTrace()
            throw ex
        }

        this.publication = publication
        this.subscription = subscription
    }

    suspend fun close() {
        sessionIdAllocator.free(sessionIdPub)
        sessionIdAllocator.free(sessionIdSub)
        streamIdAllocator.free(streamIdPub)
//        streamIdAllocator.free(streamIdSub)

        // on close, we want to make sure this file is DELETED!
        aeronDriver.closeAndDeleteSubscription(subscription, logInfo)
        aeronDriver.closeAndDeletePublication(publication, logInfo)
    }

    override fun toString(): String {
        return infoSub
    }
}
