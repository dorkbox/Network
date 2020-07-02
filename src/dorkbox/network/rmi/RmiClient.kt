/*
 * Copyright 2010 dorkbox, llc
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
 *
 * Copyright (c) 2008, Nathan Sweet
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * - Neither the name of Esoteric Software nor the names of its contributors may be used to endorse or promote products derived
 * from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package dorkbox.network.rmi

import com.conversantmedia.util.concurrent.MultithreadConcurrentQueue
import dorkbox.network.connection.Connection
import dorkbox.network.connection.Connection_
import dorkbox.network.connection.KryoExtra
import dorkbox.network.connection.OnMessageReceived
import dorkbox.network.rmi.messages.MethodRequest
import dorkbox.network.rmi.messages.MethodResponse
import kotlinx.coroutines.launch
import org.agrona.collections.IntArrayList
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock


/**
 * Handles network communication when methods are invoked on a proxy.
 *
 *
 * If the method return type is 'void', then we don't have to explicitly set 'transmitReturnValue' to false
 *
 *
 * If there are no checked exceptions thrown, then we don't have to explicitly set 'transmitExceptions' to false
 *
 * @param connection this is really the network client -- there is ONLY ever 1 connection
 * @param rmiSupport is used to provide RMI support
 * @param rmiId this is the remote object ID (assigned by RMI). This is NOT the kryo registration ID
 * @param iFace this is the RMI interface
*/
class RmiClient(private val connection: Connection_,
                private val rmiSupport: ConnectionRmiSupport, // this is the RMI id
                val rmiObjectId: Int,
                iFace: Class<*>?) : InvocationHandler {

    companion object {
        private val methods = RmiUtils.getMethods(RemoteObject::class.java)

        private val closeMethod = methods.find { it.name == "close" }
        private val setResponseTimeoutMethod = methods.find { it.name == "setResponseTimeout" }
        private val getResponseTimeoutMethod = methods.find { it.name == "getResponseTimeout" }
        private val setAsyncMethod = methods.find { it.name == "setAsync" }
        private val enableToStringMethod = methods.find { it.name == "enableToString" }
        private val waitForLastResponseMethod = methods.find { it.name == "waitForLastResponse" }
        private val getLastResponseIDMethod = methods.find { it.name == "getLastResponseID" }
        private val waitForResponseMethod = methods.find { it.name == "waitForResponse" }
        private val toStringMethod = methods.find { it.name == "toString" }
    }

    private val logger: Logger

    private val lock = ReentrantLock()
    private val responseCondition = lock.newCondition()

    private val responseTable = arrayOfNulls<MethodResponse>(256)
    private val pendingResponses = BooleanArray(256)


    // this is the KRYO class id
    private val classId: Int
    private val proxyString = "<proxy #$rmiObjectId>"

    val listener: OnMessageReceived<Connection, MethodResponse>
    private var timeoutMillis = 3000
    private var isAsync = false

    private var enableToString = false


    // for responseId's, "0" means no response (or invalid response id)
    private val responseIds = MultithreadConcurrentQueue<Byte>(256)

    private var previousResponseId: Byte = 0x00


    init {
        // create a shuffled list of ID's
        val ids = IntArrayList()
        for (id in 1..255) {
            ids.addInt(id)
        }
        ids.shuffle()

        // populate the array of randomly assigned ID's.
        ids.forEach {
            responseIds.offer(it.toByte())
        }

        // figure out the class ID for this RMI object
        val endPoint = connection.endPoint()
        val serializationManager = endPoint.serialization

        var kryoExtra: KryoExtra? = null
        try {
            kryoExtra = serializationManager.takeKryo()
            classId = kryoExtra.getRegistration(iFace).id
        } finally {
            if (kryoExtra != null) {
                serializationManager.returnKryo(kryoExtra)
            }
        }

        logger = LoggerFactory.getLogger(endPoint.name + ":" + this.javaClass.simpleName)
        // this listener is called when the "server" responds to our request.
        // SPECIFICALLY, this is "unblocks" our "waiting for response" logic
        listener = object : OnMessageReceived<Connection, MethodResponse> {
            override fun received(connection: Connection, message: MethodResponse) {
                // we have to check object ID's, BECAUSE we can have different objects all listening for a response
                if (message.objectId != rmiObjectId) {
                    return
                }

                val responseIdAsInt = 0xFF and message.responseId.toInt()
                synchronized(this) {
                    if (pendingResponses[responseIdAsInt]) {
                        responseTable[responseIdAsInt] = message
                    }
                }
                lock.lock()
                try {
                    responseCondition.signalAll()
                } finally {
                    lock.unlock()
                }
            }
        }
    }

    @Throws(Exception::class)
    override fun invoke(proxy: Any, method: Method, args: Array<Any>?): Any? {
        val declaringClass = method.declaringClass
        if (declaringClass == RemoteObject::class.java) {
            // manage all of the RemoteObject proxy methods
            when (method) {
                closeMethod -> {
                    rmiSupport.removeProxyObject(this)
                    return null
                }
                setResponseTimeoutMethod -> {
                    timeoutMillis = args!![0] as Int
                    return null
                }
                getResponseTimeoutMethod -> {
                    return timeoutMillis
                }
                setAsyncMethod -> {
                    isAsync = args!![0] as Boolean
                    return null
                }
                enableToStringMethod -> {
                    enableToString = args!![0] as Boolean
                    return null
                }
                waitForLastResponseMethod -> {
                    return waitForResponse(0xFF and previousResponseId.toInt())
                }
                getLastResponseIDMethod -> {
                    return previousResponseId
                }
                waitForResponseMethod -> {
                    check(!isAsync) { "This RemoteObject is not currently set to ASYNC mode. Unable to ignore all responses." }
                    return waitForResponse(args!![0] as Int)
                }
                else -> throw Exception("Invocation handler could not find RemoteObject method for ${method.name}")
            }
        } else if (!enableToString && method == toStringMethod) {
            return proxyString
        }

        val invokeMethod = MethodRequest()
        invokeMethod.objectId = rmiObjectId
        invokeMethod.args = args

        // which method do we access? We always want to access the IMPLEMENTATION (if available!)
        val cachedMethods = connection.endPoint().serialization.getMethods(classId)
        var i = 0
        val n = cachedMethods.size
        while (i < n) {
            val cachedMethod = cachedMethods[i]
            val checkMethod = cachedMethod.method
            if (checkMethod == method) {
                invokeMethod.cachedMethod = cachedMethod
                break
            }
            i++
        }

        // a value of 0 means there is no response (for 'async' calls only)
        val responseId: Byte
        val responseIdAsInt: Int
        val returnType = method.returnType

        // there is a STRANGE problem, where if we DO NOT respond/reply to method invocation, and immediate invoke multiple methods --
        // the "server" side can have out-of-order method invocation. There are 2 ways to solve this
        //  1) make the "server" side single threaded
        //  2) make the "client" side wait for execution response (from the "server"). <--- this is what we are using.
        //
        // Because we have to ALWAYS make the client wait (unless 'isAsync' is true), we will always be returning, and will always have a
        // response (even if it is a void response). This simplifies our response mask, and lets us use more bits for storing the
        // response ID


        // If we are async, we always ignore the response.
        // A value of 0 means to not respond, and the rest is just an ID to match requests <-> responses
        if (isAsync) {
            responseId = 0x00
            responseIdAsInt = 0
        } else {
            responseId = responseIds.poll()
            responseIdAsInt = 0xFF and responseId.toInt()

            synchronized(this) {
                pendingResponses[responseIdAsInt] = true
            }

            invokeMethod.responseId = responseId
        }

        // so we can query this, if necessary
        previousResponseId = responseId



        // Sends our invokeMethod to the remote connection, which the RmiBridge listens for
        val endPoint = connection.endPoint()
        endPoint.actionDispatch.launch {
            connection.send(invokeMethod);

            if (logger.isTraceEnabled) {
                var argString = args?.contentDeepToString() ?: ""
                argString = argString.substring(1, argString.length - 1)
                logger.trace("$connection sent: ${method.declaringClass.simpleName}#${method.name}($argString)")
            }
        }

//        // if a 'suspend' function is called, then our last argument is a 'Continuation' object (and we can use that instead of runBlocking)
//        val continuation = args?.lastOrNull()
//        if (continuation is Continuation<*>) {
////        val continuation = args!!.last() as Continuation<*>
////            val argsWithoutContinuation = args.take(args.size - 1)
////            continuation.context.
//            return kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
//        }


        // MUST use 'waitForLastResponse()' or 'waitForResponse'('getLastResponseID()') to get the response
        // If we are async then we return immediately
        // If we are 'void' return type and do not throw checked exceptions then we return immediately
        if (isAsync) {
            if (returnType.isPrimitive) {
                if (returnType == Int::class.javaPrimitiveType) {
                    return 0
                }
                if (returnType == Boolean::class.javaPrimitiveType) {
                    return java.lang.Boolean.FALSE
                }
                if (returnType == Float::class.javaPrimitiveType) {
                    return 0.0f
                }
                if (returnType == Char::class.javaPrimitiveType) {
                    return 0.toChar()
                }
                if (returnType == Long::class.javaPrimitiveType) {
                    return 0L
                }
                if (returnType == Short::class.javaPrimitiveType) {
                    return 0.toShort()
                }
                if (returnType == Byte::class.javaPrimitiveType) {
                    return 0.toByte()
                }
                if (returnType == Double::class.javaPrimitiveType) {
                    return 0.0
                }
            }
            return null
        }


        return try {
            val result = waitForResponse(responseIdAsInt)
            if (result is Exception) {
                throw result
            } else {
                result
            }
        } catch (ex: TimeoutException) {
            throw TimeoutException("Response timed out: ${method.declaringClass.name}.${method.name}")
        } finally {
            synchronized(this) {
                pendingResponses[responseIdAsInt] = false
                responseTable[responseIdAsInt] = null
            }
        }
    }

    /**
     * A timeout of 0 means that we want to disable waiting, otherwise - it waits in milliseconds
     */
    @Throws(IOException::class)
    private fun waitForResponse(responseIdAsInt: Int): Any? {
        // if timeout == 0, we wait "forever"
        var remaining: Long
        val endTime: Long

        if (timeoutMillis != 0) {
            remaining = timeoutMillis.toLong()
            endTime = System.currentTimeMillis() + remaining
        } else {
            // not forever, but close enough
            remaining = Long.MAX_VALUE
            endTime = Long.MAX_VALUE
        }

        // wait for the specified time
        var methodResponse: MethodResponse?
        while (remaining > 0) {
            synchronized(this) {
                methodResponse = responseTable[responseIdAsInt]
            }


            if (methodResponse != null) {
                previousResponseId = 0 // 0 is "no response" or "invalid"
                return methodResponse!!.result
            } else {
                lock.lock()
                try {
                    responseCondition.await(remaining, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread()
                            .interrupt()
                    throw IOException("Response timed out.", e)
                } finally {
                    lock.unlock()
                }
            }
            remaining = endTime - System.currentTimeMillis()
        }
        throw TimeoutException("Response timed out.")
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
        val other1 = other as RmiClient
        return rmiObjectId == other1.rmiObjectId
    }
}
