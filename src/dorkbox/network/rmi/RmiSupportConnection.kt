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
import dorkbox.network.rmi.messages.ConnectionObjectDeleteRequest
import dorkbox.network.serialization.Serialization
import dorkbox.util.classes.ClassHelper
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
class RmiSupportConnection<CONNECTION: Connection> internal constructor(
    private val logger: KLogger,
    private val connection: CONNECTION,
    private val rmiConnectionSupport: RmiManagerConnections<CONNECTION>,
    private val serialization: Serialization<CONNECTION>,
    private val getGlobalAction: (connection: CONNECTION, objectId: Int, interfaceClass: Class<*>) -> Any
) {

    /**
     * Tells us to save an existing object in the CONNECTION scope, so a remote connection can get it via [Connection.rmi.get()]
     *
     * Methods that return a value will throw [TimeoutException] if the response is not received with the response timeout [RemoteObject.responseTimeout].
     *
     * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side
     * will have the proxy object replaced with the registered (non-proxy) object.
     *
     * If one wishes to change the default behavior, cast the object to access the different methods.
     * ie:  `val remoteObject = test as RemoteObject`
     *
     *
     * @return the newly registered RMI ID for this object. [RemoteObjectStorage.INVALID_RMI] means it was invalid (an error log will be emitted)
     *
     * @see RemoteObject
     */
    @Suppress("DuplicatedCode")
    fun save(`object`: Any): Int {
        val rmiId = rmiConnectionSupport.saveImplObject(`object`)
        if (rmiId == RemoteObjectStorage.INVALID_RMI) {
            val exception = Exception("RMI implementation '${`object`::class.java}' could not be saved! No more RMI id's could be generated")
            ListenerManager.cleanStackTrace(exception)
            logger.error("RMI error connection ${connection.id}", exception)
        }

        return rmiId
    }

    /**
     * Tells us to save an existing object in the CONNECTION scope using the specified ID, so a remote connection can get it via [Connection.rmi.get()]
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
        val success = rmiConnectionSupport.saveImplObject(`object`, objectId)
        if (!success) {
            val exception = Exception("RMI implementation '${`object`::class.java}' could not be saved! No more RMI id's could be generated")
            ListenerManager.cleanStackTrace(exception)
            logger.error("RMI error connection ${connection.id}", exception)
        }
        return success
    }

    /**
     * Creates create a new proxy object where the implementation exists in a remote connection.
     *
     * The callback will be notified when the remote object has been created.
     *
     * Methods that return a value will throw [TimeoutException] if the response is not received with the response timeout [RemoteObject.responseTimeout].
     *
     * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side
     * will have the proxy object replaced with the registered (non-proxy) object.
     *
     * If one wishes to change the default behavior, cast the object to access the different methods.
     * ie:  `val remoteObject = test as RemoteObject`
     *
     * @see RemoteObject
     */
    suspend fun <Iface> create(vararg objectParameters: Any?, callback: suspend Iface.() -> Unit) {
        val iFaceClass = ClassHelper.getGenericParameterAsClassForSuperClass(Function1::class.java, callback.javaClass, 0)
        val kryoId = serialization.getKryoIdForRmiClient(iFaceClass)

        @Suppress("UNCHECKED_CAST")
        objectParameters as Array<Any?>

        rmiConnectionSupport.createRemoteObject(connection, kryoId, objectParameters, callback)
    }

    /**
     * Creates create a new proxy object where the implementation exists in a remote connection.
     *
     * The callback will be notified when the remote object has been created.
     *
     * Methods that return a value will throw [TimeoutException] if the response is not received with the response timeout [RemoteObject.responseTimeout].
     *
     * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side
     * will have the proxy object replaced with the registered (non-proxy) object.
     *
     * If one wishes to change the default behavior, cast the object to access the different methods.
     * ie:  `val remoteObject = test as RemoteObject`
     *
     * @see RemoteObject
     */
    suspend fun <Iface> create(callback: suspend Iface.() -> Unit) {
        val iFaceClass = ClassHelper.getGenericParameterAsClassForSuperClass(Function1::class.java, callback.javaClass, 0)
        val kryoId = serialization.getKryoIdForRmiClient(iFaceClass)

        rmiConnectionSupport.createRemoteObject(connection, kryoId, null, callback)
    }

    /**
     * This will remove both the proxy AND implementation objects. It does not matter which "side" of a connection this is called on.
     *
     * Any future method invocations will result in a error.
     *
     * Future '.get' requests will succeed, as they do not check the existence of the implementation object (methods called on it will fail)
     */
    suspend fun delete(rmiObjectId: Int) {
        // we only create the proxy + execute the callback if the RMI id is valid!
        if (rmiObjectId == RemoteObjectStorage.INVALID_RMI) {
            val exception = Exception("RMI ID '${rmiObjectId}' is invalid. Unable to delete RMI object!")
            ListenerManager.cleanStackTrace(exception)
            logger.error("RMI error connection ${connection.id}", exception)
            return
        }

        // ALWAYS send a message because we don't know if we are the "client" or the "server" - and we want ALL sides cleaned up
        connection.send(ConnectionObjectDeleteRequest(rmiObjectId))
    }

    /**
     * Gets a CONNECTION scope remote object via the ID.
     *
     * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side
     * will have the proxy object replaced with the registered (non-proxy) object.
     *
     * If one wishes to change the remote object behavior, cast the object to a [RemoteObject] to access the different methods, for example:
     * ie:  `val remoteObject = test as RemoteObject`
     *
     * @see RemoteObject
     */
    inline fun <reified Iface> get(objectId: Int): Iface {
        // NOTE: It's not possible to have reified inside a virtual function
        // https://stackoverflow.com/questions/60037849/kotlin-reified-generic-in-virtual-function

        @Suppress("NON_PUBLIC_CALL_FROM_PUBLIC_INLINE")
        return rmiConnectionSupport.getProxyObject(false, connection, objectId, Iface::class.java)
    }

    /**
     * Gets a GLOBAL scope object via the ID. Global remote objects share their state among all connections.
     *
     * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side
     * will have the proxy object replaced with the registered (non-proxy) object.
     *
     * If one wishes to change the remote object behavior, cast the object to a [RemoteObject] to access the different methods, for example:
     * ie:  `val remoteObject = test as RemoteObject`
     *
     * @see RemoteObject
     */
    inline fun <reified Iface> getGlobal(objectId: Int): Iface {
        // NOTE: It's not possible to have reified inside a virtual function
        // https://stackoverflow.com/questions/60037849/kotlin-reified-generic-in-virtual-function

        @Suppress("NON_PUBLIC_CALL_FROM_PUBLIC_INLINE")
        return getGlobalAction(connection, objectId, Iface::class.java) as Iface
    }
}
