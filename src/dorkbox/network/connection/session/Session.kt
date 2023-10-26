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

import dorkbox.network.rmi.RemoteObject
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import java.util.concurrent.*

open class Session<CONNECTION: SessionConnection>(@Volatile var connection: CONNECTION) {


    // the RMI objects are saved when the connection is removed, and restored BEFORE the connection is initialized, so there are no concerns
    // regarding the collision of RMI IDs and objects
    private val lock = ReentrantLock()
    private var oldProxyObjects: List<RemoteObject<*>>? = null
    private var oldProxyCallbacks: List<Pair<Int, Any.(Int) -> Unit>>? = null
    private var oldImplObjects: List<Pair<Int, Any>>? = null

    /**
     * Only used when configured. Will re-send all missing messages to a connection when a connection re-connects.
     */
    val pendingMessagesQueue: LinkedTransferQueue<Any> = LinkedTransferQueue()


    /**
     * the FIRST time this method is called, it will be true. EVERY SUBSEQUENT TIME, it will be false
     */
    @Volatile
    internal var isNewSession = true
        private set
        get() {
            val orig = field
            if (orig) {
                field = false
                newSession  = false
            }
            return orig
        }


    @Volatile
    private var newSession = true


    fun restore(connection: CONNECTION) {
        this.connection = connection

        if (newSession) {
            connection.logger.debug("[{}] Session connection established", connection)
            return
        }

        connection.logger.debug("[{}] Restoring session connection", connection)

        lock.withLock {
            val oldProxyObjects = oldProxyObjects
            val oldProxyCallbacks = oldProxyCallbacks
            val oldImplObjects = oldImplObjects

            // this is called, even on a brand-new session, so we must have extra checks in place.
            val rmi = connection.rmi
            if (oldProxyObjects != null) {
                rmi.recreateProxyObjects(oldProxyObjects)
                this.oldProxyObjects = null
            }
            if (oldProxyCallbacks != null) {
                rmi.restoreCallbacks(oldProxyCallbacks)
                this.oldProxyCallbacks = null
            }
            if (oldImplObjects != null) {
                rmi.restoreImplObjects(oldImplObjects)
                this.oldImplObjects = null
            }
        }
    }

    fun save(connection: CONNECTION) {
        connection.logger.debug("[{}] Saving session connection", connection)

        val rmi = connection.rmi
        val allProxyObjects = rmi.getAllProxyObjects()
        val allProxyCallbacks = rmi.getAllCallbacks()
        val allImplObjects = rmi.getAllImplObjects()

        // we want to save all the connection RMI objects, so they can be recreated on connect
        lock.withLock {
            oldProxyObjects = allProxyObjects
            oldProxyCallbacks = allProxyCallbacks
            oldImplObjects = allImplObjects
        }
    }

    fun queueMessage(connection: SessionConnection, message: Any, abortEarly: Boolean): Boolean {
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
