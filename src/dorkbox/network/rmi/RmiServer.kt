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

import dorkbox.network.connection.Connection
import dorkbox.network.rmi.messages.MethodRequest
import dorkbox.network.rmi.messages.MethodResponse
import dorkbox.util.collections.LockFreeIntBiMap
import org.slf4j.Logger
import java.io.IOException
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Allows methods on objects to be invoked remotely over TCP, UDP, or LOCAL. Local connections ignore TCP/UDP requests, and perform
 * object transformation (because there is no serialization occurring) using a series of weak hashmaps.
 *
 *
 * Objects are [NetworkSerializationManager.registerRmi], and endpoint connections can then [ ][Connection.createRemoteObject] for the registered objects.
 *
 *
 * It costs at least 2 bytes more to use remote method invocation than just sending the parameters. If the method has a return value which
 * is not [ignored][RemoteObject.setAsync], an extra byte is written. If the type of a parameter is not final (note that
 * primitives are final) then an extra byte is written for that parameter.
 *
 *
 * In situations where we want to pass in the Connection (to an RMI method), we have to be able to override method A, with method B.
 * This is to support calling RMI methods from an interface (that does pass the connection reference) to
 * an implType, that DOES pass the connection reference. The remote side (that initiates the RMI calls), MUST use
 * the interface, and the implType may override the method, so that we add the connection as the first in
 * the list of parameters.
 *
 *
 * for example:
 * Interface: foo(String x)
 * Impl: foo(Connection c, String x)
 *
 *
 * The implType (if it exists, with the same name, and with the same signature + connection parameter) will be called from the interface
 * instead of the method that would NORMALLY be called.
 *
 * @author Nathan Sweet <misc></misc>@n4te.com>, Nathan Robinson
 */
