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
import dorkbox.network.connection.Connection
import dorkbox.network.connection.ListenerManager
import dorkbox.network.connection.ListenerManager.Companion.cleanStackTrace
import dorkbox.network.exceptions.RMIException
import dorkbox.network.rmi.messages.ConnectionObjectCreateRequest
import dorkbox.network.rmi.messages.ConnectionObjectCreateResponse
import dorkbox.network.rmi.messages.ConnectionObjectDeleteRequest
import dorkbox.network.rmi.messages.ConnectionObjectDeleteResponse
import dorkbox.network.serialization.Serialization
import org.slf4j.Logger

class RmiManagerConnections<CONNECTION: Connection> internal constructor(
    private val logger: Logger,
    private val responseManager: ResponseManager,
    private val listenerManager: ListenerManager<CONNECTION>,
    private val serialization: Serialization<CONNECTION>,
    private val getGlobalAction: (connection: CONNECTION, objectId: Int, interfaceClass: Class<*>) -> Any
) {

    /**
     * called on "server"
     */
    fun onConnectionObjectCreateRequest(serialization: Serialization<CONNECTION>, connection: CONNECTION, message: ConnectionObjectCreateRequest) {
        val callbackId = RmiUtils.unpackLeft(message.packedIds)
        val kryoId = RmiUtils.unpackRight(message.packedIds)
        val objectParameters = message.objectParameters

        // We have to lookup the iface, since the proxy object requires it
        val implObject = serialization.createRmiObject(kryoId, objectParameters)


        val response = if (implObject is Exception) {
            // whoops!
            implObject.cleanStackTrace()
            val newException = RMIException(implObject)
            listenerManager.notifyError(connection, newException)
            ConnectionObjectCreateResponse(RmiUtils.packShorts(callbackId, RemoteObjectStorage.INVALID_RMI))
        } else {
            val rmiId =  connection.rmi.saveImplObject(implObject)
            if (rmiId == RemoteObjectStorage.INVALID_RMI) {
                val newException = RMIException("Unable to create RMI object, invalid RMI ID")
                listenerManager.notifyError(connection, newException)
            }

            ConnectionObjectCreateResponse(RmiUtils.packShorts(callbackId, rmiId))
        }

        // we send the message ALWAYS, because the client needs to know it worked or not
        connection.send(response)
    }

    /**
     * called on "client"
     */
    fun onConnectionObjectCreateResponse(connection: CONNECTION, message: ConnectionObjectCreateResponse) {
        val callbackId = RmiUtils.unpackLeft(message.packedIds)
        val rmiId = RmiUtils.unpackRight(message.packedIds)

        // we only create the proxy + execute the callback if the RMI id is valid!
        if (rmiId == RemoteObjectStorage.INVALID_RMI) {
            val newException = RMIException("Unable to create RMI object, invalid RMI ID")
            listenerManager.notifyError(connection, newException)
            return
        }

        @Suppress("UNCHECKED_CAST")
        val rmi = connection.rmi as RmiSupportConnection<CONNECTION>

        val callback = rmi.removeCallback(callbackId)
        val interfaceClass = ClassHelper.getGenericParameterAsClassForSuperClass(RemoteObjectCallback::class.java, callback.javaClass, 0) ?: callback.javaClass

        // create the client-side proxy object, if possible.  This MUST be an object that is saved for the connection
        val proxyObject = rmi.getProxyObject(false, connection, rmiId, interfaceClass)

        // this should be executed on a NEW coroutine!
        try {
            callback(proxyObject)
        } catch (exception: Exception) {
            exception.cleanStackTrace()
            val newException = RMIException(exception)
            listenerManager.notifyError(connection, newException)
        }
    }

    /**
     * called on "client" or "server"
     */
    fun onConnectionObjectDeleteRequest(connection: CONNECTION, message: ConnectionObjectDeleteRequest) {
        val rmiId = message.rmiId

        // we only delete the impl object if the RMI id is valid!
        if (rmiId == RemoteObjectStorage.INVALID_RMI) {
            val newException = RMIException("Unable to delete RMI object, invalid RMI ID")
            listenerManager.notifyError(connection, newException)
            return
        }

        // it DOESN'T matter which "side" we are, just delete both (RMI id's must always represent the same object on both sides)
        connection.rmi.removeProxyObject(rmiId)
        connection.rmi.removeImplObject<Any?>(rmiId)

        // tell the "other side" to delete the proxy/impl object
        connection.send(ConnectionObjectDeleteResponse(rmiId))
    }


    /**
     * called on "client" or "server"
     */
    fun onConnectionObjectDeleteResponse(connection: CONNECTION, message: ConnectionObjectDeleteResponse) {
        val rmiId = message.rmiId

        // we only create the proxy + execute the callback if the RMI id is valid!
        if (rmiId == RemoteObjectStorage.INVALID_RMI) {
            val newException = RMIException("Unable to create RMI object, invalid RMI ID")
            listenerManager.notifyError(connection, newException)
            return
        }

        // it DOESN'T matter which "side" we are, just delete both (RMI id's must always represent the same object on both sides)
        connection.rmi.removeProxyObject(rmiId)
        connection.rmi.removeImplObject<Any?>(rmiId)
    }


    fun close(connection: CONNECTION) {
        connection.rmi.clear()
    }

    /**
     * Methods supporting Remote Method Invocation and Objects. A new one is created for each connection (because the connection is different for each one)
     */
    fun getNewRmiSupport(connection: Connection): RmiSupportConnection<CONNECTION> {
        @Suppress("UNCHECKED_CAST")
        return RmiSupportConnection(logger, connection as CONNECTION, responseManager, serialization, getGlobalAction)
    }
}
