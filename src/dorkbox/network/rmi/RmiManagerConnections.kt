/*
 * Copyright 2020 dorkbox, llc
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
import dorkbox.network.rmi.messages.ConnectionObjectCreateRequest
import dorkbox.network.rmi.messages.ConnectionObjectCreateResponse
import dorkbox.network.rmi.messages.ConnectionObjectDeleteRequest
import dorkbox.network.rmi.messages.ConnectionObjectDeleteResponse
import dorkbox.network.serialization.Serialization
import dorkbox.util.classes.ClassHelper
import dorkbox.util.collections.LockFreeIntMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KLogger

class RmiManagerConnections<CONNECTION: Connection> internal constructor(
    private val logger: KLogger,
    private val responseManager: ResponseManager,
    private val listenerManager: ListenerManager<CONNECTION>,
    private val serialization: Serialization<CONNECTION>,
    private val getGlobalAction: (connection: CONNECTION, objectId: Int, interfaceClass: Class<*>) -> Any
) : RmiObjectCache(logger) {

    // It is critical that all of the RMI proxy objects are unique, and are saved/cached PER CONNECTION. These cannot be shared between connections!
    private val proxyObjects = LockFreeIntMap<RemoteObject>()

    // callbacks for when a REMOTE object has been created
    private val remoteObjectCreationCallbacks = RemoteObjectStorage(logger)

    /**
     * Removes a proxy object from the system
     *
     * @return true if it successfully removed the object
     */
    fun removeProxyObject(rmiId: Int): Boolean {
        return proxyObjects.remove(rmiId) != null
    }

    private fun getProxyObject(rmiId: Int): RemoteObject? {
        return proxyObjects[rmiId]
    }

    private fun saveProxyObject(rmiId: Int, remoteObject: RemoteObject) {
        proxyObjects.put(rmiId, remoteObject)
    }

    internal fun <Iface> registerCallback(callback: suspend Iface.() -> Unit): Int {
        return remoteObjectCreationCallbacks.register(callback)
    }

    private fun removeCallback(callbackId: Int): suspend Any.() -> Unit {
        // callback's area always correct, because we track them ourselves.
        return remoteObjectCreationCallbacks.remove(callbackId)!!
    }



    /**
     * on the connection+client to get a connection-specific remote object (that exists on the server/client)
     */
    fun <Iface> getProxyObject(isGlobal: Boolean, connection: CONNECTION, rmiId: Int, interfaceClass: Class<Iface>): Iface {
        require(interfaceClass.isInterface) { "iface must be an interface." }

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
    fun <Iface> getProxyObject(isGlobal: Boolean, connection: CONNECTION, kryoId: Int, rmiId: Int, interfaceClass: Class<Iface>): Iface {
        require(interfaceClass.isInterface) { "iface must be an interface." }

        // so we can just instantly create the proxy object (or get the cached one)
        var proxyObject = getProxyObject(rmiId)
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


    /**
     * on the "client" to create a connection-specific remote object (that exists on the server)
     */
    suspend fun <Iface> createRemoteObject(connection: CONNECTION, kryoId: Int, objectParameters: Array<Any?>?, callback: suspend Iface.() -> Unit) {
        val callbackId = registerCallback(callback)

        // There is no rmiID yet, because we haven't created it!
        val message = ConnectionObjectCreateRequest(RmiUtils.packShorts(callbackId, kryoId), objectParameters)

        // We use a callback to notify us when the object is ready. We can't "create this on the fly" because we
        // have to wait for the object to be created + ID to be assigned on the remote system BEFORE we can create the proxy instance here.

        // this means we are creating a NEW object on the server
        connection.send(message)
    }

    /**
     * called on "server"
     */
    fun onConnectionObjectCreateRequest(
        serialization: Serialization<CONNECTION>,
        connection: CONNECTION,
        message: ConnectionObjectCreateRequest,
        actionDispatch: CoroutineScope
    ) {
        val callbackId = RmiUtils.unpackLeft(message.packedIds)
        val kryoId = RmiUtils.unpackRight(message.packedIds)
        val objectParameters = message.objectParameters

        // We have to lookup the iface, since the proxy object requires it
        val implObject = serialization.createRmiObject(kryoId, objectParameters)

        val response = if (implObject is Exception) {
            // whoops!
            ListenerManager.cleanStackTrace(implObject)
            logger.error("RMI error connection ${connection.id}", implObject)
            listenerManager.notifyError(connection, implObject)
            ConnectionObjectCreateResponse(RmiUtils.packShorts(callbackId, RemoteObjectStorage.INVALID_RMI))
        } else {
            val rmiId = saveImplObject(implObject)
            if (rmiId == RemoteObjectStorage.INVALID_RMI) {
                val exception = NullPointerException("Trying to create an RMI object with the INVALID_RMI id!!")
                ListenerManager.cleanStackTrace(exception)
                logger.error("RMI error connection ${connection.id}", exception)
                listenerManager.notifyError(connection, exception)
            }

            ConnectionObjectCreateResponse(RmiUtils.packShorts(callbackId, rmiId))
        }

        actionDispatch.launch {
            // we send the message ALWAYS, because the client needs to know it worked or not
            connection.send(response)
        }
    }

    /**
     * called on "client"
     */
    fun onConnectionObjectCreateResponse(
        connection: CONNECTION,
        message: ConnectionObjectCreateResponse,
        actionDispatch: CoroutineScope
    ) {
        val callbackId = RmiUtils.unpackLeft(message.packedIds)
        val rmiId = RmiUtils.unpackRight(message.packedIds)

        // we only create the proxy + execute the callback if the RMI id is valid!
        if (rmiId == RemoteObjectStorage.INVALID_RMI) {
            val exception = Exception("RMI ID '${rmiId}' is invalid. Unable to create RMI object on server.")
            ListenerManager.cleanStackTrace(exception)
            logger.error("RMI error connection ${connection.id}", exception)
            listenerManager.notifyError(connection, exception)
            return
        }

        val callback = removeCallback(callbackId)
        val interfaceClass = ClassHelper.getGenericParameterAsClassForSuperClass(RemoteObjectCallback::class.java, callback.javaClass, 0)

        // create the client-side proxy object, if possible.  This MUST be an object that is saved for the connection
        val proxyObject = getProxyObject(false, connection, rmiId, interfaceClass)

        // this should be executed on a NEW coroutine!
        actionDispatch.launch {
            try {
                callback(proxyObject)
            } catch (e: Exception) {
                ListenerManager.cleanStackTrace(e)
                logger.error("RMI error connection ${connection.id}", e)
                listenerManager.notifyError(connection, e)
            }
        }
    }

    /**
     * called on "client" or "server"
     */
    fun onConnectionObjectDeleteRequest(
        connection: CONNECTION,
        message: ConnectionObjectDeleteRequest,
        actionDispatch: CoroutineScope
    ) {
        val rmiId = message.rmiId

        // we only delete the impl object if the RMI id is valid!
        if (rmiId == RemoteObjectStorage.INVALID_RMI) {
            val exception = Exception("RMI ID '${rmiId}' is invalid. Unable to delete RMI object!")
            ListenerManager.cleanStackTrace(exception)
            logger.error("RMI error connection ${connection.id}", exception)
            listenerManager.notifyError(connection, exception)
            return
        }

        // it DOESN'T matter which "side" we are, just delete both (RMI id's must always represent the same object on both sides)
        removeProxyObject(rmiId)
        removeImplObject<Any?>(rmiId)

        actionDispatch.launch {
            // tell the "other side" to delete the proxy/impl object
            connection.send(ConnectionObjectDeleteResponse(rmiId))
        }
    }


    /**
     * called on "client" or "server"
     */
    fun onConnectionObjectDeleteResponse(connection: CONNECTION, message: ConnectionObjectDeleteResponse) {
        val rmiId = message.rmiId

        // we only create the proxy + execute the callback if the RMI id is valid!
        if (rmiId == RemoteObjectStorage.INVALID_RMI) {
            val exception = Exception("RMI ID '${rmiId}' is invalid. Unable to create RMI object on server.")
            ListenerManager.cleanStackTrace(exception)
            logger.error("RMI error connection ${connection.id}", exception)
            listenerManager.notifyError(connection, exception)
            return
        }

        // it DOESN'T matter which "side" we are, just delete both (RMI id's must always represent the same object on both sides)
        removeProxyObject(rmiId)
        removeImplObject<Any?>(rmiId)
    }


    fun close() {
        proxyObjects.clear()
        remoteObjectCreationCallbacks.close()
    }

    /**
     * Methods supporting Remote Method Invocation and Objects. A new one is created for each connection (because the connection is different for each one)
     */
    fun getNewRmiSupport(connection: Connection): RmiSupportConnection<CONNECTION> {
        @Suppress("LeakingThis", "UNCHECKED_CAST")
        return RmiSupportConnection(logger, connection as CONNECTION, this, serialization, getGlobalAction)
    }
}
