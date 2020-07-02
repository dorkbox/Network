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
package dorkbox.network.rmi.messages

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.KryoException
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import dorkbox.network.connection.KryoExtra
import java.lang.reflect.Method

/**
 * Internal message to invoke methods remotely.
 */
class MethodRequestSerializer : Serializer<MethodRequest>() {
    companion object {
        private const val DEBUG = false
    }

    override fun write(kryo: Kryo, output: Output, methodRequest: MethodRequest) {
        if (DEBUG) {
            System.err.println("WRITING")
            System.err.println(":: objectID " + methodRequest.objectId)
            System.err.println(":: methodClassID " + methodRequest.cachedMethod.methodClassId)
            System.err.println(":: methodIndex " + methodRequest.cachedMethod.methodIndex)
        }

        output.writeInt(methodRequest.objectId, true)
        output.writeInt(methodRequest.cachedMethod.methodClassId, true)
        output.writeByte(methodRequest.cachedMethod.methodIndex)

        val serializers = methodRequest.cachedMethod.serializers
        val length = serializers.size
        val args = methodRequest.args

        for (i in 0 until length) {
            val serializer = serializers[i]
            if (serializer != null) {
                kryo.writeObjectOrNull(output, args!![i], serializer)
            } else {
                kryo.writeClassAndObject(output, args!![i])
            }
        }

        output.writeByte(methodRequest.responseId)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<out MethodRequest>): MethodRequest {
        val objectID = input.readInt(true)
        val methodClassID = input.readInt(true)
        val methodIndex = input.readByte()

        if (DEBUG) {
            System.err.println("READING")
            System.err.println(":: objectID $objectID")
            System.err.println(":: methodClassID $methodClassID")
            System.err.println(":: methodIndex $methodIndex")
        }

        val cachedMethod = try {
            (kryo as KryoExtra).serializationManager.getMethods(methodClassID)[methodIndex.toInt()]
        } catch (ex: Exception) {
            val methodClass = kryo.getRegistration(methodClassID).type
            throw KryoException("Invalid method index " + methodIndex + " for class: " + methodClass.name)
        }

        val args: Array<Any>
        val serializers = cachedMethod.serializers
        val method: Method
        val argStartIndex: Int

        if (cachedMethod.overriddenMethod != null) {
            // did we override our cached method? This is not common.
            method = cachedMethod.overriddenMethod!!

            // this is specifically when we override an interface method, with an implementation method + Connection parameter (@ index 0)
            argStartIndex = 1
            args = arrayOfNulls<Any>(serializers.size + 1) as Array<Any>
            args[0] = kryo.connection
        } else {
            method = cachedMethod.method
            argStartIndex = 0
            args = arrayOfNulls<Any>(serializers.size) as Array<Any>
        }

        val parameterTypes = method.parameterTypes

        // we don't start at 0 for the arguments, in case we have an overwritten method (in which case, the 1st arg is always "Connection.class")
        var i = 0
        val n = serializers.size
        var j = argStartIndex

        while (i < n) {
            val serializer = serializers[i]
            if (serializer != null) {
                args[j] = kryo.readObjectOrNull(input, parameterTypes[i], serializer)
            } else {
                args[j] = kryo.readClassAndObject(input)
            }
            i++
            j++
        }


        val invokeMethod = MethodRequest()
        invokeMethod.objectId = objectID
        invokeMethod.cachedMethod = cachedMethod
        invokeMethod.args = args
        invokeMethod.responseId = input.readByte()

        return invokeMethod
    }
}
