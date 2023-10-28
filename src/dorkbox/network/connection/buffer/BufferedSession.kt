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

package dorkbox.network.connection.buffer

import dorkbox.network.connection.Connection
import java.util.concurrent.*

open class BufferedSession(@Volatile var connection: Connection) {
    /**
     * Only used when configured. Will re-send all missing messages to a connection when a connection re-connects.
     */
    val pendingMessagesQueue: LinkedTransferQueue<Any> = LinkedTransferQueue()

    fun queueMessage(connection: Connection, message: Any, abortEarly: Boolean): Boolean {
        if (this.connection != connection) {
            connection.logger.trace("[{}] message received on old connection, resending", connection)

            // we received a message on an OLD connection (which is no longer connected ---- BUT we have a NEW connection that is connected)
            // this can happen on RMI object that are old
            val success = this.connection.send(message, abortEarly)
            if (success) {
                connection.logger.trace("[{}] successfully resent message", connection)
                return true
            }
        }

        if (!abortEarly) {
            // this was a "normal" send (instead of the disconnect message).
            pendingMessagesQueue.put(message)
            connection.logger.trace("[{}] queueing message", connection)
        }
        else if (connection.endPoint.aeronDriver.internal.mustRestartDriverOnError) {
            // the only way we get errors, is if the connection is bad OR if we are sending so fast that the connection cannot keep up.

            // don't restart/reconnect -- there was an internal network error
            pendingMessagesQueue.put(message)
            connection.logger.trace("[{}] queueing message", connection)
        }
        else if (!connection.isClosedWithTimeout()) {
            // there was an issue - the connection should automatically reconnect
            pendingMessagesQueue.put(message)
            connection.logger.trace("[{}] queueing message", connection)
        }

        return false
    }
}
