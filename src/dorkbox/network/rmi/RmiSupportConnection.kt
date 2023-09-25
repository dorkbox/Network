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

package dorkbox.network.rmi

import dorkbox.classUtil.ClassHelper
import dorkbox.collections.LockFreeIntMap
import dorkbox.network.connection.Connection
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTrace
import dorkbox.network.rmi.messages.ConnectionObjectCreateRequest
import dorkbox.network.rmi.messages.ConnectionObjectDeleteRequest
import dorkbox.network.serialization.Serialization
import org.slf4j.Logger
import java.lang.reflect.Proxy

/**
 * Only the server can create or delete a global object
 *
 * Regarding "scopes"
 *  GLOBAL      -> all connections/clients access the same object, and the state is shared
 *  CONNECTION  -> each object exists only within that specific connection, and only the corresponding remote connection has access to it's state.
 *
 *  Connection scope objects can be remotely created or deleted by either end of the connection. Only the server can create/delete a global scope object
 */
class RmiSupportConnection<CONNECTION: Connection> : RmiObjectCache {
    private val connection: CONNECTION
    private val responseManager: ResponseManager
    val serialization: Serialization<CONNECTION>
    private val getGlobalAction: (connection: CONNECTION, objectId: Int, interfaceClass: Class<*>) -> Any

    internal constructor(
        logger: Logger,
        connection: CONNECTION,
        responseManager: ResponseManager,
        serialization: Serialization<CONNECTION>,
        getGlobalAction: (connection: CONNECTION, objectId: Int, interfaceClass: Class<*>) -> Any
    ) : super(logger) {
        this.connection = connection
        this.responseManager = responseManager
        this.serialization = serialization
        this.getGlobalAction = getGlobalAction
        this.proxyObjects = LockFreeIntMap<RemoteObject<*>>()
        this.remoteObjectCreationCallbacks = RemoteObjectStorage(logger)
    }

    // It is critical that all of the RMI proxy objects are unique, and are saved/cached PER CONNECTION. These cannot be shared between connections!
    private val proxyObjects: LockFreeIntMap<RemoteObject<*>>

    // callbacks for when a REMOTE object has been created
    private val remoteObjectCreationCallbacks: RemoteObjectStorage

    /**
     * Removes a proxy object from the system
     *
     * @return true if it successfully removed the object
     */
    internal fun removeProxyObject(rmiId: Int): Boolean {
        return proxyObjects.remove(rmiId) != null
    }

    private fun getProxyObject(rmiId: Int): RemoteObject<*>? {
        return proxyObjects[rmiId]
    }

    private fun saveProxyObject(rmiId: Int, remoteObject: RemoteObject<*>) {
        proxyObjects.put(rmiId, remoteObject)
    }

    private fun <Iface> registerCallback(callback: Iface.(Int) -> Unit): Int {
        return remoteObjectCreationCallbacks.register(callback)
    }

    internal fun removeCallback(callbackId: Int): Any.(Int) -> Unit {
        // callback's area always correct, because we track them ourselves.
        return remoteObjectCreationCallbacks.remove(callbackId)!!
    }

    internal fun getAllCallbacks(): List<Pair<Int, Any.(Int) -> Unit>> {
        @Suppress("UNCHECKED_CAST")
        return remoteObjectCreationCallbacks.getAll() as List<Pair<Int, Any.(Int) -> Unit>>
    }

    internal fun restoreCallbacks(oldProxyCallbacks: List<Pair<Int, Any.(Int) -> Unit>>) {
        remoteObjectCreationCallbacks.restoreAll(oldProxyCallbacks)
    }

    /**
     * @return all the RMI proxy objects used by this connection. This is used by session management in order to preserve RMI functionality.
     */
    internal fun getAllProxyObjects(): List<RemoteObject<*>> {
        return proxyObjects.values.toList()
    }

