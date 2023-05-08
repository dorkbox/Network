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
import dorkbox.network.aeron.AeronDriver.Companion.sessionIdAllocator
import dorkbox.network.aeron.mediaDriver.MediaDriverConnection.Companion.uri
import dorkbox.network.connection.ListenerManager.Companion.cleanAllStackTrace
import dorkbox.network.exceptions.ClientRetryException
import dorkbox.network.exceptions.ClientTimedOutException
import dorkbox.network.handshake.ClientConnectionInfo
import io.aeron.Publication
import io.aeron.Subscription
import kotlinx.coroutines.runBlocking
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit


/**
 * Set up the subscription + publication channels to the server
 *
 * @throws ClientRetryException if we need to retry to connect
 * @throws ClientTimedOutException if we cannot connect to the server in the designated time
 */
internal open class ClientConnectionDriver(
        val handshakeConnection: ClientHandshakeDriver,
        val connectionInfo: ClientConnectionInfo) {

    val aeronDriver = handshakeConnection.aeronDriver
    val handshakeTimeoutSec = handshakeConnection.handshakeTimeoutSec
    val reliable = handshakeConnection.reliable
    val remoteAddress = handshakeConnection.remoteAddress
    val remoteAddressString = handshakeConnection.remoteAddressString

    val logger = handshakeConnection.logger
    val isUsingIPC = handshakeConnection.isUsingIPC

    val sessionIdPub: Int
    val sessionIdSub: Int
    val streamIdPub: Int
    val streamIdSub: Int

    lateinit var subscription: Subscription
    lateinit var publication: Publication

    val logInfo: String
    val info: String

    init {
        var type = ""
        var logInfo = ""
        var info = ""

        // flipped because we are connecting to these!
        sessionIdPub = connectionInfo.sessionIdSub
        sessionIdSub = connectionInfo.sessionIdPub
        streamIdPub = connectionInfo.streamIdSub
        streamIdSub = connectionInfo.streamIdPub



        if (isUsingIPC) {
            // Create a subscription at the given address and port, using the given stream ID.
            logInfo = "CONNECTION-IPC"
            info = "Connecting handshake to IPC [$streamIdPub|$streamIdSub|$sessionIdPub]"

            buildIPC(logInfo = logInfo,
                     sessionIdPub = sessionIdPub,
                     sessionIdSub = sessionIdSub,
                     streamIdPub = streamIdPub,
                     streamIdSub = streamIdSub,
                     reliable = reliable)
        }
        else {
            val logType = if (handshakeConnection.remoteAddress is Inet4Address) {
                "IPv4"
            } else {
                "IPv6"
            }


            logInfo = "CONNECTION-$logType"
//            type = "$logInfo '${handshakeConnection.remoteAddressPrettyString}:${handshakeConnection.config.port}'"

            buildUDP(logInfo = logInfo,
                     sessionIdPub = sessionIdPub,
                     sessionIdSub = sessionIdSub,
                     streamIdPub = streamIdPub,
                     streamIdSub = streamIdSub,
                     remoteAddress = remoteAddress!!,
                     remoteAddressString = remoteAddressString,
                     portPub = handshakeConnection.portPub,
                     portSub = handshakeConnection.portSub,
                     reliable = reliable)

//            val addressesAndPorts = subscription.localSocketAddresses().first()
//            val splitPoint2 = addressesAndPorts.lastIndexOf(':')
//            val subscriptionPort = addressesAndPorts.substring(splitPoint2+1).toInt()




            info = "Connecting handshake to ${handshakeConnection.remoteAddressString} [$streamIdPub|$streamIdSub|$sessionIdPub|$sessionIdSub] (reliable:${handshakeConnection.reliable})"

            logger.error {
                "CLIENT INFO:\n" +
                        "sessionId PUB: $sessionIdPub\n" +
                        "sessionId SUB: $sessionIdSub\n" +
                        "streamId PUB: $streamIdPub\n" +
                        "streamId SUB: $streamIdSub\n" +

                        "port PUB: ${handshakeConnection.portPub}\n" +
                        "port SUB: ${handshakeConnection.portSub}\n" +
                        ""
            }

//            info = "Connecting handshake to ${handshakeConnection.remoteAddressString} [$port|$subscriptionPort] [$streamId|*] (reliable:${handshakeConnection.reliable})"
        }

//        this.subscriptionPort = subscriptionPort
        this.logInfo = logInfo
        this.info = info
    }

    private fun buildIPC(logInfo: String, sessionIdPub: Int, sessionIdSub: Int, streamIdPub: Int, streamIdSub: Int, reliable: Boolean) {
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

        // always include the linger timeout, so we don't accidentally kill ourselves by taking too long
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
                aeronDriver.closeAndDeletePublication(publication, this@ClientConnectionDriver.logInfo)
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
        logInfo: String,
        sessionIdPub: Int,
        sessionIdSub: Int,
        streamIdPub: Int,
        streamIdSub: Int,
        remoteAddress: InetAddress,
        remoteAddressString: String,
        portPub: Int,
        portSub: Int,
        reliable: Boolean,
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
        val subscriptionUri = uri("udp", sessionIdSub, reliable)
            .endpoint(isIpv4, localAddressString, portSub)
//            .controlEndpoint(isIpv4, remoteAddressString, portSub)
//            .controlMode(CommonContext.MDC_CONTROL_MODE_DYNAMIC)

        val subscription = aeronDriver.addSubscription(subscriptionUri, logInfo, streamIdSub)


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

            sessionIdAllocator.free(sessionIdPub)

            val ex = ClientTimedOutException("Cannot create publication $logInfo $remoteAddressString in $handshakeTimeoutSec seconds")
            ex.cleanAllStackTrace()
            throw ex
        }

        this.publication = publication
        this.subscription = subscription
    }

    suspend fun close() {
        // all the session/stream IDs are managed by the server!

        // on close, we want to make sure this file is DELETED!
        aeronDriver.closeAndDeleteSubscription(subscription, logInfo)
        aeronDriver.closeAndDeletePublication(publication, logInfo)
    }

    override fun toString(): String {
        return info
    }

    fun connectionInfo(): MediaDriverConnectInfo {
        return if (isUsingIPC) {
            logger.info { "Creating new IPC connection to $info" }

            MediaDriverConnectInfo(
                subscription = subscription,
                publication = publication,
                sessionIdPub = sessionIdPub,
                streamIdPub = streamIdPub,
                streamIdSub = streamIdSub,
                isReliable = handshakeConnection.reliable,
                remoteAddress = null,
                remoteAddressString = "ipc"
            )
        } else {
            logger.info { "Creating new connection to $info" }

            MediaDriverConnectInfo(
                subscription = subscription,
                publication = publication,
                sessionIdPub = sessionIdPub,
                streamIdPub = streamIdPub,
                streamIdSub = streamIdSub,
                isReliable = handshakeConnection.reliable,
                remoteAddress = handshakeConnection.remoteAddress,
                remoteAddressString = handshakeConnection.remoteAddressString
            )
        }
    }
}
