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
import dorkbox.network.connection.ListenerManager
import dorkbox.network.rmi.messages.ConnectionObjectCreateRequest
import dorkbox.network.rmi.messages.ConnectionObjectCreateResponse
import dorkbox.network.rmi.messages.GlobalObjectCreateRequest
import dorkbox.network.rmi.messages.GlobalObjectCreateResponse
import dorkbox.network.rmi.messages.MethodRequest
import dorkbox.network.rmi.messages.MethodResponse
import dorkbox.network.serialization.NetworkSerializationManager
import dorkbox.util.classes.ClassHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KLogger
import java.lang.reflect.Proxy
import java.util.*

internal class RmiMessageManager(logger: KLogger,
                                 actionDispatch: CoroutineScope,
                                 internal val serialization: NetworkSerializationManager) : RmiObjectCache(logger, actionDispatch) {
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
                                       rmiObjectCache: RmiObjectCache, namePrefix: String,
                                       rmiId: Int, interfaceClass: Class<*>): RemoteObject {

            require(interfaceClass.isInterface) { "iface must be an interface." }

            // duplicates are fine, as they represent the same object (as specified by the ID) on the remote side.

            val classId = serialization.getClassId(interfaceClass)
            val cachedMethods = serialization.getMethods(classId)

            val name = "<${namePrefix}-proxy #$rmiId>"

            // the ACTUAL proxy is created in the connection impl. Our proxy handler MUST BE suspending because of:
            //  1) how we send data on the wire
            //  2) how we must (sometimes) wait for a response
            val proxyObject = RmiClient(isGlobalObject, rmiId, connection, name, rmiObjectCache, cachedMethods)

            // This is the interface inheritance by the proxy object
            val interfaces: Array<Class<*>> = arrayOf(RemoteObject::class.java, interfaceClass)

            return Proxy.newProxyInstance(RmiMessageManager::class.java.classLoader, interfaces, proxyObject) as RemoteObject
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
        private fun onGenericObjectResponse(endPoint: EndPoint<*>, connection: Connection, logger: KLogger,
                                            isGlobal: Boolean, rmiId: Int, callback: suspend (Int, Any) -> Unit,
                                            rmiObjectCache: RmiObjectCache, serialization: NetworkSerializationManager) {

            // we only create the proxy + execute the callback if the RMI id is valid!
            if (rmiId == RemoteObjectStorage.INVALID_RMI) {
                logger.error {
                    "RMI ID '${rmiId}' is invalid. Unable to create RMI object on server."
                }
                return
            }

            val interfaceClass = ClassHelper.getGenericParameterAsClassForSuperClass(RemoteObjectCallback::class.java, callback.javaClass, 1)

            // create the client-side proxy object, if possible
            var proxyObject = rmiObjectCache.getProxyObject(rmiId)
            if (proxyObject == null) {
                proxyObject = createProxyObject(isGlobal, connection, serialization, rmiObjectCache, endPoint.type.simpleName, rmiId, interfaceClass)
                rmiObjectCache.saveProxyObject(rmiId, proxyObject)
            }

            // this should be executed on a NEW coroutine!
            endPoint.actionDispatch.launch {
                try {
                    callback(rmiId, proxyObject)
                } catch (e: Exception) {
                    logger.error("Error getting or creating the remote object $interfaceClass", e)
                }
            }
        }
    }

    // this is used for all connection specific ones as well.
    private val remoteObjectCreationCallbacks = RemoteObjectStorage(logger)


    internal fun <Iface> registerCallback(callback: suspend (Int, Iface) -> Unit): Int {
        return remoteObjectCreationCallbacks.register(callback)
    }

    private fun removeCallback(callbackId: Int): suspend (Int, Any) -> Unit {
        // callback's area always correct, because we track them ourselves.
        return remoteObjectCreationCallbacks.remove(callbackId)!!
    }

    /**
     * @return the implementation object based on if it is global, or not global
     */
    fun <T> getImplObject(isGlobal: Boolean, rmiId: Int, connection: Connection): T? {
        return if (isGlobal) getImplObject(rmiId) else connection.rmiConnectionSupport.getImplObject(rmiId)
    }

    /**
     * @return the newly registered RMI ID for this object. [RemoteObjectStorage.INVALID_RMI] means it was invalid (an error log will be emitted)
     */
    fun saveImplObject(logger: KLogger, `object`: Any): Int {
        val rmiId = saveImplObject(`object`)

        if (rmiId != RemoteObjectStorage.INVALID_RMI) {
            // this means we could register this object.

            // next, scan this object to see if there are any RMI fields
            scanImplForRmiFields(logger, `object`) {
                saveImplObject(it)
            }
        } else {
            logger.error("Trying to create an RMI object with the INVALID_RMI id!!")
        }

        return rmiId
    }

    /**
     * @return the true if it was a success saving this object. False means it was invalid (an error log will be emitted)
     */
    fun saveImplObject(logger: KLogger, `object`: Any, objectId: Int): Boolean {
        val rmiSuccess = saveImplObject(`object`, objectId)

        if (rmiSuccess) {
            // this means we could register this object.

            // next, scan this object to see if there are any RMI fields
            scanImplForRmiFields(logger, `object`) {
                saveImplObject(it)
            }
        } else {
            logger.error("Trying to save an RMI object ${`object`.javaClass} with invalid id $objectId")
        }

        return rmiSuccess
    }

    /**
     * @return the removed object. If null, an error log will be emitted
     */
    fun <T> removeImplObject(logger: KLogger, objectId: Int): T? {
        val success = removeImplObject<Any>(objectId)
        if (success == null) {
            logger.error("Error trying to remove an RMI impl object id $objectId.")
        }

        @Suppress("UNCHECKED_CAST")
        return success as T?
    }

    override fun close() {
        super.close()
        remoteObjectCreationCallbacks.close()
    }

    /**
     * on the connection+client to get a global remote object (that exists on the server)
     */
    fun <Iface> getGlobalRemoteObject(connection: Connection, endPoint: EndPoint<*>, objectId: Int, interfaceClass: Class<Iface>): Iface {
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
     * Manages ALL OF THE RMI stuff!
     */
    @Suppress("DuplicatedCode")
    @Throws(IllegalArgumentException::class)
    suspend fun manage(endPoint: EndPoint<*>, connection: Connection, message: Any, logger: KLogger) {
        when (message) {
            is ConnectionObjectCreateRequest -> {
                /**
                 * called on "server"
                 */
                connection.rmiConnectionSupport.onConnectionObjectCreateRequest(endPoint, connection, message, logger)
            }
            is ConnectionObjectCreateResponse -> {
                /**
                 * called on "client"
                 */
                val callbackId = RmiUtils.unpackLeft(message.packedIds)
                val rmiId = RmiUtils.unpackRight(message.packedIds)
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
                val callbackId = RmiUtils.unpackLeft(message.packedIds)
                val rmiId = RmiUtils.unpackRight(message.packedIds)
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
                val isGlobal = message.isGlobal
                val isCoroutine = message.isCoroutine
                val rmiObjectId = RmiUtils.unpackLeft(message.packedId)
                val rmiId = RmiUtils.unpackUnsignedRight(message.packedId)
                val cachedMethod = message.cachedMethod
                val args = message.args
                val sendResponse = rmiId != 1 // async is always with a '1', and we should NOT send a message back if it is '1'

                logger.trace { "RMI received: $rmiId" }

                val implObject = getImplObject<Any>(isGlobal, rmiObjectId, connection)

                if (implObject == null) {
                    logger.error("Unable to resolve implementation object for [global=$isGlobal, objectID=$rmiObjectId, connection=$connection")

                    if (sendResponse) {
                        returnRmiMessage(connection,
                                         message,
                                         NullPointerException("Remote object for proxy [global=$isGlobal, rmiObjectID=$rmiObjectId] does not exist."),
                                         logger)
                    }
                    return
                }

                logger.trace {
                    var argString = ""
                    if (args != null) {
                        argString = Arrays.deepToString(args)
                        argString = argString.substring(1, argString.length - 1)
                    }

                    val stringBuilder = StringBuilder(128)
                    stringBuilder.append(connection.toString()).append(" received: ").append(implObject.javaClass.simpleName)
                    stringBuilder.append(":").append(rmiObjectId)
                    stringBuilder.append("#").append(cachedMethod.method.name)
                    stringBuilder.append("(").append(argString).append(")")

                    if (cachedMethod.overriddenMethod != null) {
                        // did we override our cached method? THIS IS NOT COMMON.
                        stringBuilder.append(" [Connection method override]")
                    }
                    stringBuilder.toString()
                }

                var result: Any?

                if (isCoroutine) {
                    // https://stackoverflow.com/questions/47654537/how-to-run-suspend-method-via-reflection
                    // https://discuss.kotlinlang.org/t/calling-coroutines-suspend-functions-via-reflection/4672

                    var suspendResult = kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { cont ->
                        // if we are a coroutine, we have to replace the LAST arg with the coroutine object
                        // we KNOW this is OK, because a continuation arg will always be there!
                        args!![args.size - 1] = cont

                        var insideResult: Any?
                        try {
                            // args!! is safe to do here (even though it doesn't make sense)
                            insideResult = cachedMethod.invoke(connection, implObject, args)
                        } catch (ex: Exception) {
                            insideResult = ex.cause
                            // added to prevent a stack overflow when references is false, (because 'cause' == "this").
                            // See:
                            // https://groups.google.com/forum/?fromgroups=#!topic/kryo-users/6PDs71M1e9Y
                            if (insideResult == null) {
                                insideResult = ex
                            }
                            else {
                                insideResult.initCause(null)
                            }

                            ListenerManager.cleanStackTraceReverse(insideResult as Throwable)
                            val fancyName = RmiUtils.makeFancyMethodName(cachedMethod)
                            logger.error("Error invoking method: $fancyName", insideResult)
                        }
                        insideResult
                    }


                    if (suspendResult === kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) {
                        // we were suspending, and the stack will resume when possible, then it will call the response below
                    }
                    else {
                        if (suspendResult === Unit) {
                            // kotlin suspend returns, that DO NOT have a return value, REALLY return kotlin.Unit. This means there is no
                            // return value!
                            suspendResult = null
                        }

                        if (sendResponse) {
                            returnRmiMessage(connection, message, suspendResult, logger)
                        }
                    }
                }
                else {
                    // not a suspend (coroutine) call
                    try {
                        // args!! is safe to do here (even though it doesn't make sense)
                        result = cachedMethod.invoke(connection, implObject, message.args!!)
                    } catch (ex: Exception) {
                        result = ex.cause
                        // added to prevent a stack overflow when references is false, (because 'cause' == "this").
                        // See:
                        // https://groups.google.com/forum/?fromgroups=#!topic/kryo-users/6PDs71M1e9Y
                        if (result == null) {
                            result = ex
                        }
                        else {
                            result.initCause(null)
                        }

                        ListenerManager.cleanStackTraceReverse(result as Throwable)
                        val fancyName = RmiUtils.makeFancyMethodName(cachedMethod)
                        logger.error("Error invoking method: $fancyName", result)
                    }

                    if (sendResponse) {
                        returnRmiMessage(connection, message, result, logger)
                    }
                }
            }
            is MethodResponse -> {
                // notify the pending proxy requests that we have a response!
                getResponseStorage().onMessage(message)
            }
        }
    }

    private suspend fun returnRmiMessage(connection: Connection, message: MethodRequest, result: Any?, logger: KLogger) {
        logger.trace { "RMI returned: ${RmiUtils.unpackUnsignedRight(message.packedId)}" }

        val rmiMessage = MethodResponse()
        rmiMessage.packedId = message.packedId
        rmiMessage.result = result

        connection.send(rmiMessage)
    }

    /**
     * called on "server"
     */
    private suspend fun onGlobalObjectCreateRequest(endPoint: EndPoint<*>, connection: Connection, message: GlobalObjectCreateRequest, logger: KLogger) {
        val interfaceClassId = RmiUtils.unpackLeft(message.packedIds)
        val callbackId = RmiUtils.unpackRight(message.packedIds)
        val objectParameters = message.objectParameters


        // We have to lookup the iface, since the proxy object requires it
        val implObject = endPoint.serialization.createRmiObject(interfaceClassId, objectParameters)

        val response = if (implObject is Exception) {
            // whoops!
            logger.error("Unable to create remote object!", implObject)

            // we send the message ANYWAYS, because the client needs to know it did NOT succeed!
            GlobalObjectCreateResponse(RmiUtils.packShorts(callbackId, RemoteObjectStorage.INVALID_RMI))
        } else {
            val rmiId = saveImplObject(logger, implObject)

            // we send the message ANYWAYS, because the client needs to know it did NOT succeed!
            GlobalObjectCreateResponse(RmiUtils.packShorts(callbackId, rmiId))
        }

        connection.send(response)
    }
}
