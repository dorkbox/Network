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

package dorkbox.network.connection.session

import dorkbox.network.connection.Connection
import dorkbox.network.connection.ConnectionParams

open class SessionConnection(connectionParameters: ConnectionParams<*>): Connection(connectionParameters) {
    @Volatile
    lateinit var session: Session<*>

    override fun send(message: Any, abortEarly: Boolean): Boolean {
        val success = super.send(message, abortEarly)
        if (!success) {
            return session.queueMessage(this, message, abortEarly)
        }

        return true
    }

    internal fun sendPendingMessages() {
        // now send all pending messages
        if (logger.isDebugEnabled) {
            logger.debug("Sending pending messages: ${session.pendingMessagesQueue.size}")
        }
        session.pendingMessagesQueue.forEach {
            super.send(it, false)
        }
    }
}