    /**
     * Recreate all the proxy objects for this connection. This is used by session management in order to preserve RMI functionality.
     */
    internal fun recreateProxyObjects(oldProxyObjects: List<RemoteObject<*>>) {
        oldProxyObjects.forEach {
            // the interface we care about is ALWAYS the second one!
            val iface = it.javaClass.interfaces[1]

            val kryoId = connection.endPoint.serialization.getKryoIdForRmiClient(iface)
            val rmiClient = Proxy.getInvocationHandler(it) as RmiClient
            val rmiId = rmiClient.rmiObjectId

            val proxyObject = RmiManagerGlobal.createProxyObject(
                rmiClient.isGlobal,
                connection,
                serialization,
                responseManager,
                kryoId, rmiId,
                iface
            )

            saveProxyObject(rmiId, proxyObject)
        }
    }



    /**
     * Tells us to save an existing object in the CONNECTION scope, so a remote connection can get it via [Connection.rmi.get()]
     *
     * NOTE:: Methods can throw [TimeoutException] if the response is not received with the response timeout [RemoteObject.responseTimeout].
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
        val rmiId = saveImplObject(`object`)
        if (rmiId == RemoteObjectStorage.INVALID_RMI) {
            val exception = Exception("RMI implementation '${`object`::class.java}' could not be saved! No more RMI id's could be generated")
            exception.cleanStackTrace()
            logger.error("RMI error connection ${connection.id}", exception)
        }

        return rmiId
    }

    /**
     * Tells us to save an existing object in the CONNECTION scope using the specified ID, so a remote connection can get it via [Connection.rmi.get()]
     *
     * NOTE:: Methods can throw [TimeoutException] if the response is not received with the response timeout [RemoteObject.responseTimeout].
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
        val success = saveImplObject(`object`, objectId)
        if (!success) {
            val exception = Exception("RMI implementation '${`object`::class.java}' could not be saved! No more RMI id's could be generated")
            exception.cleanStackTrace()
            logger.error("RMI error connection ${connection.id}", exception)
        }
        return success
    }

    /**
     * Creates create a new proxy object where the implementation exists in a remote connection.
     *
     * We use a callback to notify us when the object is ready. We can't "create this on the fly" because we
     * have to wait for the object to be created + ID to be assigned on the remote system BEFORE we can create the proxy instance here.
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
    fun <Iface> create(vararg objectParameters: Any?, callback: Iface.(rmiId: Int) -> Unit) {
        val iFaceClass = ClassHelper.getGenericParameterAsClassForSuperClass(Function1::class.java, callback.javaClass, 0) ?: callback.javaClass
        val kryoId = serialization.getKryoIdForRmiClient(iFaceClass)

        @Suppress("UNCHECKED_CAST")
        objectParameters as Array<Any?>

        val callbackId = registerCallback(callback)

        // There is no rmiID yet, because we haven't created it!
        val message = ConnectionObjectCreateRequest(RmiUtils.packShorts(callbackId, kryoId), objectParameters)

        connection.send(message)
    }

    /**
     * Creates create a new proxy object where the implementation exists in a remote connection.
     *
     * We use a callback to notify us when the object is ready. We can't "create this on the fly" because we
     * have to wait for the object to be created + ID to be assigned on the remote system BEFORE we can create the proxy instance here.
     *
     * NOTE:: Methods can throw [TimeoutException] if the response is not received with the response timeout [RemoteObject.responseTimeout].
     *
     * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side
     * will have the proxy object replaced with the registered (non-proxy) object.
     *
     * If one wishes to change the default behavior, cast the object to access the different methods.
     * ie:  `val remoteObject = RemoteObject.cast(obj)`
     *
     * @see RemoteObject
     */
    fun <Iface> create(callback: Iface.(rmiId: Int) -> Unit) {
        val iFaceClass = ClassHelper.getGenericParameterAsClassForSuperClass(Function1::class.java, callback.javaClass, 0) ?: callback.javaClass
        val kryoId = serialization.getKryoIdForRmiClient(iFaceClass)

        val callbackId = registerCallback(callback)

        // There is no rmiID yet, because we haven't created it!
        val message = ConnectionObjectCreateRequest(RmiUtils.packShorts(callbackId, kryoId), null)

        connection.send(message)
    }

