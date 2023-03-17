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

import dorkbox.network.aeron.AeronDriver
import dorkbox.network.aeron.mediaDriver.MediaDriverConnection.Companion.uri
import mu.KLogger

/**
 * For a client, the streamId specified here MUST be manually flipped because they are in the perspective of the SERVER
 * NOTE: IPC connection will ALWAYS have a timeout of 10 second to connect. This is IPC, it should connect fast
 */
internal open class ServerIpcDriver(streamId: Int,
                                    sessionId: Int) :
    MediaDriverServer(port = 0, streamId = streamId, sessionId = sessionId, connectionTimeoutSec = 10, isReliable = true) {


    var success: Boolean = false
    override val type = "ipc"

    /**
     * Setup the subscription + publication channels on the server.
     *
     * serverAddress is ignored for IPC
     */
     override fun build(aeronDriver: AeronDriver, logger: KLogger) {
        // Create a subscription at the given address and port, using the given stream ID.
        val subscriptionUri = uri("ipc", sessionId)

        info = if (sessionId != AeronDriver.RESERVED_SESSION_ID_INVALID) {
                "[$sessionId] IPC listening on [$streamId] [$sessionId]"
            } else {
                "Listening handshake on IPC [$streamId] [$sessionId]"
            }


        success = true
        subscription = aeronDriver.addSubscription(logger, subscriptionUri, "IPC", streamId)
    }
}
