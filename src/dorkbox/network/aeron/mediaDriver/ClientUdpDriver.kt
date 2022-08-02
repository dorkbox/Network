/*
 * Copyright 2021 dorkbox, llc
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

import dorkbox.netUtil.IPv4
import dorkbox.netUtil.IPv6
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.mediaDriver.MediaDriverConnection.Companion.uriEndpoint
import dorkbox.network.connection.ListenerManager
import dorkbox.network.exceptions.ClientRetryException
import dorkbox.network.exceptions.ClientTimedOutException
import mu.KLogger
import java.lang.Thread.sleep
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.*

/**
 * For a client, the ports specified here MUST be manually flipped because they are in the perspective of the SERVER.
 * A connection timeout of 0, means to wait forever
 */
internal class ClientUdpDriver(val address: InetAddress, val addressString: String,
                               port: Int,
                               streamId: Int,
                               sessionId: Int,
                               localSessionId: Int,
                               connectionTimeoutSec: Int = 0,
                               isReliable: Boolean) :
    MediaDriverClient(port, streamId, sessionId, localSessionId, connectionTimeoutSec, isReliable) {

    var success: Boolean = false
    override val type: String by lazy {
        if (address is Inet4Address) {
            "IPv4"
        } else {
            "IPv6"
        }
    }

    override val subscriptionPort: Int by lazy {
        val addressesAndPorts = subscription.localSocketAddresses()
        val first = addressesAndPorts.first()

        // split
        val splitPoint = first.lastIndexOf(':')
        val port = first.substring(splitPoint+1)
        port.toInt()
    }

    /**
     * @throws ClientRetryException if we need to retry to connect
     * @throws ClientTimedOutException if we cannot connect to the server in the designated time
     */
    @Suppress("DuplicatedCode")
    fun build(aeronDriver: AeronDriver, logger: KLogger) {
        var success = false

        // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
        //  publication of any state to other threads and not be long running or re-entrant with the client.

        // on close, the publication CAN linger (in case a client goes away, and then comes back)
        // AERON_PUBLICATION_LINGER_TIMEOUT, 5s by default (this can also be set as a URI param)

        // Create a publication at the given address and port, using the given stream ID.
        // Note: The Aeron.addPublication method will block until the Media Driver acknowledges the request or a timeout occurs.
        val publicationUri = uriEndpoint("udp", remoteSessionId, isReliable, address, addressString, port)
        logger.trace("client pub URI: $type ${publicationUri.build()}")

        // For publications, if we add them "too quickly" (faster than the 'linger' timeout), Aeron will throw exceptions.
        //      ESPECIALLY if it is with the same streamID. This was noticed as a problem with IPC
        val publication = aeronDriver.addPublication(publicationUri, streamId)


        val localAddresses = publication.localSocketAddresses().first()
        // split
        val splitPoint = localAddresses.lastIndexOf(':')
        val localAddressString = localAddresses.substring(0, splitPoint)


        // the subscription here is WILDCARD
        val localAddress = if (address is Inet6Address) {
            IPv6.toAddress(localAddressString)!!
        } else {
            IPv4.toAddress(localAddressString)!!
        }

        // Create a subscription the given address and port, using the given stream ID.
        val subscriptionUri = uriEndpoint("udp", localSessionId, isReliable, localAddress, localAddressString, 0)
        logger.trace("client sub URI: $type ${subscriptionUri.build()}")

        val subscription = aeronDriver.addSubscription(subscriptionUri, streamId)

        // always include the linger timeout, so we don't accidentally kill ourself by taking too long
        val timoutInNanos = TimeUnit.SECONDS.toNanos(connectionTimeoutSec.toLong()) + aeronDriver.getLingerNs()
        val startTime = System.nanoTime()

        while (System.nanoTime() - startTime < timoutInNanos) {
            if (publication.isConnected) {
                success = true
                break
            }

            sleep(500L)
        }

        if (!success) {
            subscription.close()
            publication.close()

            val ex = ClientTimedOutException("Cannot create publication to $type $addressString in $connectionTimeoutSec seconds")
            ListenerManager.cleanAllStackTrace(ex)
            throw ex
        }

        this.success = true
        this.publication = publication
        this.subscription = subscription
    }

    override val info: String by lazy {
        if (sessionId != AeronDriver.RESERVED_SESSION_ID_INVALID) {
            "$addressString [$port|$subscriptionPort] [$streamId|$sessionId] (reliable:$isReliable)"
        } else {
            "Connecting handshake to $addressString [$port|$subscriptionPort] [$streamId|*] (reliable:$isReliable)"
        }
    }

    override fun toString(): String {
        return info
    }
}
