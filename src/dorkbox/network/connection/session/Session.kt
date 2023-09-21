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

import dorkbox.hex.toHexString
import dorkbox.network.aeron.AeronDriver
import dorkbox.network.connection.Connection
import dorkbox.network.rmi.RemoteObject
import dorkbox.network.rmi.RmiClient
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import java.lang.reflect.Proxy
import java.util.concurrent.*

class Session<CONNECTION: Connection>(val aeronDriver: AeronDriver) {
    // the RMI objects are saved when the connection is removed, and restored BEFORE the connection is initialized, so there are no concerns
    // regarding the collision of RMI IDs and objects
    private val lock = ReentrantLock()
    private var oldProxyObjects: List<RemoteObject<*>>? = null
    private var oldImplObjects: List<Pair<Int, Any>>? = null

    /**
     * Only used when configured. Will re-send all missing messages to a connection when a connection re-connects.
     */
    internal val pendingMessagesQueue: LinkedTransferQueue<Any> = LinkedTransferQueue()

    @Volatile
    private var connection: CONNECTION? = null


    fun queueMessage(connection: SessionConnection, message: Any, abortEarly: Boolean): Boolean {
        val existingConnection = this.connection

        if (existingConnection != null && existingConnection != connection) {
            // we received a message on an OLD connection (which is no longer connected ---- BUT we have a NEW connection that is connected)
            val success = existingConnection.send(message, abortEarly)
            if (success) {
                return true
            }
        }


        if (!abortEarly) {
            // this was a "normal" send (instead of the disconnect message).
            pendingMessagesQueue.put(message)
        }
        else if (aeronDriver.internal.mustRestartDriverOnError) {
            // the only way we get errors, is if the connection is bad OR if we are sending so fast that the connection cannot keep up.

            // don't restart/reconnect -- there was an internal network error
            pendingMessagesQueue.put(message)
        }
        else if (existingConnection == null || !existingConnection.isConnected()) {
            // there was an issue - the connection should automatically reconnect
            pendingMessagesQueue.put(message)
        }

        // we couldn't send the message, but we did queue it if possible
        return false
    }

    fun restore(connection: CONNECTION) {
        connection.logger.error("RESTORING RMI OBJECTS for ${connection.uuid.toHexString()}")
        this.connection = connection

        lock.withLock {
            // this is called, even on a brand-new session, so we must have extra checks in place.
            val rmi = connection.rmi
            if (oldProxyObjects != null) {
                rmi.recreateProxyObjects(oldProxyObjects!!)
                oldProxyObjects = null
            }
            if (oldImplObjects != null) {
                rmi.restoreAllImplObjects(oldImplObjects!!)
                oldImplObjects = null
            }
        }

        // now send all pending messages
        connection.logger.error("Sending pending messages: ${pendingMessagesQueue.size}")
        pendingMessagesQueue.forEach {
            connection.send(it, false)
        }
    }

    fun save(connection: CONNECTION) {
        connection.logger.error("BACKING UP RMI OBJECTS: ${connection.uuid.toHexString()}")

        val allProxyObjects = connection.rmi.getAllProxyObjects()
        val allImplObjects = connection.rmi.getAllImplObjects()

        allProxyObjects.forEach {
            val rmiClient = Proxy.getInvocationHandler(it) as RmiClient
            val rmiId = rmiClient.rmiObjectId

            connection.logger.error("PROXY: $rmiId")
        }

        allImplObjects.forEach {
            connection.logger.error("IMPL: ${it.first} : ${it.second.javaClass}")
        }

        // we want to save all the connection RMI objects, so they can be recreated on connect
        lock.withLock {
            oldProxyObjects = allProxyObjects
            oldImplObjects = allImplObjects
        }
    }
}
