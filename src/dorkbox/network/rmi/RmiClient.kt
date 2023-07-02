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

import dorkbox.network.connection.Connection
import dorkbox.network.connection.EndPoint
import dorkbox.network.rmi.messages.MethodRequest
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mu.KLogger
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
                         private val responseManager: ResponseManager,
                         private val cachedMethods: Array<CachedMethod>) : InvocationHandler {

    companion object {
        private val methods = RmiUtils.getMethods(RemoteObject::class.java)

        private val toStringMethod = methods.find { it.name == "toString" }
        private val hashCodeMethod = methods.find { it.name == "hashCode" }
        private val equalsMethod = methods.find { it.name == "equals" }

        private val enableToStringMethod = methods.find { it.name == "enableToString" }
        private val enableHashCodeMethod = methods.find { it.name == "enableHashCode" }
        private val enableEqualsMethod = methods.find { it.name == "enableEquals" }

        private val asyncMethod = methods.find { it.name == "async" }
        private val syncMethod = methods.find { it.name == "sync" }
        private val asyncSuspendMethod = methods.find { it.name == "asyncSuspend" }
        private val syncSuspendMethod = methods.find { it.name == "syncSuspend" }

        private val setResponseTimeoutMethod = methods.find { it.name == "setResponseTimeout" }
        private val getResponseTimeoutMethod = methods.find { it.name == "getResponseTimeout" }
        private val setAsyncMethod = methods.find { it.name == "setAsync" }
        private val getAsyncMethod = methods.find { it.name == "getAsync" }

        @Suppress("UNCHECKED_CAST")
        private val EMPTY_ARRAY: Array<Any> = Collections.EMPTY_LIST.toTypedArray() as Array<Any>

        private val safeAsyncState: ThreadLocal<Boolean?> = ThreadLocal.withInitial {
            null
        }

        private const val charPrim = 0.toChar()
        private const val shortPrim = 0.toShort()
        private const val bytePrim = 0.toByte()

        private fun returnAsyncOrSync(isAsync: Boolean, method: Method, returnValue: Any?): Any? {
            if (isAsync) {
                // if we are async then we return immediately.
                // If you want the response value, disable async!
                val returnType = method.returnType
                if (returnType.isPrimitive) {
                    return when (returnType) {
                        Boolean::class.javaPrimitiveType -> java.lang.Boolean.FALSE
                        Int::class.javaPrimitiveType    -> 0
                        Float::class.javaPrimitiveType  -> 0.0f
                        Char::class.javaPrimitiveType   -> charPrim
                        Long::class.javaPrimitiveType   -> 0L
                        Short::class.javaPrimitiveType  -> shortPrim
                        Byte::class.javaPrimitiveType   -> bytePrim
                        Double::class.javaPrimitiveType -> 0.0
                        else -> null // void type
                    }
                }
                return null
            }
            else {
                return returnValue
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun syncMethodAction(isAsync: Boolean, proxy: RemoteObject<*>, args: Array<Any>) {
            val action = args[0] as Any.() -> Unit
            val prev = safeAsyncState.get()

            // the sync state is treated as a stack. Manually changing the state via `.async` field setter can cause problems, but
            // the docs cover that (and say, `don't do this`)
            safeAsyncState.set(false)

            // the `sync` method is always a unit function - we want to execute that unit function directly - this way we can control
            // exactly how sync state is preserved.
            try {
                action(proxy)
            } finally {
                if (prev != isAsync) {
                    safeAsyncState.remove()
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun syncSuspendMethodAction(isAsync: Boolean, proxy: RemoteObject<*>, args: Array<Any>): Any? {
            val action = args[0] as suspend Any.() -> Unit

            val prev = safeAsyncState.get()

            // if a 'suspend' function is called, then our last argument is a 'Continuation' object
            // We will use this for our coroutine context instead of running on a new coroutine
            val suspendCoroutineArg = args.last()

            val continuation = suspendCoroutineArg as Continuation<Any?>
            val suspendFunction: suspend () -> Any? = {
                // the sync state is treated as a stack. Manually changing the state via `.async` field setter can cause problems, but
                // the docs cover that (and say, `don't do this`)
                withContext(safeAsyncState.asContextElement(isAsync)) {
                    action(proxy)
                }
            }

            // function suspension works differently !!
            return (suspendFunction as Function1<Continuation<Any?>, Any?>).invoke(
                Continuation(continuation.context) {
                    val any = try {
                        it.getOrNull()
                    } finally {
                        if (prev != isAsync) {
                            safeAsyncState.remove()
                        }
                    }
                    when (any) {
                        is Exception -> {
                            // for co-routines, it's impossible to get a legit stacktrace without impacting general performance,
                            // so we just don't do it.
                            // RmiUtils.cleanStackTraceForProxy(Exception(), any)
                            continuation.resumeWithException(any)
                        }
                        else -> {
                            continuation.resume(null)
                        }
                    }
                })
        }
    }

    @Volatile private var isAsync = false
    @Volatile private var timeoutMillis: Long = if (EndPoint.DEBUG_CONNECTIONS) TimeUnit.HOURS.toMillis(2) else 3_000L
    @Volatile private var enableToString = false
    @Volatile private var enableHashCode = false
    @Volatile private var enableEquals = false

    // if we are ASYNC, then this method immediately returns
    private suspend fun sendRequest(isAsync: Boolean, invokeMethod: MethodRequest, logger: KLogger): Any? {
        // there is a STRANGE problem, where if we DO NOT respond/reply to method invocation, and immediate invoke multiple methods --
        // the "server" side can have out-of-order method invocation. There are 2 ways to solve this
        //  1) make the "server" side single threaded
        //  2) make the "client" side wait for execution response (from the "server"). <--- this is what we are using.
        //
        // Because we have to ALWAYS make the client wait (unless 'isAsync' is true), we will always be returning, and will always have a
        // response (even if it is a void response). This simplifies our response mask, and lets us use more bits for storing the
        // response ID

        // NOTE: we ALWAYS send a response from the remote end (except when async).
        //
        // 'async' -> DO NOT WAIT (no response)
        // 'timeout > 0' -> WAIT w/ TIMEOUT
        // 'timeout == 0' -> WAIT FOREVER

        invokeMethod.isGlobal = isGlobal

        return if (isAsync) {
            // If we are async, we ignore the response (don't invoke the response manager at all)....
            invokeMethod.packedId = RmiUtils.packShorts(rmiObjectId, RemoteObjectStorage.ASYNC_RMI)

            connection.send(invokeMethod)
            null
        } else {
            // The response, even if there is NOT one (ie: not void) will always return a thing (so our code execution is in lockstep -- unless it is ASYNC)
            val rmiWaiter = responseManager.prep(logger)
            invokeMethod.packedId = RmiUtils.packShorts(rmiObjectId, rmiWaiter.id)

            connection.send(invokeMethod)

            responseManager.waitForReply(rmiWaiter, timeoutMillis, logger, connection)
        }
    }






    @Suppress("DuplicatedCode", "UNCHECKED_CAST")
    /**
     * @throws Exception
     */
    override fun invoke(proxy: Any, method: Method, args: Array<Any>?): Any? {
        val localAsync =
            safeAsyncState.get() // value set via obj.sync {}
                ?:
            isAsync // the value was set via obj.sync = xyz


        if (method.declaringClass == RemoteObject::class.java) {
            // manage all the RemoteObject proxy methods
            when (method) {
                setResponseTimeoutMethod -> {
                    timeoutMillis = (args!![0] as Int).toLong()
                    require(timeoutMillis >= 0) { "ResponseTimeout must be >= 0" }
                    return null
                }

                getResponseTimeoutMethod -> {
                    return timeoutMillis.toInt()
                }

                getAsyncMethod -> {
                    return localAsync
                }

                setAsyncMethod -> {
                    isAsync = args!![0] as Boolean
                    return null
                }


                asyncMethod -> {
                    syncMethodAction(true, proxy as RemoteObject<*>, args!!)
                    return null
                }
                syncMethod -> {
                    syncMethodAction(false, proxy as RemoteObject<*>, args!!)
                    return null
                }

                asyncSuspendMethod -> {
                    return syncSuspendMethodAction(true, proxy as RemoteObject<*>, args!!)
                }
                syncSuspendMethod -> {
                    return syncSuspendMethodAction(false, proxy as RemoteObject<*>, args!!)
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
        } else {
            when (method) {
                toStringMethod -> if (!enableToString) return proxyString  // otherwise, the RMI round trip logic is done for toString()
                hashCodeMethod -> if (!enableHashCode) return rmiObjectId  // otherwise, the RMI round trip logic is done for hashCode()
                equalsMethod -> {
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
        val suspendCoroutineArg = args?.lastOrNull()

        // async will return immediately
        if (suspendCoroutineArg is Continuation<*>) {
            val continuation = suspendCoroutineArg as Continuation<Any?>

            val suspendFunction: suspend () -> Any? = {
                sendRequest(localAsync, invokeMethod, connection.logger)
            }

            // function suspension works differently !!
            return (suspendFunction as Function1<Continuation<Any?>, Any?>).invoke(
                Continuation(continuation.context) {
                    val any = it.getOrNull()
                    when (any) {
                        ResponseManager.TIMEOUT_EXCEPTION -> {
                            val fancyName = RmiUtils.makeFancyMethodName(method)
                            val exception = TimeoutException("Response timed out: $fancyName")
                            // from top down, clean up the coroutine stack
                            RmiUtils.cleanStackTraceForProxy(exception)
                            continuation.resumeWithException(exception)
                        }

                        is Exception -> {
                            // for co-routines, it's impossible to get a legit stacktrace without impacting general performance,
                            // so we just don't do it.
                            // RmiUtils.cleanStackTraceForProxy(Exception(), any)
                            continuation.resumeWithException(any)
                        }

                        else -> {
                            continuation.resume(returnAsyncOrSync(localAsync, method, any))
                        }
                    }
                })
        } else {
            val any = runBlocking {
                sendRequest(localAsync, invokeMethod, connection.logger)
            }

            when (any) {
                ResponseManager.TIMEOUT_EXCEPTION -> {
                    val fancyName = RmiUtils.makeFancyMethodName(method)
                    val exception = TimeoutException("Response timed out: $fancyName")
                    // from top down, clean up the coroutine stack
                    RmiUtils.cleanStackTraceForProxy(exception)
                    throw exception
                }

                is Exception -> {
                    // reconstruct the stack trace, so the calling method knows where the method invocation happened, and can trace the call
                    // this stack will ALWAYS run up to this method (so we remove from the top->down, to get to the call site)
                    RmiUtils.cleanStackTraceForProxy(Exception(), any)
                    throw any
                }

                else -> {
                    // attempt to return a proper value
                    return returnAsyncOrSync(localAsync, method, any)
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