class RmiServer(// the name of who created this RmiBridge
        val logger: Logger, isGlobal: Boolean) {

    companion object {
        const val INVALID_MAP_ID = -1
        const val INVALID_RMI = Int.MAX_VALUE

        /**
         * @return true if the objectId is global for all connections (even). false if it's connection-only (odd)
         */
        fun isGlobal(objectId: Int): Boolean {
            // global RMI objects -> EVEN in range 0 - (MAX_VALUE-1)
            // connection local RMI -> ODD in range 1 - (MAX_VALUE-1)
            return objectId and 1 == 0
        }

        /**
         * Invokes the method on the object and, if necessary, sends the result back to the connection that made the invocation request. This
         * method is invoked on the update thread of the [EndPoint] for this RmiBridge and unless an executor has been set.
         *
         * This is the response to the invoke method in the RmiProxyHandler
         *
         * @param connection
         * The remote side of this connection requested the invocation.
         */
        @Throws(IOException::class)
        internal operator fun invoke(connection: Connection, target: Any, methodRequest: MethodRequest, logger: Logger): MethodResponse? {
            val cachedMethod = methodRequest.cachedMethod

            if (logger.isTraceEnabled) {
                var argString = ""
                if (methodRequest.args != null) {
                    argString = Arrays.deepToString(methodRequest.args)
                    argString = argString.substring(1, argString.length - 1)
                }
                val stringBuilder = StringBuilder(128)
                stringBuilder.append(connection.toString())
                        .append(" received: ")
                        .append(target.javaClass.simpleName)
                stringBuilder.append(":").append(methodRequest.objectId)
                stringBuilder.append("#").append(cachedMethod.method.name)
                stringBuilder.append("(").append(argString).append(")")

                if (cachedMethod.overriddenMethod != null) {
                    // did we override our cached method? THIS IS NOT COMMON.
                    stringBuilder.append(" [Connection method override]")
                }
                logger.trace(stringBuilder.toString())
            }


            val responseId = methodRequest.responseId.toInt()

            var result: Any?
            try {
                // args!! is safe to do here (even though it doesn't make sense)
                result = cachedMethod.invoke(connection, target, methodRequest.args!!)
            } catch (ex: Exception) {
                logger.error("Error invoking method: ${cachedMethod.method.declaringClass.name}.${cachedMethod.method.name}", ex)

                result = ex.cause
                // added to prevent a stack overflow when references is false
                // (because 'cause' == "this").
                // See:
                // https://groups.google.com/forum/?fromgroups=#!topic/kryo-users/6PDs71M1e9Y
                if (result == null) {
                    result = ex
                } else {
                    result.initCause(null)
                }
            }

            // A value of 0 means to not respond, and the rest is just an ID to match requests <-> responses
            if (responseId == 0) {
                return null
            }

            val invokeMethodResult = MethodResponse()
            invokeMethodResult.objectId = methodRequest.objectId
            invokeMethodResult.responseId = responseId.toByte()
            invokeMethodResult.result = result

            // logger.error("{} sent data: {}  with id ({})", connection, result, invokeMethod.responseID);
            return invokeMethodResult
        }
    }

    // we start at 1, because 0 (INVALID_RMI) means we access connection only objects
    private val rmiObjectIdCounter: AtomicInteger = if (isGlobal) {
        AtomicInteger(0)
    } else {
        AtomicInteger(1)
    }

    // this is the ID -> Object RMI map. The RMI ID is used (not the kryo ID)
    private val objectMap = LockFreeIntBiMap<Any>(INVALID_MAP_ID)


    private fun nextObjectId(): Int {
        // always increment by 2
        // global RMI objects -> ODD in range 1-16380 (max 2 bytes) throws error on outside of range
        // connection local RMI -> EVEN in range 1-16380 (max 2 bytes)  throws error on outside of range
        val value = rmiObjectIdCounter.getAndAdd(2)
        if (value >= INVALID_RMI) {
            rmiObjectIdCounter.set(INVALID_RMI) // prevent wrapping by spammy callers
            logger.error("next RMI value '{}' has exceeded maximum limit '{}' in RmiBridge! Not creating RMI object.", value, INVALID_RMI)
            return INVALID_RMI
        }
        return value
    }

    /**
     * Automatically registers an object with the next available ID to allow the remote end of the RmiBridge connections to access it using the returned ID.
     *
     * @return the RMI object ID, if the registration failed (null object or TOO MANY objects), it will be [RmiServer.INVALID_RMI] (Integer.MAX_VALUE).
     */
    fun register(`object`: Any?): Int {
        if (`object` == null) {
            return INVALID_RMI
        }

        // this will return INVALID_RMI if there are too many in the ObjectSpace
        val nextObjectId = nextObjectId()
        if (nextObjectId != INVALID_RMI) {
            // specifically avoid calling register(int, Object) method to skip non-necessary checks + exceptions
            objectMap.put(nextObjectId, `object`)
            if (logger.isTraceEnabled) {
                logger.trace("Object <proxy #{}> registered with ObjectSpace with .toString() = '{}'", nextObjectId, `object`)
            }
        }
        return nextObjectId
    }

    /**
     * Registers an object to allow the remote end of the RmiBridge connections to access it using the specified ID.
     *
     * @param objectID Must not be <0 or [RmiServer.INVALID_RMI] (Integer.MAX_VALUE).
     */
    fun register(objectID: Int, `object`: Any?) {
        require(objectID >= 0) { "objectID cannot be $INVALID_RMI" }
        require(objectID < INVALID_RMI) { "objectID cannot be $INVALID_RMI" }
        requireNotNull(`object`) { "object cannot be null." }
        objectMap.put(objectID, `object`)
        if (logger.isTraceEnabled) {
            logger.trace("Object <proxy #{}> registered in RmiBridge with .toString() = '{}'", objectID, `object`)
        }
    }

    /**
     * Removes an object. The remote end of the RmiBridge connection will no longer be able to access it.
     */
    fun remove(objectID: Int) {
        val `object` = objectMap.remove(objectID)
        if (logger.isTraceEnabled) {
            logger.trace("Object <proxy #{}> removed from ObjectSpace with .toString() = '{}'", objectID, `object`)
        }
    }

    /**
     * Removes an object. The remote end of the RmiBridge connection will no longer be able to access it.
     */
    fun remove(`object`: Any) {
        val objectID = objectMap.inverse().remove(`object`)
        if (objectID == INVALID_MAP_ID) {
            logger.error("Object {} could not be found in the ObjectSpace.", `object`)
        } else if (logger.isTraceEnabled) {
            logger.trace("Object {} (ID: {}) removed from ObjectSpace.", `object`, objectID)
        }
    }

    /**
     * Returns the object registered with the specified ID.
     */
    fun getRegisteredObject(objectID: Int): Any? {
        return if (objectID < 0 || objectID >= INVALID_RMI) {
            null
        } else objectMap[objectID]

        // Find an object with the objectID.
    }

    /**
     * Returns the ID registered for the specified object, or INVALID_RMI if not found.
     */
    fun <T> getRegisteredId(`object`: T): Int {
        // Find an ID with the object.
        val i = objectMap.inverse()[`object`]
        return if (i == INVALID_MAP_ID) {
            INVALID_RMI
        } else i
    }
}
