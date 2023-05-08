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
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.AeronDriver.Companion.sessionIdAllocator
import dorkbox.network.aeron.mediaDriver.MediaDriverConnection.Companion.uri
import dorkbox.network.connection.ListenerManager.Companion.cleanAllStackTrace
import dorkbox.network.exceptions.ClientRetryException
import dorkbox.network.exceptions.ClientTimedOutException
import kotlinx.coroutines.delay
import mu.KLogger
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.*

/**
 * A connection timeout of 0, means to wait forever
 */
@Deprecated("to delete")
internal class ClientUdpDriver(aeronDriver: AeronDriver,
                               val address: InetAddress, val addressString: String,
                               port: Int,
                               streamId: Int,
                               sessionId: Int = sessionIdAllocator.allocate(),
                               connectionTimeoutSec: Int = 0,
                               isReliable: Boolean,
                               logInfo: String) :
    MediaDriverClient(
        aeronDriver = aeronDriver,
        port = port,
        streamId = streamId,
        sessionId = sessionId,
        connectionTimeoutSec = connectionTimeoutSec,
        isReliable = isReliable,
        logInfo = logInfo
    ) {

    var success: Boolean = false

    /**
     * @throws ClientRetryException if we need to retry to connect
     * @throws ClientTimedOutException if we cannot connect to the server in the designated time
     */
    @Suppress("DuplicatedCode")
    override suspend fun build(logger: KLogger) {
        var success = false

        // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
        //  publication of any state to other threads and not be long running or re-entrant with the client.

        // on close, the publication CAN linger (in case a client goes away, and then comes back)
        // AERON_PUBLICATION_LINGER_TIMEOUT, 5s by default (this can also be set as a URI param)
        val isIpv4 = address is Inet4Address

        // Create a publication at the given address and port, using the given stream ID.
        // Note: The Aeron.addPublication method will block until the Media Driver acknowledges the request or a timeout occurs.
        val publicationUri = uri("udp", sessionId, isReliable)
            .endpoint(isIpv4, addressString, port)


        // For publications, if we add them "too quickly" (faster than the 'linger' timeout), Aeron will throw exceptions.
        //      ESPECIALLY if it is with the same streamID. This was noticed as a problem with IPC
        val publication = aeronDriver.addPublication(publicationUri, logInfo, streamId)


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
        val subscriptionUri = uri("udp", sessionId, isReliable)
            .endpoint(isIpv4, localAddressString, 0)
//            .controlEndpoint(isIpv4, addressString, port+1)
//            .controlMode(CommonContext.MDC_CONTROL_MODE_DYNAMIC)

        val subscription = aeronDriver.addSubscription(subscriptionUri, logInfo, streamId)


        // always include the linger timeout, so we don't accidentally kill ourselves by taking too long
        val timeoutInNanos = TimeUnit.SECONDS.toNanos(connectionTimeoutSec.toLong()) + aeronDriver.getLingerNs()
        val startTime = System.nanoTime()

        while (System.nanoTime() - startTime < timeoutInNanos) {
            if (publication.isConnected) {
                success = true
                break
            }

            delay(500L)
        }

        if (!success) {
            aeronDriver.closeAndDeleteSubscription(subscription, logInfo)
            aeronDriver.closeAndDeletePublication(publication, logInfo)

            sessionIdAllocator.free(sessionId)

            val ex = ClientTimedOutException("Cannot create publication $logInfo $addressString in $connectionTimeoutSec seconds")
            ex.cleanAllStackTrace()
            throw ex
        }

        info = if (sessionId != AeronDriver.RESERVED_SESSION_ID_INVALID) {
            "$addressString [$port|$subscriptionPort] [$streamId|$sessionId] (reliable:$isReliable)"
        } else {
            "Connecting handshake to $addressString [$port|$subscriptionPort] [$streamId|*] (reliable:$isReliable)"
        }


        val addressesAndPorts = subscription.localSocketAddresses().first()
        val splitPoint2 = addressesAndPorts.lastIndexOf(':')
        this.subscriptionPort = addressesAndPorts.substring(splitPoint2+1).toInt()

        this.success = true
        this.publication = publication
        this.subscription = subscription
    }
}
