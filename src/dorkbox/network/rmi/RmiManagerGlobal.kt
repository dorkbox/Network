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
import dorkbox.network.rmi.messages.*
import dorkbox.network.serialization.Serialization
import dorkbox.util.classes.ClassHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KLogger
import java.lang.Throwable
import java.lang.reflect.Proxy
import java.util.*

internal class RmiManagerGlobal<CONNECTION : Connection>(logger: KLogger,
                                                         actionDispatch: CoroutineScope,
                                                         private val serialization: Serialization) : RmiObjectCache(logger) {

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
                                       connection: Connection,
                                       serialization: Serialization,
                                       responseManager: ResponseManager,
                                       kryoId: Int,
                                       rmiId: Int,
                                       interfaceClass: Class<*>): RemoteObject {

            // duplicates are fine, as they represent the same object (as specified by the ID) on the remote side.

            val cachedMethods = serialization.getMethods(kryoId)

            val name = "<${connection.endPoint.type.simpleName}-proxy #$rmiId>"

            // the ACTUAL proxy is created in the connection impl. Our proxy handler MUST BE suspending because of:
            //  1) how we send data on the wire
            //  2) how we must (sometimes) wait for a response
            val proxyObject = RmiClient(isGlobalObject, rmiId, connection, name, responseManager, cachedMethods)

            // This is the interface inheritance by the proxy object
            val interfaces: Array<Class<*>> = arrayOf(RemoteObject::class.java, interfaceClass)

            return Proxy.newProxyInstance(RmiManagerGlobal::class.java.classLoader, interfaces, proxyObject) as RemoteObject
        }
    }

    internal val responseManager = ResponseManager(logger, actionDispatch)

    // this is used for all connection specific ones as well.
    private val remoteObjectCreationCallbacks = RemoteObjectStorage(logger)


    internal fun <Iface> registerCallback(callback: suspend Iface.() -> Unit): Int {
        return remoteObjectCreationCallbacks.register(callback)
    }

    private fun removeCallback(callbackId: Int): suspend Any.() -> Unit {
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
     * @return the removed object. If null, an error log will be emitted
     */
    fun <T> removeImplObject(endPoint: EndPoint<CONNECTION>, objectId: Int): T? {
        val success = removeImplObject<Any>(objectId)
        if (success == null) {
            val exception = Exception("Error trying to remove RMI impl object id $objectId.")
            ListenerManager.cleanStackTrace(exception)
            endPoint.listenerManager.notifyError(exception)
        }

        @Suppress("UNCHECKED_CAST")
        return success as T?
    }

    suspend fun close() {
        responseManager.close()
        remoteObjectCreationCallbacks.close()
    }


    /**
     * called on "client"
     */
    private fun onGenericObjectResponse(endPoint: EndPoint<CONNECTION>,
                                        connection: CONNECTION,
                                        isGlobal: Boolean,
                                        rmiId: Int,
                                        callback: suspend Any.() -> Unit,
                                        serialization: Serialization) {

        // we only create the proxy + execute the callback if the RMI id is valid!
        if (rmiId == RemoteObjectStorage.INVALID_RMI) {
            endPoint.listenerManager.notifyError(connection, Exception("RMI ID '${rmiId}' is invalid. Unable to create RMI object on server."))
            return
        }

        val interfaceClass = ClassHelper.getGenericParameterAsClassForSuperClass(RemoteObjectCallback::class.java, callback.javaClass, 0)

        // create the client-side proxy object, if possible.  This MUST be an object that is saved for the connection
        var proxyObject = connection.rmiConnectionSupport.getProxyObject(rmiId)
        if (proxyObject == null) {
            val kryoId = endPoint.serialization.getKryoIdForRmiClient(interfaceClass)
            proxyObject = createProxyObject(isGlobal, connection, serialization, responseManager, kryoId, rmiId, interfaceClass)
            connection.rmiConnectionSupport.saveProxyObject(rmiId, proxyObject)
        }

        // this should be executed on a NEW coroutine!
        endPoint.actionDispatch.launch {
            try {
                callback(proxyObject)
            } catch (e: Exception) {
                ListenerManager.cleanStackTrace(e)
                endPoint.listenerManager.notifyError(e)
            }
        }
    }

    /**
     * on the connection+client to get a global remote object (that exists on the server)
     */
    fun <Iface> getGlobalRemoteObject(connection: Connection, objectId: Int, interfaceClass: Class<Iface>): Iface {
        // this immediately returns BECAUSE the object must have already been created on the server (this is why we specify the rmiId)!
        require(interfaceClass.isInterface) { "iface must be an interface." }

        val kryoId = serialization.getKryoIdForRmiClient(interfaceClass)

        // so we can just instantly create the proxy object (or get the cached one). This MUST be an object that is saved for the connection
        var proxyObject = connection.rmiConnectionSupport.getProxyObject(objectId)
        if (proxyObject == null) {
            proxyObject = createProxyObject(true, connection, serialization, responseManager, kryoId, objectId, interfaceClass)
            connection.rmiConnectionSupport.saveProxyObject(objectId, proxyObject)
        }

        @Suppress("UNCHECKED_CAST")
        return proxyObject as Iface
    }

    /**
     * Manages ALL OF THE RMI stuff!
     */
    @Suppress("DuplicatedCode")
    suspend fun manage(endPoint: EndPoint<CONNECTION>, connection: CONNECTION, message: Any, logger: KLogger) {
        when (message) {
            is ConnectionObjectCreateRequest -> {
                /**
                 * called on "server"
                 */
                @Suppress("UNCHECKED_CAST")
                val rmiConnectionSupport: RmiManagerConnections<CONNECTION> = connection.rmiConnectionSupport as RmiManagerConnections<CONNECTION>
                rmiConnectionSupport.onConnectionObjectCreateRequest(endPoint, connection, message)
            }
            is ConnectionObjectCreateResponse -> {
                /**
                 * called on "client"
                 */
                val callbackId = RmiUtils.unpackLeft(message.packedIds)
                val rmiId = RmiUtils.unpackRight(message.packedIds)
                val callback = removeCallback(callbackId)
                onGenericObjectResponse(endPoint, connection, false, rmiId, callback, serialization)
            }
            is GlobalObjectCreateRequest -> {
                /**
                 * called on "server"
                 */
                onGlobalObjectCreateRequest(endPoint, connection, message)
            }
            is GlobalObjectCreateResponse -> {
                /**
                 * called on "client"
                 */
                val callbackId = RmiUtils.unpackLeft(message.packedIds)
                val rmiId = RmiUtils.unpackRight(message.packedIds)
                val callback = removeCallback(callbackId)
                onGenericObjectResponse(endPoint, connection, true, rmiId, callback, serialization)
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
                val sendResponse = rmiId != RemoteObjectStorage.ASYNC_RMI // async is always with a '1', and we should NOT send a message back if it is '1'

                logger.trace { "RMI received: $rmiId" }

                val implObject = getImplObject<Any>(isGlobal, rmiObjectId, connection)

                if (implObject == null) {
                    endPoint.listenerManager.notifyError(connection,
                                                         NullPointerException("Unable to resolve implementation object for [global=$isGlobal, objectID=$rmiObjectId, connection=$connection"))
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
                                (insideResult as Throwable).initCause(null)
                            }
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
                        } else if (suspendResult is Exception) {
                            RmiUtils.cleanStackTraceForImpl(suspendResult, true)
                            endPoint.listenerManager.notifyError(connection, suspendResult)
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
                            (result as Throwable).initCause(null)
                        }

                        RmiUtils.cleanStackTraceForImpl(result as Exception, false)
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
                responseManager.onRmiMessage(message)
            }
        }
    }

    private suspend fun returnRmiMessage(connection: Connection, message: MethodRequest, result: Any?, logger: KLogger) {
        logger.trace { "RMI return. Send: ${RmiUtils.unpackUnsignedRight(message.packedId)}" }

        val rmiMessage = MethodResponse()
        rmiMessage.packedId = message.packedId
        rmiMessage.result = result

        connection.send(rmiMessage)
    }

    /**
     * called on "server"
     */
    private suspend fun onGlobalObjectCreateRequest(endPoint: EndPoint<CONNECTION>,
                                                    connection: CONNECTION,
                                                    message: GlobalObjectCreateRequest) {
        val interfaceClassId = RmiUtils.unpackLeft(message.packedIds)
        val callbackId = RmiUtils.unpackRight(message.packedIds)
        val objectParameters = message.objectParameters
        val serialization = endPoint.serialization


        // We have to lookup the iface, since the proxy object requires it
        val implObject = serialization.createRmiObject(interfaceClassId, objectParameters)

        val response = if (implObject is Exception) {
            // whoops!
            endPoint.listenerManager.notifyError(connection, implObject)

            // we send the message ANYWAYS, because the client needs to know it did NOT succeed!
            GlobalObjectCreateResponse(RmiUtils.packShorts(callbackId, RemoteObjectStorage.INVALID_RMI))
        } else {
            val rmiId = saveImplObject(implObject)

            // we send the message ANYWAYS, because the client needs to know it did NOT succeed!
            GlobalObjectCreateResponse(RmiUtils.packShorts(callbackId, rmiId))
        }

        connection.send(response)
    }
}
