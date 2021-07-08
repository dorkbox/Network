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
import dorkbox.network.rmi.messages.*
import dorkbox.network.serialization.Serialization
import mu.KLogger
import java.lang.reflect.Proxy
import java.util.*

internal class RmiManagerGlobal<CONNECTION: Connection>(logger: KLogger) : RmiObjectCache(logger) {

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
        internal fun <CONNECTION : Connection> createProxyObject(
            isGlobalObject: Boolean,
            connection: CONNECTION,
            serialization: Serialization<CONNECTION>,
            responseManager: ResponseManager,
            kryoId: Int,
            rmiId: Int,
            interfaceClass: Class<*>
        ): RemoteObject {

            // duplicates are fine, as they represent the same object (as specified by the ID) on the remote side.

            val cachedMethods = serialization.getMethods(kryoId)

            val name = "<${connection.endPoint.type.simpleName}-proxy:$rmiId>"

            // the ACTUAL proxy is created in the connection impl. Our proxy handler MUST BE suspending because of:
            //  1) how we send data on the wire
            //  2) how we must (sometimes) wait for a response
            val proxyObject = RmiClient(isGlobalObject, rmiId, connection, name, responseManager, cachedMethods)

            // This is the interface inheritance by the proxy object
            val interfaces: Array<Class<*>> = arrayOf(RemoteObject::class.java, interfaceClass)

            return Proxy.newProxyInstance(RmiManagerGlobal::class.java.classLoader, interfaces, proxyObject) as RemoteObject
        }
    }

    /**
     * on the connection+client to get a global remote object (that exists on the server)
     *
     * NOTE: This must be cast correctly by the caller!
     */
    fun getGlobalRemoteObject(connection: CONNECTION, objectId: Int, interfaceClass: Class<*>): Any {
        // this immediately returns BECAUSE the object must have already been created on the server (this is why we specify the rmiId)!
        require(interfaceClass.isInterface) { "generic parameter must be an interface!" }

        @Suppress("UNCHECKED_CAST")
        val rmiConnectionSupport = connection.endPoint.rmiConnectionSupport as RmiManagerConnections<CONNECTION>

        // so we can just instantly create the proxy object (or get the cached one). This MUST be an object that is saved for the connection
        return rmiConnectionSupport.getProxyObject(true, connection, objectId, interfaceClass)
    }

    /**
     * Manages ALL OF THE RMI SCOPES
     */
    @Suppress("DuplicatedCode")
    suspend fun manage(
        serialization: Serialization<CONNECTION>,
        connection: CONNECTION,
        message: Any,
        rmiConnectionSupport: RmiManagerConnections<CONNECTION>,
        responseManager: ResponseManager,
        logger: KLogger
    ) {
        when (message) {
            is ConnectionObjectCreateRequest -> {
                /**
                 * called on "server"
                 */
                rmiConnectionSupport.onConnectionObjectCreateRequest(serialization, connection, message)
            }
            is ConnectionObjectCreateResponse -> {
                /**
                 * called on "client"
                 */
                rmiConnectionSupport.onConnectionObjectCreateResponse(connection, message)
            }
            is ConnectionObjectDeleteRequest -> {
                /**
                 * called on "client" or "server"
                 */
                rmiConnectionSupport.onConnectionObjectDeleteRequest(connection, message)
            }
            is ConnectionObjectDeleteResponse -> {
                /**
                 * called on "client" or "server"
                 */
                rmiConnectionSupport.onConnectionObjectDeleteResponse(connection, message)
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

                val implObject: Any? = if (isGlobal) {
                    getImplObject(rmiObjectId)
                } else {
                    rmiConnectionSupport.getImplObject(rmiObjectId)
                }

                if (implObject == null) {
                    logger.error("Connection ${connection.id}: Unable to resolve implementation object for [global=$isGlobal, objectID=$rmiObjectId, connection=$connection")
                    if (sendResponse) {
                        val rmiMessage = returnRmiMessage(
                            message,
                            NullPointerException("Remote object for proxy [global=$isGlobal, rmiObjectID=$rmiObjectId] does not exist."),
                            logger
                        )

                        connection.send(rmiMessage)
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
                            logger.error("Connection ${connection.id}", suspendResult)
                        }

                        if (sendResponse) {
                            val rmiMessage = returnRmiMessage(message, suspendResult, logger)
                            connection.send(rmiMessage)
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

                        RmiUtils.cleanStackTraceForImpl(result as Exception, false)
                        val fancyName = RmiUtils.makeFancyMethodName(cachedMethod)
                        logger.error("Error invoking method: $fancyName", result)
                    }

                    if (sendResponse) {
                        val rmiMessage = returnRmiMessage(message, result, logger)
                        connection.send(rmiMessage)
                    }
                }
            }
            is MethodResponse -> {
                // notify the pending proxy requests that we have a response!
                val rmiId = RmiUtils.unpackUnsignedRight(message.packedId)
                val result = message.result

                responseManager.notifyWaiter(rmiId, result, logger)
            }
        }
    }

    private fun returnRmiMessage(message: MethodRequest, result: Any?, logger: KLogger): MethodResponse {
        logger.trace { "RMI return. Send: ${RmiUtils.unpackUnsignedRight(message.packedId)}" }

        val rmiMessage = MethodResponse()
        rmiMessage.packedId = message.packedId
        rmiMessage.result = result

        return rmiMessage
    }
}
