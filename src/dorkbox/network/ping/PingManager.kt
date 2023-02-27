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


@file:Suppress("UNUSED_PARAMETER")

package dorkbox.network.ping

import dorkbox.network.connection.Connection
import dorkbox.network.rmi.ResponseManager
import kotlinx.coroutines.CoroutineScope
import mu.KLogger
import java.util.concurrent.*

/**
 * How to handle ping messages
 */
internal class PingManager<CONNECTION : Connection> {
    companion object {
        val DEFAULT_TIMEOUT_SECONDS = 30
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun manage(connection: CONNECTION, responseManager: ResponseManager, ping: Ping, logger: KLogger) {
        if (ping.pongTime == 0L) {
            ping.pongTime = System.currentTimeMillis()
            connection.send(ping)
        } else {
            ping.finishedTime = System.currentTimeMillis()

            val rmiId = ping.packedId

            // process the ping message so that our ping callback does something

            // this will be null if the ping took longer than XXX seconds and was cancelled
            val result = responseManager.getWaiterCallback<suspend Ping.() -> Unit>(rmiId, logger)
            if (result != null) {
                result(ping)
            }
        }
    }

    /**
     * Sends a "ping" packet to measure **ROUND TRIP** time to the remote connection.
     *
     * @return true if the message was successfully sent by aeron
     */
    internal suspend fun ping(
        connection: Connection,
        pingTimeoutSeconds: Int,
        eventDispatch: CoroutineScope,
        responseManager: ResponseManager,
        logger: KLogger,
        function: suspend Ping.() -> Unit
    ): Boolean {
        val id = responseManager.prepWithCallback(logger, function)

        val ping = Ping()
        ping.packedId = id
        ping.pingTime = System.currentTimeMillis()

        // ALWAYS cancel the ping after XXX seconds
        responseManager.cancelRequest(eventDispatch, TimeUnit.SECONDS.toMillis(pingTimeoutSeconds.toLong()), id, logger) {
            // kill the callback, since we are now "cancelled". If there is a race here (and the response comes at the exact same time)
            // we don't care since either it will be null or it won't (if it's not null, it will run the callback)
            result = null
        }

        return connection.send(ping)
    }
}
