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

import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.mediaDriver.MediaDriverConnection.Companion.uri
import dorkbox.network.connection.ListenerManager
import dorkbox.network.exceptions.ClientRetryException
import dorkbox.network.exceptions.ClientTimedOutException
import mu.KLogger
import java.lang.Thread.sleep
import java.util.concurrent.*

/**
 * For a client, the streamId specified here MUST be manually flipped because they are in the perspective of the SERVER
 * NOTE: IPC connection will ALWAYS have a timeout of 10 second to connect. This is IPC, it should connect fast
 */
internal open class ClientIpcDriver(streamId: Int,
                                    sessionId: Int,
                                    localSessionId: Int) :
    MediaDriverClient(
        port = streamId,
        streamId = streamId,
        remoteSessionId = sessionId,
        localSessionId = localSessionId,
        connectionTimeoutSec = 10,
        isReliable = true
    ) {

    var success: Boolean = false
    override val type = "ipc"

    override val subscriptionPort: Int = localSessionId

    /**
     * Set up the subscription + publication channels to the server
     *
     * @throws ClientRetryException if we need to retry to connect
     * @throws ClientTimedOutException if we cannot connect to the server in the designated time
     */
    fun build(aeronDriver: AeronDriver, logger: KLogger) {
        // Create a publication at the given address and port, using the given stream ID.
        // Note: The Aeron.addPublication method will block until the Media Driver acknowledges the request or a timeout occurs.
        val publicationUri = uri("ipc", remoteSessionId)

        // Create a subscription at the given address and port, using the given stream ID.
        val subscriptionUri = uri("ipc", 0)

        if (logger.isTraceEnabled) {
            logger.trace("IPC client pub URI: ${publicationUri.build()}")
            logger.trace("IPC server sub URI: ${subscriptionUri.build()}")
        }

        var success = false

        // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
        //  publication of any state to other threads and not be long running or re-entrant with the client.

        // For publications, if we add them "too quickly" (faster than the 'linger' timeout), Aeron will throw exceptions.
        //      ESPECIALLY if it is with the same streamID
        // this check is in the "reconnect" logic

        val publication = aeronDriver.addPublication(publicationUri, streamId)
        val subscription = aeronDriver.addSubscription(subscriptionUri, localSessionId)


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

            val clientTimedOutException = ClientTimedOutException("Cannot create publication IPC connection to server")
            ListenerManager.cleanAllStackTrace(clientTimedOutException)
            throw clientTimedOutException
        }

        this.success = true
        this.subscription = subscription
        this.publication = publication
    }

    override val info : String by lazy {
        if (sessionId != AeronDriver.RESERVED_SESSION_ID_INVALID) {
            "[$sessionId] IPC connection established to [$streamId|$subscriptionPort]"
        } else {
            "Connecting handshake to IPC [$streamId|$subscriptionPort]"
        }
    }

    override fun toString(): String {
        return info
    }
}
