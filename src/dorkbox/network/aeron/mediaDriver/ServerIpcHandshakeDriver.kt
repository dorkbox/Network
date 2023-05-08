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
import io.aeron.Subscription
import mu.KLogger

/**
 * For a client, the streamId specified here MUST be manually flipped because they are in the perspective of the SERVER
 * NOTE: IPC connection will ALWAYS have a timeout of 10 second to connect. This is IPC, it should connect fast
 */
internal open class ServerIpcHandshakeDriver(
    val aeronDriver: AeronDriver,
    streamIdSub: Int,
    sessionIdSub: Int,
    logger: KLogger
) {

    @Volatile
    lateinit var subscription: Subscription

    @Volatile
    var info = ""


    var success: Boolean = false

    init {
        buildIPC(sessionIdSub, streamIdSub,  "HANDSHAKE-IPC")
    }

    /**
     * Setup the subscription + publication channels on the server.
     *
     * serverAddress is ignored for IPC
     */
    private fun buildIPC(sessionIdSub: Int, streamIdSub: Int, logInfo: String) {
        // Create a subscription at the given address and port, using the given stream ID.
        val subscriptionUri = uri("ipc", sessionIdSub, true)

        info = "Listening $logInfo [$sessionIdSub] [$streamIdSub]"

        success = true
        subscription = aeronDriver.addSubscription(subscriptionUri, logInfo, streamIdSub)
    }

    suspend fun close() {
        // on close, we want to make sure this file is DELETED!
        aeronDriver.closeAndDeleteSubscription(subscription, "HANDSHAKE-IPC")
    }

    override fun toString(): String {
        return info
    }
}
