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
import dorkbox.network.other.SuspendWaiter
import dorkbox.network.other.invokeSuspendFunction
import dorkbox.network.rmi.messages.MethodRequest
import kotlinx.coroutines.runBlocking
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.util.*
import kotlin.coroutines.Continuation


/**
 * Handles network communication when methods are invoked on a proxy. For NON-BLOCKING performance, the interface
 * must have the 'suspend' keyword added. If it is not present, then all method invocation will be BLOCKING.
 *
 *
 *
 * @param connection this is really the network client -- there is ONLY ever 1 connection
 * @param rmiSupport is used to provide RMI support
 * @param rmiObjectId this is the remote object ID (assigned by RMI). This is NOT the kryo registration ID
 * @param cachedMethods this is the methods available for the specified class
*/
class RmiClient(val isGlobal: Boolean,
                val rmiObjectId: Int,
                private val connection: Connection,
                private val proxyString: String,
                private val rmiSupportCache: RmiSupportCache,
                private val cachedMethods: Array<CachedMethod>) : InvocationHandler {

    companion object {
        private val methods = RmiUtils.getMethods(RemoteObject::class.java)

        private val closeMethod = methods.find { it.name == "close" }
        private val setResponseTimeoutMethod = methods.find { it.name == "setResponseTimeout" }
        private val getResponseTimeoutMethod = methods.find { it.name == "getResponseTimeout" }
        private val setAsyncMethod = methods.find { it.name == "setAsync" }
        private val getAsyncMethod = methods.find { it.name == "getAsync" }
        private val enableToStringMethod = methods.find { it.name == "enableToString" }
        private val enableWaitingForResponseMethod = methods.find { it.name == "enableWaitingForResponse" }
        private val waitForLastResponseMethod = methods.find { it.name == "waitForLastResponse" }
        private val getLastResponseIdMethod = methods.find { it.name == "getLastResponseId" }
        private val waitForResponseMethod = methods.find { it.name == "waitForResponse" }
        private val toStringMethod = methods.find { it.name == "toString" }

        @Suppress("UNCHECKED_CAST")
        private val EMPTY_ARRAY: Array<Any> = Collections.EMPTY_LIST.toTypedArray() as Array<Any>
    }

    private val responseWaiter = SuspendWaiter()

    private var timeoutMillis: Long = 3000
    private var isAsync = false
    private var allowWaiting = false
    private var enableToString = false

    // this is really a a short!
    @Volatile
    private var previousResponseId: Int = 0


    private suspend fun invokeSuspend(method: Method, args: Array<Any>): Any? {
        // there is a STRANGE problem, where if we DO NOT respond/reply to method invocation, and immediate invoke multiple methods --
        // the "server" side can have out-of-order method invocation. There are 2 ways to solve this
        //  1) make the "server" side single threaded
        //  2) make the "client" side wait for execution response (from the "server"). <--- this is what we are using.
        //
        // Because we have to ALWAYS make the client wait (unless 'isAsync' is true), we will always be returning, and will always have a
        // response (even if it is a void response). This simplifies our response mask, and lets us use more bits for storing the
        // response ID

        val responseStorage = rmiSupportCache.getResponseStorage()

        // If we are async, we ignore the response.... FOR NOW. The response, even if there is NOT one (ie: not void) will always return
        // a thing (so we will know when to stop blocking).
        val responseId = responseStorage.prep(rmiObjectId, responseWaiter)

        // so we can query for async, if we want to necessary
        previousResponseId = responseId


        val invokeMethod = MethodRequest()
        invokeMethod.isGlobal = isGlobal
        invokeMethod.objectId = rmiObjectId
        invokeMethod.responseId = responseId
        invokeMethod.args = args

        // which method do we access? We always want to access the IMPLEMENTATION (if available!). we know that this will always succeed
        // this should be accessed via the KRYO class ID + method index (both are SHORT, and can be packed)
        invokeMethod.cachedMethod = cachedMethods.first { it.method == method }

        connection.send(invokeMethod)

        // if we are async, then this will immediately return!
        return try {
            val result = responseStorage.waitForReply(allowWaiting, isAsync, rmiObjectId, responseId, responseWaiter, timeoutMillis)
            if (result is Exception) {
                throw result
            } else {
                result
            }
        } catch (ex: TimeoutException) {
            throw TimeoutException("Response timed out: ${method.declaringClass.name}.${method.name}")
        }
    }

    @Suppress("DuplicatedCode")
    @Throws(Exception::class)
    override fun invoke(proxy: Any, method: Method, args: Array<Any>?): Any? {
        if (method.declaringClass == RemoteObject::class.java) {
            // manage all of the RemoteObject proxy methods
            when (method) {
                closeMethod -> {
                    rmiSupportCache.removeProxyObject(rmiObjectId)
                    return null
                }
                setResponseTimeoutMethod -> {
                    timeoutMillis = (args!![0] as Int).toLong()
                    return null
                }
                getResponseTimeoutMethod -> {
                    return timeoutMillis.toInt()
                }
                getAsyncMethod -> {
                    return isAsync
                }
                setAsyncMethod -> {
                    isAsync = args!![0] as Boolean
                    return null
                }
                enableToStringMethod -> {
                    enableToString = args!![0] as Boolean
                    return null
                }
                getLastResponseIdMethod -> {
                    // only ASYNC can wait for responses
                    check(isAsync) { "This RemoteObject is not currently set to ASYNC mode. Unable to manually get the response ID." }
                    check(allowWaiting) { "This RemoteObject does not allow waiting for responses. You must enable this BEFORE " +
                            "calling the method that you want to wait for the respose to" }

                    return previousResponseId
                }
                enableWaitingForResponseMethod -> {
                    allowWaiting = args!![0] as Boolean
                    return null
                }
                waitForLastResponseMethod -> {
                    // only ASYNC can wait for responses
                    check(isAsync) { "This RemoteObject is not currently set to ASYNC mode. Unable to manually wait for a response." }
                    check(allowWaiting) { "This RemoteObject does not allow waiting for responses. You must enable this BEFORE " +
                            "calling the method that you want to wait for the respose to" }

                    val maybeContinuation = args?.lastOrNull() as Continuation<*>

                    // this is a suspend method, so we don't need extra checks
                    return invokeSuspendFunction(maybeContinuation) {
                        rmiSupportCache.getResponseStorage().waitForReplyManually(rmiObjectId, previousResponseId, responseWaiter)
                    }
                }
                waitForResponseMethod -> {
                    // only ASYNC can wait for responses
                    check(isAsync) { "This RemoteObject is not currently set to ASYNC mode. Unable to manually wait for a response." }
                    check(allowWaiting) { "This RemoteObject does not allow waiting for responses. You must enable this BEFORE " +
                            "calling the method that you want to wait for the respose to" }

                    val maybeContinuation = args?.lastOrNull() as Continuation<*>

                    // this is a suspend method, so we don't need extra checks
                    return invokeSuspendFunction(maybeContinuation) {
                        rmiSupportCache.getResponseStorage().waitForReplyManually(rmiObjectId, args[0] as Int, responseWaiter)
                    }
                }
                else -> throw Exception("Invocation handler could not find RemoteObject method for ${method.name}")
            }
        } else if (!enableToString && method == toStringMethod) {
            return proxyString
        }

        // if a 'suspend' function is called, then our last argument is a 'Continuation' object
        // We will use this for our coroutine context instead of running on a new coroutine
        val maybeContinuation = args?.lastOrNull()

        if (isAsync) {
            // return immediately, without suspends
            if (maybeContinuation is Continuation<*>) {
                val argsWithoutContinuation = args.take(args.size - 1)
                invokeSuspendFunction(maybeContinuation) {
                    invokeSuspend(method, argsWithoutContinuation.toTypedArray())
                }
            } else {
                runBlocking {
                    invokeSuspend(method, args ?: EMPTY_ARRAY)
                }
            }

            // if we are async then we return immediately. If you want the response value, you MUST use
            // 'waitForLastResponse()' or 'waitForResponse'('getLastResponseID()')
            val returnType = method.returnType
            if (returnType.isPrimitive) {
                return when (returnType) {
                    Int::class.javaPrimitiveType -> {
                        0
                    }
                    Boolean::class.javaPrimitiveType -> {
                        java.lang.Boolean.FALSE
                    }
                    Float::class.javaPrimitiveType -> {
                        0.0f
                    }
                    Char::class.javaPrimitiveType -> {
                        0.toChar()
                    }
                    Long::class.javaPrimitiveType -> {
                        0L
                    }
                    Short::class.javaPrimitiveType -> {
                        0.toShort()
                    }
                    Byte::class.javaPrimitiveType -> {
                        0.toByte()
                    }
                    Double::class.javaPrimitiveType -> {
                        0.0
                    }
                    else -> {
                        null
                    }
                }
            }
            return null
        } else {
            // non-async code, so we will be blocking/suspending!
            return if (maybeContinuation is Continuation<*>) {
                val argsWithoutContinuation = args.take(args.size - 1)
                invokeSuspendFunction(maybeContinuation) {
                    invokeSuspend(method, argsWithoutContinuation.toTypedArray())
                }
            } else {
                runBlocking {
                    invokeSuspend(method, args ?: EMPTY_ARRAY)
                }
            }
        }
    }

    override fun hashCode(): Int {
        val prime = 31
        var result = 1
        result = prime * result + rmiObjectId
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other == null) {
            return false
        }
        if (javaClass != other.javaClass) {
            return false
        }

        if (other !is RmiClient) {
            return false
        }

        return rmiObjectId == other.rmiObjectId
    }
}
