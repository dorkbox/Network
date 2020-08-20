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
import dorkbox.network.other.coroutines.SuspendFunctionTrampoline
import dorkbox.network.rmi.messages.MethodRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.util.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object MyUnconfined : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = false
    override fun dispatch(context: CoroutineContext, block: Runnable) = block.run() // !!!
}

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
 * @param responseManager is used to provide RMI request/response support
 * @param cachedMethods this is the methods available for the specified class
*/
internal class RmiClient(val isGlobal: Boolean,
                         val rmiObjectId: Int,
                         private val connection: Connection,
                         private val proxyString: String,
                         private val responseManager: RmiResponseManager,
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
    private suspend fun sendRequest(invokeMethod: MethodRequest): Any? {
        // there is a STRANGE problem, where if we DO NOT respond/reply to method invocation, and immediate invoke multiple methods --
        // the "server" side can have out-of-order method invocation. There are 2 ways to solve this
        //  1) make the "server" side single threaded
        //  2) make the "client" side wait for execution response (from the "server"). <--- this is what we are using.
        //
        // Because we have to ALWAYS make the client wait (unless 'isAsync' is true), we will always be returning, and will always have a
        // response (even if it is a void response). This simplifies our response mask, and lets us use more bits for storing the
        // response ID

        // NOTE: we ALWAYS send a response from the remote end.
        //
        // 'async' -> DO NOT WAIT
        // 'timeout > 0' -> WAIT
        // 'timeout == 0' -> same as async (DO NOT WAIT)
        val isAsync = isAsync || timeoutMillis <= 0L


        // If we are async, we ignore the response....
        // The response, even if there is NOT one (ie: not void) will always return a thing (so our code excution is in lockstep
        val rmiWaiter = responseManager.prep(isAsync)

        invokeMethod.isGlobal = isGlobal
        invokeMethod.packedId = RmiUtils.packShorts(rmiObjectId, rmiWaiter.id)


        connection.send(invokeMethod)


        // if we are async, then this will immediately return
        return responseManager.waitForReply(isAsync, rmiWaiter, timeoutMillis)
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
                    connection.rmiConnectionSupport.removeProxyObject(rmiObjectId)
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

        // setup the RMI request

        val invokeMethod = MethodRequest()

        // if this is a kotlin suspend function, the continuation arg will NOT be here (it's replaced at runtime)!
        invokeMethod.args = args ?: EMPTY_ARRAY

        // which method do we access? We always want to access the IMPLEMENTATION (if available!). we know that this will always succeed
        // this should be accessed via the KRYO class ID + method index (both are SHORT, and can be packed)
        invokeMethod.cachedMethod = cachedMethods.first { it.method == method }





        // if a 'suspend' function is called, then our last argument is a 'Continuation' object
        // We will use this for our coroutine context instead of running on a new coroutine
        val suspendCoroutineObject = args?.lastOrNull()

        // async will return immediately
        var returnValue: Any? = null
        if (suspendCoroutineObject is Continuation<*>) {
//            val continuation = suspendCoroutineObject as Continuation<Any?>
//
//
//            val suspendFunction: suspend () -> Any? = {
//                val rmiResult = sendRequest(invokeMethod)
//                println("RMI: ${rmiResult?.javaClass}")
//                println(1)
//                delay(3000)
//                println(2)
//            }
//            val suspendFunction1: Function1<Continuation<Any?>, *> = suspendFunction as Function1<Continuation<Any?>?, *>
//            returnValue = suspendFunction1.invoke(Continuation(EmptyCoroutineContext) {
//                it.getOrNull().apply {
//                    continuation.resume(this)
//                }
//            })
//
//            System.err.println("have suspend ret value ${returnValue?.javaClass}")
//
////            returnValue = invokeSuspendFunction(continuation, suspendFunction)
//
//            // https://stackoverflow.com/questions/57230869/how-to-propagate-kotlin-coroutine-context-through-reflective-invocation-of-suspe
//            // https://stackoverflow.com/questions/52869672/call-kotlin-suspend-function-in-java-class
//            // https://discuss.kotlinlang.org/t/how-to-continue-a-suspend-function-in-a-dynamic-proxy-in-the-same-coroutine/11391
//
//
//            // NOTE:
//            // Calls to OkHttp Call.enqueue() like those inside await and awaitNullable can sometimes
//            // invoke the supplied callback with an exception before the invoking stack frame can return.
//            // Coroutines will intercept the subsequent invocation of the Continuation and throw the
//            // exception synchronously. A Java Proxy cannot throw checked exceptions without them being
//            // declared on the interface method. To avoid the synchronous checked exception being wrapped
//            // in an UndeclaredThrowableException, it is intercepted and supplied to a helper which will
//            // force suspension to occur so that it can be instead delivered to the continuation to
//            // bypass this restriction.
//
//            /**
//             * https://jakewharton.com/exceptions-and-proxies-and-coroutines-oh-my/
//             * https://github.com/Kotlin/kotlinx.coroutines/pull/1667
//             * https://github.com/square/retrofit/blob/master/retrofit/src/main/java/retrofit2/KotlinExtensions.kt
//             * https://github.com/square/retrofit/blob/master/retrofit/src/main/java/retrofit2/HttpServiceMethod.java
//             * https://github.com/square/retrofit/blob/master/retrofit/src/main/java/retrofit2/Utils.java
//             * https://github.com/square/retrofit/blob/master/retrofit/src/main/java/retrofit2
//             */
////            returnValue = try {
////                val actualContinuation = suspendCoroutineObject.intercepted() as Continuation<Any?>
//////                suspend {
//////                    try {
//////                        delay(100)
//////                        sendRequest(invokeMethod)
//////                    } catch (e: Exception) {
//////                        yield()
//////                        throw e
//////                    }
//////                }.startCoroutineUninterceptedOrReturn(actualContinuation)
////
////                invokeSuspendFunction(actualContinuation) {
////
////
////////                    kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> {
////                        delay(100)
////                        sendRequest(invokeMethod)
////////                    }
////////
//////                    kotlinx.coroutines.suspendCancellableCoroutine<Any?> { continuation: Any? ->
//////                        resume(body)
//////                    }
//////                    withContext(MyUnconfined) {
//////
//////                    }
////                }
////
//////                MyUnconfined.dispatch(suspendCoroutineObject.context, Runnable {
//////                    invokeSuspendFunction(suspendCoroutineObject) {
//////
//////                    }
//////                })
////
////            } catch (e: Exception) {
////                e.printStackTrace()
////            }
////
////            if (returnValue == kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) {
////                // we were suspend, and when we unsuspend, we will pick up where we left off
////                return returnValue
////            }

            // if this was an exception, we want to get it out!
            returnValue = runBlocking {
                sendRequest(invokeMethod)
            }
        } else {
            returnValue = runBlocking {
                sendRequest(invokeMethod)
            }
        }

        if (isAsync) {
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
        else {
            // this will not return immediately. This will be suspended until there is a response
            when (returnValue) {
                RmiResponseManager.TIMEOUT_EXCEPTION -> {
                    val fancyName = RmiUtils.makeFancyMethodName(method)
                    val exception = TimeoutException("Response timed out: $fancyName")
                    // from top down, clean up the coroutine stack
                    ListenerManager.cleanStackTrace(exception, RmiClient::class.java)
                    throw exception
                }
//                is Exception -> {
//                    // reconstruct the stack trace, so the calling method knows where the method invocation happened, and can trace the call
//                    // this stack will ALWAYS run up to this method (so we remove from the top->down, to get to the call site)
//                    ListenerManager.cleanStackTrace(Exception(), RmiClient::class.java, returnValue)
//                    throw returnValue
//                }
                else -> {
                    return returnValue
                }
            }
        }
    }


    /**
     * Force the calling coroutine to suspend before throwing [this].
     *
     * This is needed when a checked exception is synchronously caught in a [java.lang.reflect.Proxy]
     * invocation to avoid being wrapped in [java.lang.reflect.UndeclaredThrowableException].
     *
     * The implementation is derived from:
     * https://github.com/Kotlin/kotlinx.coroutines/pull/1667#issuecomment-556106349
     */
    suspend fun suspendAndThrow(e: Throwable): Nothing {
        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Nothing> { continuation ->
            Dispatchers.Default.dispatch(continuation.context, Runnable {
                continuation.resumeWithException(e)
            })
            kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
        }
    }


    // trampoline so we can access suspend functions correctly and (if suspend) get the coroutine connection parameter)
    private fun invokeSuspendFunction(continuation: Continuation<Any?>, suspendFunction: suspend () -> Any?): Any {
        return SuspendFunctionTrampoline.invoke(Continuation<Any?>(EmptyCoroutineContext) {
            it.getOrNull().apply {
                continuation.resume(this)
            }
        }, suspendFunction) as Any
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
