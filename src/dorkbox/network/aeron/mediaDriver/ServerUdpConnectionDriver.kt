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

import dorkbox.netUtil.IP
import dorkbox.netUtil.IPv4
import dorkbox.netUtil.IPv6
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.connection.EndPoint
import io.aeron.Publication
import io.aeron.Subscription
import mu.KLogger
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * This represents the connection PAIR between a server<->client
 * A connection timeout of 0, means to wait forever
 */
internal class ServerUdpConnectionDriver(
    val aeronDriver: AeronDriver,
    val sessionIdPub: Int,
    val sessionIdSub: Int,
    val streamIdPub: Int,
    val streamIdSub: Int,

    listenAddress: InetAddress,
    val remoteAddress: InetAddress,
    val remoteAddressString: String,
    val portPub: Int,
    val portSub: Int,
    val isReliable: Boolean,
    val logInfo: String,
    val logger: KLogger,
)
//    :
//    ServerUdpDriver(
//        aeronDriver = driver,
//        listenAddress = listenAddress,
//        port = port,
//        streamId = streamId,
//        sessionId = sessionId,
//        connectionTimeoutSec = connectionTimeoutSec,
//        isReliable = isReliable,
//        logInfo = logInfo
//    )
{


    @Volatile
    lateinit var subscription: Subscription

    @Volatile
    var info = ""


    lateinit var publication: Publication

    var success: Boolean = false

    private val isListenIpv4 = listenAddress is Inet4Address
    protected val listenAddressString = IP.toString(listenAddress)

    init {
        build(remoteAddress, listenAddress, listenAddressString, sessionIdPub, sessionIdSub, streamIdPub, streamIdSub, isReliable)

        logger.error { "\n" +
                "SERVER PUB: stream: $streamIdPub session: $sessionIdPub port: $portPub\n"+
                "SERVER SUB: stream: $streamIdSub session: $sessionIdSub port: $portSub\n" }
    }

    protected val prettyAddressString = when (listenAddress) {
        IPv4.WILDCARD -> listenAddressString
        IPv6.WILDCARD -> IPv4.WILDCARD.hostAddress + "/" + listenAddressString
        else -> listenAddressString
    }


    private fun build(remoteAddress: InetAddress, listenAddress: InetAddress, listenAddressString: String, sessionIdPub: Int, sessionIdSub: Int, streamIdPub: Int, streamIdSub: Int, isReliable: Boolean) {
        // connection timeout of 0 doesn't matter. it is not used by the server
        // the client address WILL BE either IPv4 or IPv6
        val isRemoteIpv4 = remoteAddress is Inet4Address


        // if we are connecting to localhost IPv4 (but our server is IPv6+4), then aeron MUST publish on the IPv4 version
        val properPubAddress = EndPoint.getWildcard(listenAddress, listenAddressString, isRemoteIpv4)

        // create a new publication for the connection (since the handshake ALWAYS closes the current publication)
        val publicationUri = MediaDriverConnection
            .uri("udp", sessionIdPub, isReliable)
//            .controlEndpoint(isRemoteIpv4, properPubAddress, portPub)
//            .controlMode(CommonContext.MDC_CONTROL_MODE_DYNAMIC)
            .endpoint(isRemoteIpv4, properPubAddress, portPub)


        val publication = aeronDriver.addPublication(publicationUri, logInfo, streamIdPub)

        // if we are IPv6 WILDCARD -- then our subscription must ALSO be IPv6, even if our connection is via IPv4
        var subShouldBeIpv4 = isRemoteIpv4

        val properSubAddress = if (listenAddress == IPv6.WILDCARD) {
            subShouldBeIpv4 = false
            IPv6.WILDCARD_STRING
        } else {
            // this will cause us to listen on the interface that connects with the remote address, instead of ALL interfaces.
            val localAddresses = publication.localSocketAddresses().first()
            val splitPoint = localAddresses.lastIndexOf(':')
            var localAddressString = localAddresses.substring(0, splitPoint)

            if (!isRemoteIpv4) {
                // this is necessary to clean up the address when adding it to aeron, since different formats mess it up
                // aeron IPv6 addresses all have [...]
                localAddressString = localAddressString.substring(1, localAddressString.length-1)
                localAddressString = IPv6.toString(IPv6.toAddress(localAddressString)!!)
            }

            localAddressString
        }



        // Create a subscription at the given address and port, using the given stream ID.
        val subscriptionUri = MediaDriverConnection
            .uri("udp", sessionIdSub, isReliable)
            .endpoint(subShouldBeIpv4, properSubAddress, portSub)


        val subscription = aeronDriver.addSubscription(subscriptionUri, logInfo, streamIdSub)

        val remoteAddressString = if (isRemoteIpv4) {
            IPv4.toString(remoteAddress as Inet4Address)
        } else {
            IPv6.toString(remoteAddress as Inet6Address)
        }

        this.info = "$remoteAddressString via $prettyAddressString [$portPub|${portPub+1}] [$streamIdSub|$sessionIdSub] (reliable:$isReliable)"

        this.success = true
        this.publication = publication
        this.subscription = subscription
    }

    fun connectionInfo(): MediaDriverConnectInfo {
        logger.info { "[$logInfo] Creating new IPC connection from UPDASDASDASDSAD .. $remoteAddressString" }


//            logger.info { "[$aeronLogInfo] Creating new UDP connection from $newMediaDriver" }

//            val clientConnection = MediaDriverConnectInfo(
//                publication = newMediaDriver.publication,
//                subscription = newMediaDriver.subscription,
//                sessionId = newMediaDriver.sessionId,
//                streamIdPub = newMediaDriver.streamId,
//                streamIdSub = newMediaDriver.streamId,
////                portSub = newMediaDriver.port,
////                portPub = publicationPort,
//                isReliable = newMediaDriver.isReliable,
//                remoteAddress = clientAddress,
//                remoteAddressString = clientAddressString
//            )


        return MediaDriverConnectInfo(
            publication = publication,
            subscription = subscription,
            sessionIdPub = sessionIdSub,
            streamIdPub = streamIdPub,
            streamIdSub = streamIdSub,
            isReliable = isReliable,
            remoteAddress = remoteAddress,
            remoteAddressString = remoteAddressString
        )
    }

    suspend fun close() {
        // on close, we want to make sure this file is DELETED!
        aeronDriver.closeAndDeletePublication(publication, logInfo)
        aeronDriver.closeAndDeleteSubscription(subscription, logInfo)
    }

    override fun toString(): String {
        return info
    }
}
