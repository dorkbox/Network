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

package dorkbox.network.aeron

import dorkbox.network.connection.ListenerManager
import dorkbox.network.exceptions.ClientRetryException
import dorkbox.network.exceptions.ClientTimedOutException
import io.aeron.ChannelUriStringBuilder
import mu.KLogger
import java.lang.Thread.sleep
import java.util.concurrent.*

/**
 * For a client, the streamId specified here MUST be manually flipped because they are in the perspective of the SERVER
 * NOTE: IPC connection will ALWAYS have a timeout of 10 second to connect. This is IPC, it should connect fast
 */
internal open class IpcMediaDriverConnection(
    streamId: Int,
    val streamIdSubscription: Int,
    sessionId: Int,
    ) :
        MediaDriverConnection(0, 0, streamId, sessionId, 10, true) {

    private fun uri(): ChannelUriStringBuilder {
        val builder = ChannelUriStringBuilder().media("ipc")
        if (sessionId != AeronDriver.RESERVED_SESSION_ID_INVALID) {
            builder.sessionId(sessionId)
        }

        return builder
    }

    /**
     * Set up the subscription + publication channels to the server
     *
     * @throws ClientRetryException if we need to retry to connect
     * @throws ClientTimedOutException if we cannot connect to the server in the designated time
     */
    override fun buildClient(aeronDriver: AeronDriver, logger: KLogger) {
        // Create a publication at the given address and port, using the given stream ID.
        // Note: The Aeron.addPublication method will block until the Media Driver acknowledges the request or a timeout occurs.
        val publicationUri = uri()

        // Create a subscription with a control port (for dynamic MDC) at the given address and port, using the given stream ID.
        val subscriptionUri = uri()


        if (logger.isTraceEnabled) {
            logger.trace("IPC client pub URI: ${publicationUri.build()}")
            logger.trace("IPC server sub URI: ${subscriptionUri.build()}")
        }

        var success = false

        // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
        //  publication of any state to other threads and not be long running or re-entrant with the client.

        val publication = aeronDriver.addPublication(publicationUri, streamId)
        val subscription = aeronDriver.addSubscription(subscriptionUri, streamIdSubscription)


        // We must add the subscription first, because we must be available to listen when the server responds.


        val timoutInNanos = TimeUnit.SECONDS.toNanos(connectionTimeoutSec.toLong())
        var startTime = System.nanoTime()

        // this will wait for the server to acknowledge the connection (all via aeron)
        while (System.nanoTime() - startTime < timoutInNanos) {
            if (subscription.isConnected) {
                success = true
                break
            }

            sleep(500L)
        }

        if (!success) {
            subscription.close()

            val clientTimedOutException = ClientTimedOutException("Cannot create subscription IPC connection to server")
            ListenerManager.cleanAllStackTrace(clientTimedOutException)
            throw clientTimedOutException
        }




        success = false
        startTime = System.nanoTime()

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

    /**
     * Setup the subscription + publication channels on the server.
     *
     * serverAddress is ignored for IPC
     */
    override fun buildServer(aeronDriver: AeronDriver, logger: KLogger, pairConnection: Boolean) {
        // Create a publication with a control port (for dynamic MDC) at the given address and port, using the given stream ID.
        // Note: The Aeron.addPublication method will block until the Media Driver acknowledges the request or a timeout occurs.
        val publicationUri = uri()

        // Create a subscription with a control port (for dynamic MDC) at the given address and port, using the given stream ID.
        val subscriptionUri = uri()


        if (logger.isTraceEnabled) {
            logger.trace("IPC server pub URI: ${publicationUri.build()}")
            logger.trace("IPC server sub URI: ${subscriptionUri.build()}")
        }

        // NOTE: Handlers are called on the client conductor thread. The client conductor thread expects handlers to do safe
        //  publication of any state to other threads and not be long running or re-entrant with the client.

        // on close, the publication CAN linger (in case a client goes away, and then comes back)
        // AERON_PUBLICATION_LINGER_TIMEOUT, 5s by default (this can also be set as a URI param)

        // If we start/stop too quickly, we might have the aeron connectivity issues! Retry a few times.
        success = true
        subscription = aeronDriver.addSubscription(subscriptionUri, streamIdSubscription)
        publication = aeronDriver.addPublication(publicationUri, streamId)
    }

    override val clientInfo : String by lazy {
        if (sessionId != AeronDriver.RESERVED_SESSION_ID_INVALID) {
            "[$sessionId] IPC connection established to [$streamIdSubscription|$streamId]"
        } else {
            "Connecting handshake to IPC [$streamIdSubscription|$streamId]"
        }
    }

    override val serverInfo : String by lazy {
        if (sessionId != AeronDriver.RESERVED_SESSION_ID_INVALID) {
            "[$sessionId] IPC listening on [$streamIdSubscription|$streamId] [$sessionId]"
        } else {
            "Listening handshake on IPC [$streamIdSubscription|$streamId] [$sessionId]"
        }
    }

    override fun toString(): String {
        return serverInfo
    }
}
