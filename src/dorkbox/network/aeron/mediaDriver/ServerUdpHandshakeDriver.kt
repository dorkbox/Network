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
import dorkbox.network.aeron.mediaDriver.MediaDriverConnection.Companion.uri
import io.aeron.Subscription
import mu.KLogger
import java.net.Inet4Address
import java.net.InetAddress

/**
 * For a client, the ports specified here MUST be manually flipped because they are in the perspective of the SERVER.
 * A connection timeout of 0, means to wait forever
 */
internal open class ServerUdpHandshakeDriver(
    val aeronDriver: AeronDriver,
    val listenAddress: InetAddress,
    val port: Int,
    val streamId: Int,
    val sessionId: Int,
    val connectionTimeoutSec: Int,
    val isReliable: Boolean,
    val logInfo: String,
    val logger: KLogger
)
//    :
//    MediaDriverServer(
//        aeronDriver = aeronDriver,
//        port = port,
//        streamId = streamId,
//        sessionId = sessionId,
//        connectionTimeoutSec = connectionTimeoutSec,
//        isReliable = isReliable,
//        logInfo = logInfo
//    )
{


    var success: Boolean = false

    lateinit var subscription: Subscription

    @Volatile
    var info = ""


    private val isListenIpv4 = listenAddress is Inet4Address
    protected val listenAddressString = IP.toString(listenAddress)

    protected val prettyAddressString = when (listenAddress) {
        IPv4.WILDCARD -> listenAddressString
        IPv6.WILDCARD -> IPv4.WILDCARD.hostAddress + "/" + listenAddressString
        else -> listenAddressString
    }

    init {
        build()
    }

    private fun build() {
        // Create a subscription at the given address and port, using the given stream ID.
        val subscriptionUri = uri("udp", sessionId, isReliable)
            .endpoint(isListenIpv4, listenAddressString, port)

        this.info = "Listening handshake on $prettyAddressString [$port}] [$streamId|*] (reliable:$isReliable)"

        this.success = true
        this.subscription = aeronDriver.addSubscription(subscriptionUri, logInfo, streamId)
    }

    suspend fun close() {
        // on close, we want to make sure this file is DELETED!
        aeronDriver.closeAndDeleteSubscription(subscription, logInfo)
    }

    override fun toString(): String {
        return info
    }
}
