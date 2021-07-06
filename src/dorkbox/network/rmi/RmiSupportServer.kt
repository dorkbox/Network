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

package dorkbox.network.rmi

import dorkbox.network.connection.Connection
import dorkbox.network.connection.ListenerManager
import mu.KLogger

/**
 * Only the server can create or delete a global object
 *
 * Regarding "scopes"
 *  GLOBAL      -> all connections/clients access the same object, and the state is shared
 *  CONNECTION  -> each object exists only within that specific connection, and only the corresponding remote connection has access to it's state.
 *
 *  Connection scope objects can be remotely created or deleted by either end of the connection. Only the server can create/delete a global scope object
 */
class RmiSupportServer<CONNECTION : Connection> internal constructor(
    private val logger: KLogger,
    private val rmiGlobalSupport: RmiManagerGlobal<CONNECTION>
) {
    /**
     * Tells us to save an existing object, GLOBALLY, so a remote connection can get it via [Connection.rmi.getGlobal()]
     *
     * Methods that return a value will throw [TimeoutException] if the response is not received with the response timeout [RemoteObject.responseTimeout].
     *
     * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side
     * will have the proxy object replaced with the registered (non-proxy) object.
     *
     * If one wishes to change the default behavior, cast the object to access the different methods.
     * ie:  `val remoteObject = test as RemoteObject`
     *
     * @return the newly registered RMI ID for this object. [RemoteObjectStorage.INVALID_RMI] means it was invalid (an error log will be emitted)
     *
     * @see RemoteObject
     */
    @Suppress("DuplicatedCode")
    fun save(`object`: Any): Int {
        val rmiId = rmiGlobalSupport.saveImplObject(`object`)
        if (rmiId == RemoteObjectStorage.INVALID_RMI) {
            val exception = Exception("RMI implementation '${`object`::class.java}' could not be saved! No more RMI id's could be generated")
            ListenerManager.cleanStackTrace(exception)
            logger.error("RMI error", exception)
        }
        return rmiId
    }

    /**
     * Tells us to save an existing object, GLOBALLY using the specified ID, so a remote connection can get it via [Connection.rmi.getGlobal()]
     *
     * Methods that return a value will throw [TimeoutException] if the response is not received with the response timeout [RemoteObject.responseTimeout].
     *
     * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side
     * will have the proxy object replaced with the registered (non-proxy) object.
     *
     * If one wishes to change the default behavior, cast the object to access the different methods.
     * ie:  `val remoteObject = test as RemoteObject`
     *
     * @return true if the object was successfully saved for the specified ID. If false, an error log will be emitted
     *
     * @see RemoteObject
     */
    @Suppress("DuplicatedCode")
    fun save(`object`: Any, objectId: Int): Boolean {
        val success = rmiGlobalSupport.saveImplObject(`object`, objectId)
        if (!success) {
            val exception = Exception("RMI implementation '${`object`::class.java}' could not be saved! No more RMI id's could be generated")
            ListenerManager.cleanStackTrace(exception)
            logger.error("RMI error", exception)
        }
        return success
    }

    /**
     * Tells us to delete a previously saved, GLOBAL scope, RMI object.
     *
     * After this call, this object will no longer be available to remote connections and the ID will be recycled (don't use it again)
     *
     * @return true if the object was successfully deleted. If false, an error log will be emitted
     *
     * @see RemoteObject
     */
    @Suppress("DuplicatedCode")
    fun delete(`object`: Any): Boolean {
        val successRmiId = rmiGlobalSupport.getId(`object`)
        val success = successRmiId != RemoteObjectStorage.INVALID_RMI

        if (success) {
            rmiGlobalSupport.removeImplObject<Any?>(successRmiId)
        } else {
            val exception = Exception("RMI implementation '${`object`::class.java}' could not be deleted! It does not exist")
            ListenerManager.cleanStackTrace(exception)
            logger.error("RMI error", exception)
        }

        return success
    }

    /**
     * Tells us to delete a previously saved, GLOBAL scope, RMI object.
     *
     * After this call, this object will no longer be available to remote connections and the ID will be recycled (don't use it again)
     *
     * @return true if the object was successfully deleted. If false, an error log will be emitted
     *
     * @see RemoteObject
     */
    @Suppress("DuplicatedCode")
    fun delete(objectId: Int): Boolean {
        val previousObject = rmiGlobalSupport.removeImplObject<Any?>(objectId)

        val success = previousObject != null
        if (!success) {
            val exception = Exception("RMI implementation UD '$objectId' could not be deleted! It does not exist")
            ListenerManager.cleanStackTrace(exception)
            logger.error("RMI error", exception)
        }
        return success
    }
}
