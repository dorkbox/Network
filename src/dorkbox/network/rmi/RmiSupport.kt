/*
 * Copyright 2019 dorkbox, llc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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
import dorkbox.network.connection.Connection_
import dorkbox.network.connection.EndPoint
import dorkbox.network.rmi.messages.*
import dorkbox.network.serialization.NetworkSerializationManager
import dorkbox.util.classes.ClassHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KLogger
import java.lang.reflect.Proxy
import java.util.*

class RmiSupport<C : Connection>(logger: KLogger,
                                 actionDispatch: CoroutineScope,
                                 internal val serialization: NetworkSerializationManager) : RmiSupportCache(logger, actionDispatch)
{
    companion object {
        /**
         * Returns a proxy object that implements the specified interface, and the methods invoked on the proxy object will be invoked
         * remotely.
         *
         * Methods that return a value will throw [TimeoutException] if the response is not received with the [RemoteObject.responseTimeout].
         *
         *
         * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side will
         * have the proxy object replaced with the registered object.
         *
         * @see RemoteObject
         *
         * @param rmiId this is the remote object ID (assigned by RMI). This is NOT the kryo registration ID
         * @param interfaceClass this is the RMI interface class
         */
        internal fun createProxyObject(isGlobalObject: Boolean,
                                       connection: Connection, serialization: NetworkSerializationManager,
                                       rmiSupportCache: RmiSupportCache, namePrefix: String,
                                       rmiId: Int, interfaceClass: Class<*>): RemoteObject {

            require(interfaceClass.isInterface) { "iface must be an interface." }

            // duplicates are fine, as they represent the same object (as specified by the ID) on the remote side.

            val classId = serialization.getClassId(interfaceClass)
            val cachedMethods = serialization.getMethods(classId)

            val name = "<${namePrefix}-proxy #$rmiId>"

            // the ACTUAL proxy is created in the connection impl. Our proxy handler MUST BE suspending because of:
            //  1) how we send data on the wire
            //  2) how we must (sometimes) wait for a response
            val proxyObject = RmiClient(isGlobalObject, rmiId, connection, name, rmiSupportCache, cachedMethods)

            // This is the interface inheritance by the proxy object
            val interfaces: Array<Class<*>> = arrayOf(RemoteObject::class.java, interfaceClass)

            return Proxy.newProxyInstance(RmiSupport::class.java.classLoader, interfaces, proxyObject) as RemoteObject
        }

        /**
         * Scans a class (+hierarchy) for @Rmi annotation and executes the 'registerAction' with it
         */
        internal fun scanImplForRmiFields(logger: KLogger, implObject: Any, registerAction: (fieldObject: Any) -> Unit) {
            val implementationClass = implObject::class.java

            // the @Rmi annotation allows an RMI object to have fields with objects that are ALSO RMI objects
            val classesToCheck = LinkedList<Map.Entry<Class<*>, Any?>>()
            classesToCheck.add(AbstractMap.SimpleEntry(implementationClass, implObject))


            var remoteClassObject: Map.Entry<Class<*>, Any?>
            while (!classesToCheck.isEmpty()) {
                remoteClassObject = classesToCheck.removeFirst()

                // we have to check the IMPLEMENTATION for any additional fields that will have proxy information.
                // we use getDeclaredFields() + walking the object hierarchy, so we get ALL the fields possible (public + private).

                for (field in remoteClassObject.key.declaredFields) {
                    if (field.getAnnotation(Rmi::class.java) != null) {
                        val type = field.type
                        if (!type.isInterface) {
                            // the type must be an interface, otherwise RMI cannot create a proxy object
                            logger.error("Error checking RMI fields for: {}.{} -- It is not an interface!",
                                    remoteClassObject.key,
                                    field.name)
                            continue
                        }
//TODO FIX THIS. MAYBE USE KOTLIN TO DO THIS?
                        val prev = field.isAccessible
                        field.isAccessible = true

                        val o: Any
                        try {
                            o = field[remoteClassObject.value]
                            registerAction(o)
                            classesToCheck.add(AbstractMap.SimpleEntry(type, o))
                        } catch (e: IllegalAccessException) {
                            logger.error("Error checking RMI fields for: {}.{}", remoteClassObject.key, field.name, e)
                        } finally {
                            field.isAccessible = prev
                        }
                    }
                }

                // have to check the object hierarchy as well
                val superclass = remoteClassObject.key.superclass
                if (superclass != null && superclass != Any::class.java) {
                    classesToCheck.add(AbstractMap.SimpleEntry(superclass, remoteClassObject.value))
                }
            }
        }

        /**
         * called on "client"
         */
        private fun onGenericObjectResponse(endPoint: EndPoint<Connection_>, connection: Connection_, logger: KLogger,
                                            isGlobal: Boolean, rmiId: Int, callback: suspend (Any) -> Unit,
                                            rmiSupportCache: RmiSupportCache, serialization: NetworkSerializationManager) {

            // we only create the proxy + execute the callback if the RMI id is valid!
            if (rmiId == RemoteObjectStorage.INVALID_RMI) {
                logger.error {
                    "RMI ID '${rmiId}' is invalid. Unable to create RMI object on server."
                }
                return
            }

            val interfaceClass = ClassHelper.getGenericParameterAsClassForSuperClass(RemoteObjectCallback::class.java, callback.javaClass, 0)

            // create the client-side proxy object, if possible
            var proxyObject = rmiSupportCache.getProxyObject(rmiId)
            if (proxyObject == null) {
                proxyObject = createProxyObject(isGlobal, connection, serialization, rmiSupportCache, endPoint.type.simpleName, rmiId, interfaceClass)
                rmiSupportCache.saveProxyObject(rmiId, proxyObject)
            }

            // this should be executed on a NEW coroutine!
            endPoint.actionDispatch.launch {
                try {
                    callback(proxyObject)
                } catch (e: Exception) {
                    logger.error("Error getting or creating the remote object $interfaceClass", e)
                }
            }
        }
    }

    // this is used for all connection specific ones as well.
    private val remoteObjectCreationCallbacks = RemoteObjectStorage(logger)

    internal fun <Iface> registerCallback(callback: suspend (Iface) -> Unit): Int {
        return remoteObjectCreationCallbacks.register(callback)
    }

    private fun removeCallback(callbackId: Int): suspend (Any) -> Unit {
        return remoteObjectCreationCallbacks.remove(callbackId)
    }

    /**
     * Get's the implementation object based on if it is global, or not global
     */
    fun getImplObject(isGlobal: Boolean, rmiObjectId: Int, connection: Connection_): Any {
        return if (isGlobal) getImplObject(rmiObjectId) else connection.rmiSupport().getImplObject(rmiObjectId)
    }

    override fun close() {
        super.close()
        remoteObjectCreationCallbacks.close()
    }

    /**
     * on the "client" to get a global remote object (that exists on the server)
     */
    fun <Iface> getGlobalRemoteObject(connection: C, endPoint: EndPoint<C>, objectId: Int, interfaceClass: Class<Iface>): Iface {
        // this immediately returns BECAUSE the object must have already been created on the server (this is why we specify the rmiId)!

        // so we can just instantly create the proxy object (or get the cached one)
        var proxyObject = getProxyObject(objectId)
        if (proxyObject == null) {
            proxyObject = createProxyObject(true, connection, serialization, this, endPoint.type.simpleName, objectId, interfaceClass)
            saveProxyObject(objectId, proxyObject)
        }

        @Suppress("UNCHECKED_CAST")
        return proxyObject as Iface
    }

    /**
     * on the "client" to create a global remote object (that exists on the server)
     */
    suspend fun <Iface> createGlobalRemoteObject(connection: Connection, interfaceClassId: Int, callback: suspend (Iface) -> Unit) {
        val callbackId = registerCallback(callback)

        // There is no rmiID yet, because we haven't created it!
        val message = GlobalObjectCreateRequest(RmiUtils.packShorts(interfaceClassId, callbackId))

        // We use a callback to notify us when the object is ready. We can't "create this on the fly" because we
        // have to wait for the object to be created + ID to be assigned on the remote system BEFORE we can create the proxy instance here.

        // this means we are creating a NEW object on the server
        connection.send(message)
    }



    /**
     * Manages ALL OF THE RMI stuff!
     */
    @Throws(IllegalArgumentException::class)
    suspend fun manage(endPoint: EndPoint<Connection_>, connection: Connection_, message: Any, logger: KLogger) {
        when (message) {
            is ConnectionObjectCreateRequest -> {
                /**
                 * called on "server"
                 */
                connection.rmiSupport().onConnectionObjectCreateRequest(endPoint, connection, message, logger)
            }
            is ConnectionObjectCreateResponse -> {
                /**
                 * called on "client"
                 */
                val rmiId = RmiUtils.unpackLeft(message.packedIds)
                val callbackId = RmiUtils.unpackRight(message.packedIds)
                val callback = removeCallback(callbackId)
                onGenericObjectResponse(endPoint, connection, logger, false, rmiId, callback, this, serialization)
            }
            is GlobalObjectCreateRequest -> {
                /**
                 * called on "server"
                 */
                onGlobalObjectCreateRequest(endPoint, connection, message, logger)
            }
            is GlobalObjectCreateResponse -> {
                /**
                 * called on "client"
                 */
                val rmiId = RmiUtils.unpackLeft(message.packedIds)
                val callbackId = RmiUtils.unpackRight(message.packedIds)
                val callback = removeCallback(callbackId)
                onGenericObjectResponse(endPoint, connection, logger, true, rmiId, callback, this, serialization)
            }
            is MethodRequest -> {
                /**
                 * Invokes the method on the object and, sends the result back to the connection that made the invocation request.
                 *
                 * This is the response to the invoke method in the RmiClient
                 *
                 * The remote side of this connection requested the invocation.
                 */
                val objectId: Int = message.objectId
                val isGlobal: Boolean = message.isGlobal
                val cachedMethod = message.cachedMethod

                val implObject = getImplObject(isGlobal, objectId, connection)

                logger.trace {
                    var argString = ""
                    if (message.args != null) {
                        argString = Arrays.deepToString(message.args)
                        argString = argString.substring(1, argString.length - 1)
                    }

                    val stringBuilder = StringBuilder(128)
                    stringBuilder.append(connection.toString())
                            .append(" received: ")
                            .append(implObject.javaClass.simpleName)
                    stringBuilder.append(":").append(objectId)
                    stringBuilder.append("#").append(cachedMethod.method.name)
                    stringBuilder.append("(").append(argString).append(")")

                    if (cachedMethod.overriddenMethod != null) {
                        // did we override our cached method? THIS IS NOT COMMON.
                        stringBuilder.append(" [Connection method override]")
                    }
                    stringBuilder.toString()
                }


                var result: Any?
                try {
                    // args!! is safe to do here (even though it doesn't make sense)
                    result = cachedMethod.invoke(connection, implObject, message.args!!)
                } catch (ex: Exception) {
                    logger.error("Error invoking method: ${cachedMethod.method.declaringClass.name}.${cachedMethod.method.name}", ex)

                    result = ex.cause
                    // added to prevent a stack overflow when references is false, (because 'cause' == "this").
                    // See:
                    // https://groups.google.com/forum/?fromgroups=#!topic/kryo-users/6PDs71M1e9Y
                    if (result == null) {
                        result = ex
                    } else {
                        result.initCause(null)
                    }
                }

                val invokeMethodResult = MethodResponse()
                invokeMethodResult.objectId = objectId
                invokeMethodResult.responseId = message.responseId
                invokeMethodResult.result = result

                connection.send(invokeMethodResult)
            }
            is MethodResponse -> {
                // notify the pending proxy requests that we have a response!
                getResponseStorage().onMessage(message)
            }
        }
    }

    /**
     * called on "server"
     */
    private suspend fun onGlobalObjectCreateRequest(
            endPoint: EndPoint<Connection_>, connection: Connection_, message: GlobalObjectCreateRequest, logger: KLogger) {

        val interfaceClassId = RmiUtils.unpackLeft(message.packedIds)
        val callbackId = RmiUtils.unpackRight(message.packedIds)

        // We have to lookup the iface, since the proxy object requires it
        val implObject = endPoint.serialization.createRmiObject(interfaceClassId)
        val rmiId = registerImplObject(implObject)

        if (rmiId != RemoteObjectStorage.INVALID_RMI) {
            // this means we could register this object.

            // next, scan this object to see if there are any RMI fields
            scanImplForRmiFields(logger, implObject) {
                registerImplObject(implObject)
            }
        } else {
            logger.error {
                "Trying to create an RMI object with the INVALID_RMI id!!"
            }
        }

        // we send the message ANYWAYS, because the client needs to know it did NOT succeed!
        connection.send(GlobalObjectCreateResponse(RmiUtils.packShorts(rmiId, callbackId)))
    }
}