    /**
     * This will remove both the proxy AND implementation objects. It does not matter which "side" of a connection this is called on.
     *
     * Any future method invocations will result in a error.
     *
     * Future '.get' requests will succeed, as they do not check the existence of the implementation object (methods called on it will fail)
     */
    fun delete(rmiObjectId: Int) {
        // we only create the proxy + execute the callback if the RMI id is valid!
        if (rmiObjectId == RemoteObjectStorage.INVALID_RMI) {
            val exception = Exception("Unable to delete RMI object!")
            exception.cleanStackTrace()
            logger.error("RMI error connection ${connection.id}", exception)
            return
        }


        // it DOESN'T matter which "side" we are, just delete both (RMI id's must always represent the same object on both sides)
        removeProxyObject(rmiObjectId)
        removeImplObject<Any?>(rmiObjectId)


        // ALWAYS send a message because we don't know if we are the "client" or the "server" - and we want ALL sides cleaned up
        connection.send(ConnectionObjectDeleteRequest(rmiObjectId))
    }

    /**
     * Gets a CONNECTION scope remote object via the ID.
     *
     * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side
     * will have the proxy object replaced with the registered (non-proxy) object.
     *
     *NOTE:: Methods can throw [TimeoutException] if the response is not received with the response timeout [RemoteObject.responseTimeout].
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
        return getProxyObject(false, connection, objectId, Iface::class.java)
    }

    /**
     * Gets a GLOBAL scope object via the ID. Global remote objects share their state among all connections.
     *
     * NOTE:: Methods can throw [TimeoutException] if the response is not received with the response timeout [RemoteObject.responseTimeout].
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




    /**
     * on the connection+client to get a connection-specific remote object (that exists on the server/client)
     */
    internal fun <Iface> getProxyObject(isGlobal: Boolean, connection: CONNECTION, rmiId: Int, interfaceClass: Class<Iface>): Iface {
        require(interfaceClass.isInterface) { "'interfaceClass' must be an interface!" }

        // so we can just instantly create the proxy object (or get the cached one)
        var proxyObject = getProxyObject(rmiId)
        if (proxyObject == null) {
            val kryoId = connection.endPoint.serialization.getKryoIdForRmiClient(interfaceClass)

            proxyObject = RmiManagerGlobal.createProxyObject(isGlobal,
                                                             connection,
                                                             serialization,
                                                             responseManager,
                                                             kryoId,
                                                             rmiId,
                                                             interfaceClass)
            saveProxyObject(rmiId, proxyObject)
        }

        // this immediately returns BECAUSE the object must have already been created on the server (this is why we specify the rmiId)!
        @Suppress("UNCHECKED_CAST")
        return proxyObject as Iface
    }

    /**
     * on the connection+client to get a connection-specific remote object (that exists on the server/client)
     */
    internal fun <Iface> getProxyObject(isGlobal: Boolean, connection: CONNECTION, kryoId: Int, rmiId: Int, interfaceClass: Class<Iface>): Iface {
        require(interfaceClass.isInterface) { "'interfaceClass' must be an interface!" }

        // so we can just instantly create the proxy object (or get the cached one)
        @Suppress("UNCHECKED_CAST")
        var proxyObject = getProxyObject(rmiId) as RemoteObject<Iface>?
        if (proxyObject == null) {
            proxyObject = RmiManagerGlobal.createProxyObject(isGlobal,
                                                             connection,
                                                             serialization,
                                                             responseManager,
                                                             kryoId,
                                                             rmiId,
                                                             interfaceClass)
            saveProxyObject(rmiId, proxyObject)
        }

        // this immediately returns BECAUSE the object must have already been created on the server (this is why we specify the rmiId)!
        @Suppress("UNCHECKED_CAST")
        return proxyObject as Iface
    }

    internal fun clear() {
        proxyObjects.clear()
        remoteObjectCreationCallbacks.close()
    }
}
