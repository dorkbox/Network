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
    internal var isNewSession = true
        private set
        get() {
            val orig = field
            if (orig) {
                field = false
            }
            return orig
        }


    fun restore(connection: CONNECTION) {
        this.connection = connection
        connection.logger.debug("restoring connection")

        lock.withLock {
            // this is called, even on a brand-new session, so we must have extra checks in place.
            val rmi = connection.rmi
            if (oldProxyObjects != null) {
                rmi.recreateProxyObjects(oldProxyObjects!!)
                oldProxyObjects = null
            }
            if (oldProxyCallbacks != null) {
                rmi.restoreCallbacks(oldProxyCallbacks!!)
                oldProxyCallbacks = null
            }
            if (oldImplObjects != null) {
                rmi.restoreImplObjects(oldImplObjects!!)
                oldImplObjects = null
            }
        }
    }

    fun save(connection: CONNECTION) {
        connection.logger.debug("saving connection")
        val allProxyObjects = connection.rmi.getAllProxyObjects()
        val allProxyCallbacks = connection.rmi.getAllCallbacks()
        val allImplObjects = connection.rmi.getAllImplObjects()

        // we want to save all the connection RMI objects, so they can be recreated on connect
        lock.withLock {
            oldProxyObjects = allProxyObjects
            oldProxyCallbacks = allProxyCallbacks
            oldImplObjects = allImplObjects
        }
    }

    fun queueMessage(connection: SessionConnection, message: Any, abortEarly: Boolean): Boolean {
        if (this.connection != connection) {
            // we received a message on an OLD connection (which is no longer connected ---- BUT we have a NEW connection that is connected)
            // this can happen on RMI object that are old
            val success = this.connection.send(message, abortEarly)
            if (success) {
                connection.logger.error("successfully resent message")
                return true
            }
        }

        if (!abortEarly) {
            // this was a "normal" send (instead of the disconnect message).
            pendingMessagesQueue.put(message)
            connection.logger.error("queueing message")
        }
        else if (connection.endPoint.aeronDriver.internal.mustRestartDriverOnError) {
            // the only way we get errors, is if the connection is bad OR if we are sending so fast that the connection cannot keep up.

            // don't restart/reconnect -- there was an internal network error
            pendingMessagesQueue.put(message)
            connection.logger.error("queueing message")
        }
        else if (!connection.isConnected()) {
            // there was an issue - the connection should automatically reconnect
            pendingMessagesQueue.put(message)
            connection.logger.error("queueing message")
        }

        connection.logger.error("NOT NOT NOT queueing message")
        return false
    }
}
