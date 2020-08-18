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
import dorkbox.network.other.coroutines.SuspendFunctionTrampoline
import dorkbox.network.rmi.messages.MethodRequest
import kotlinx.coroutines.runBlocking
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.util.*
import kotlin.coroutines.Continuation


/**
 * Handles network communication when methods are invoked on a proxy.
 *
 * For NON-BLOCKING performance, the RMI interface must have the 'suspend' keyword added. If this keyword is not
 * present, then all method invocation will be BLOCKING.
 *
 * @param isGlobal true if this is a global object, or false if it is connection specific
 * @param rmiObjectId this is the remote object ID (assigned by RMI). This is NOT the kryo registration ID
 * @param connection this is really the network client -- there is ONLY ever 1 connection
 * @param proxyString this is the name assigned to the proxy [toString] method
 * @param rmiObjectCache is used to provide RMI support
 * @param cachedMethods this is the methods available for the specified class
*/
internal class RmiClient(val isGlobal: Boolean,
                         val rmiObjectId: Int,
                         private val connection: Connection,
                         private val proxyString: String,
                         private val rmiObjectCache: RmiObjectCache,
                         private val cachedMethods: Array<CachedMethod>) : InvocationHandler {

    companion object {
        private val methods = RmiUtils.getMethods(RemoteObject::class.java)

        private val closeMethod = methods.find { it.name == "close" }
        private val toStringMethod = methods.find { it.name == "toString" }
        private val hashCodeMethod = methods.find { it.name == "hashCode" }
        private val equalsMethod = methods.find { it.name == "equals" }

        private val enableToStringMethod = methods.find { it.name == "enableToString" }
        private val enableHashCodeMethod = methods.find { it.name == "enableHashCode" }
        private val enableEqualsMethod = methods.find { it.name == "enableEquals" }


        private val setResponseTimeoutMethod = methods.find { it.name == "setResponseTimeout" }
        private val getResponseTimeoutMethod = methods.find { it.name == "getResponseTimeout" }
        private val setAsyncMethod = methods.find { it.name == "setAsync" }
        private val getAsyncMethod = methods.find { it.name == "getAsync" }

        @Suppress("UNCHECKED_CAST")
        private val EMPTY_ARRAY: Array<Any> = Collections.EMPTY_LIST.toTypedArray() as Array<Any>
    }

    private var timeoutMillis: Long = 3000
    private var isAsync = false

    private var enableToString = false
    private var enableHashCode = false
    private var enableEquals = false

    // if we are ASYNC, then this method immediately returns
    private suspend fun sendRequest(method: Method, args: Array<Any>): Any? {
        // there is a STRANGE problem, where if we DO NOT respond/reply to method invocation, and immediate invoke multiple methods --
        // the "server" side can have out-of-order method invocation. There are 2 ways to solve this
        //  1) make the "server" side single threaded
        //  2) make the "client" side wait for execution response (from the "server"). <--- this is what we are using.
        //
        // Because we have to ALWAYS make the client wait (unless 'isAsync' is true), we will always be returning, and will always have a
        // response (even if it is a void response). This simplifies our response mask, and lets us use more bits for storing the
        // response ID

        val responseStorage = rmiObjectCache.getResponseStorage()

        // NOTE: we ALWAYS send a response from the remote end.
        //
        // 'async' -> DO NOT WAIT
        // 'timeout > 0' -> WAIT
        // 'timeout == 0' -> same as async (DO NOT WAIT)
        val isAsync = isAsync || timeoutMillis <= 0L


        // If we are async, we ignore the response....
        // The response, even if there is NOT one (ie: not void) will always return a thing (so our code excution is in lockstep
        val rmiWaiter = responseStorage.prep(isAsync)

        val invokeMethod = MethodRequest()
        invokeMethod.isGlobal = isGlobal
        invokeMethod.packedId = RmiUtils.packShorts(rmiObjectId, rmiWaiter.id)
        invokeMethod.args = args // if this is a kotlin suspend function, the suspend arg will NOT be here!

        // which method do we access? We always want to access the IMPLEMENTATION (if available!). we know that this will always succeed
        // this should be accessed via the KRYO class ID + method index (both are SHORT, and can be packed)
        invokeMethod.cachedMethod = cachedMethods.first { it.method == method }

        connection.send(invokeMethod)


        // if we are async, then this will immediately return
        @Suppress("MoveVariableDeclarationIntoWhen")
        val result = responseStorage.waitForReply(isAsync, rmiWaiter, timeoutMillis)
        when (result) {
            RmiResponseManager.TIMEOUT_EXCEPTION -> {
                // TODO: from top down, clean up the coroutine stack
                throw TimeoutException("Response timed out: ${method.declaringClass.name}.${method.name}(${method.parameterTypes.map{it.simpleName}})")
            }
            is Exception -> {
                // reconstruct the stack trace, so the calling method knows where the method invocation happened, and can trace the call
                // this stack will ALWAYS run up to this method (so we remove from the top->down, to get to the call site)

                val stackTrace = Exception().stackTrace
                val myClassName = RmiClient::class.java.name

                var newStartIndex = 0
                for (element in stackTrace) {
                    newStartIndex++

                    if (element.className == myClassName && element.methodName == "invoke") {
                        // we do this 1 more time, because we want to remove the proxy invocation off the stack as well.
                        newStartIndex++
                        break
                    }
                }

                val newStack = Array<StackTraceElement>(result.stackTrace.size + stackTrace.size - newStartIndex) { stackTrace[0] }
                result.stackTrace.copyInto(newStack)
                stackTrace.copyInto(newStack, result.stackTrace.size, newStartIndex)

                result.stackTrace = newStack
                throw result
            }
            else -> {
                return result
            }
        }
    }

    @Suppress("DuplicatedCode")
    /**
     * @throws Exception
     */
    override fun invoke(proxy: Any, method: Method, args: Array<Any>?): Any? {
        if (method.declaringClass == RemoteObject::class.java) {
            // manage all of the RemoteObject proxy methods
            when (method) {
                closeMethod -> {
                    rmiObjectCache.removeProxyObject(rmiObjectId)
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
                enableHashCodeMethod -> {
                    enableHashCode = args!![0] as Boolean
                    return null
                }
                enableEqualsMethod -> {
                    enableEquals = args!![0] as Boolean
                    return null
                }

                else -> throw Exception("Invocation handler could not find RemoteObject method for ${method.name}")
            }
        } else  {
            when (method) {
                toStringMethod -> if (!enableToString) return proxyString  // otherwise, the RMI round trip logic is done for toString()
                hashCodeMethod -> if (!enableHashCode) return rmiObjectId  // otherwise, the RMI round trip logic is done for hashCode()
                equalsMethod   -> {
                    val other = args!![0]
                    if (other !is RmiClient) {
                        return false
                    }

                    if (!enableEquals) {
                        return rmiObjectId == other.rmiObjectId
                    }

                    // otherwise, the RMI round trip logic is done for equals()
                }
            }
        }

        // if a 'suspend' function is called, then our last argument is a 'Continuation' object
        // We will use this for our coroutine context instead of running on a new coroutine
        val maybeContinuation = args?.lastOrNull()

        // async will return immediately
        val returnValue = if (maybeContinuation is Continuation<*>) {
            invokeSuspendFunction(maybeContinuation) {
                sendRequest(method, args)
            }
        } else {
            runBlocking {
                sendRequest(method, args ?: EMPTY_ARRAY)
            }
        }


        if (!isAsync) {
            return returnValue
        }

        // if we are async then we return immediately.
        // If you want the response value, disable async!
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
    }

    // trampoline so we can access suspend functions correctly and (if suspend) get the coroutine connection parameter)
    private fun invokeSuspendFunction(continuation: Continuation<*>, suspendFunction: suspend () -> Any?): Any {
        return SuspendFunctionTrampoline.invoke(continuation, suspendFunction) as Any
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
