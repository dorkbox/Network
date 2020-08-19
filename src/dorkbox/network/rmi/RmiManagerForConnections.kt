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
import dorkbox.network.connection.EndPoint
import dorkbox.network.rmi.messages.ConnectionObjectCreateRequest
import dorkbox.network.rmi.messages.ConnectionObjectCreateResponse
import dorkbox.network.serialization.NetworkSerializationManager
import kotlinx.coroutines.CoroutineScope
import mu.KLogger

internal class RmiManagerForConnections(logger: KLogger,
                                        val rmiGlobalSupport: RmiMessageManager,
                                        private val serialization: NetworkSerializationManager,
                                        actionDispatch: CoroutineScope) : RmiObjectCache(logger, actionDispatch) {

    private fun <Iface> createProxyObject(isGlobalObject: Boolean,
                                  connection: Connection,
                                  endPoint: EndPoint<*>,
                                  objectId: Int,
                                  interfaceClass: Class<Iface>) : Iface {

        // so we can just instantly create the proxy object (or get the cached one)
        var proxyObject = getProxyObject(objectId)
        if (proxyObject == null) {
            proxyObject = RmiMessageManager.createProxyObject(isGlobalObject, connection, serialization, rmiGlobalSupport, endPoint.type.simpleName, objectId, interfaceClass)
            saveProxyObject(objectId, proxyObject)
        }

        @Suppress("UNCHECKED_CAST")
        return proxyObject as Iface
    }

    /**
     * on the connection+client to get a connection-specific remote object (that exists on the server/client)
     */
    fun <Iface> getRemoteObject(connection: Connection, endPoint: EndPoint<*>, objectId: Int, interfaceClass: Class<Iface>): Iface {
        // this immediately returns BECAUSE the object must have already been created on the server (this is why we specify the rmiId)!
        return createProxyObject(false, connection, endPoint, objectId, interfaceClass)
    }


    /**
     * on the "client" to create a connection-specific remote object (that exists on the server)
     */
    suspend fun <Iface> createRemoteObject(connection: Connection, interfaceClassId: Int, objectParameters: Array<Any?>?, callback: suspend (Int, Iface) -> Unit) {
        val callbackId = rmiGlobalSupport.registerCallback(callback)

        // There is no rmiID yet, because we haven't created it!
        val message = ConnectionObjectCreateRequest(RmiUtils.packShorts(interfaceClassId, callbackId), objectParameters)

        // We use a callback to notify us when the object is ready. We can't "create this on the fly" because we
        // have to wait for the object to be created + ID to be assigned on the remote system BEFORE we can create the proxy instance here.

        // this means we are creating a NEW object on the server
        connection.send(message)
    }

    /**
     * called on "server"
     */
    internal suspend fun onConnectionObjectCreateRequest(endPoint: EndPoint<*>, connection: Connection, message: ConnectionObjectCreateRequest, logger: KLogger) {

        val interfaceClassId = RmiUtils.unpackLeft(message.packedIds)
        val callbackId = RmiUtils.unpackRight(message.packedIds)
        val objectParameters = message.objectParameters

        // We have to lookup the iface, since the proxy object requires it
        val implObject = endPoint.serialization.createRmiObject(interfaceClassId, objectParameters)

        val response = if (implObject is Exception) {
            // whoops!
            logger.error("Unable to create remote object!", implObject)

            // we send the message ANYWAYS, because the client needs to know it did NOT succeed!
            ConnectionObjectCreateResponse(RmiUtils.packShorts(RemoteObjectStorage.INVALID_RMI, callbackId))
        } else {
            val rmiId = saveImplObject(implObject)

            if (rmiId != RemoteObjectStorage.INVALID_RMI) {
                // this means we could register this object.

                // next, scan this object to see if there are any RMI fields
                RmiMessageManager.scanImplForRmiFields(logger, implObject) {
                    saveImplObject(it)
                }
            } else {
                logger.error {
                    "Trying to create an RMI object with the INVALID_RMI id!!"
                }
            }

            // we send the message ANYWAYS, because the client needs to know it did NOT succeed!
            ConnectionObjectCreateResponse(RmiUtils.packShorts(rmiId, callbackId))
        }

        connection.send(response)
    }
}
