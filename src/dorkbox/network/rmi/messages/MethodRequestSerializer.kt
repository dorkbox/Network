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
import dorkbox.network.rmi.RmiUtils
import dorkbox.network.serialization.KryoExtra
import java.lang.reflect.Method

/**
 * Internal message to invoke methods remotely.
 */
@Suppress("ConstantConditionIf")
class MethodRequestSerializer : Serializer<MethodRequest>() {
    override fun write(kryo: Kryo, output: Output, methodRequest: MethodRequest) {
        val method = methodRequest.cachedMethod

        // we pack objectId + responseId into the same "int", since they are both really shorts (but are represented as ints to make
        // working with them a lot easier
        output.writeInt(methodRequest.packedId)
        output.writeInt(RmiUtils.packShorts(method.methodClassId, method.methodIndex), true)
        output.writeBoolean(methodRequest.isGlobal)


        val serializers = method.serializers
        if (serializers.isNotEmpty()) {
            val args = methodRequest.args!!

            serializers.forEachIndexed { index, serializer ->
                if (serializer != null) {
                    kryo.writeObjectOrNull(output, args[index], serializer)
                } else {
                    kryo.writeClassAndObject(output, args[index])
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun read(kryo: Kryo, input: Input, type: Class<out MethodRequest>): MethodRequest {
        val packedId = input.readInt()
        val methodInfo = input.readInt(true)
        val methodClassId = RmiUtils.unpackLeft(methodInfo)
        val methodIndex = RmiUtils.unpackRight(methodInfo)
        val isGlobal = input.readBoolean()

        (kryo as KryoExtra)

        val cachedMethod = try {
            kryo.getMethods(methodClassId)[methodIndex]
        } catch (ex: Exception) {
            val methodClass = kryo.getRegistration(methodClassId).type
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

            // we have to save the connection this happened on, so it can be part of the method invocation
            args[0] = kryo.connection
        } else {
            method = cachedMethod.method
            argStartIndex = 0
            args = arrayOfNulls<Any>(serializers.size) as Array<Any>
        }

        val parameterTypes = method.parameterTypes

        var isCoroutine = false

        // we don't start at 0 for the arguments, in case we have an overwritten method, in which case, the 1st arg is always "Connection.class"
        var index = 0
        val size = serializers.size
        var argStart = argStartIndex


        while (index < size) {
            val serializer = serializers[index]
            if (serializer != null) {
                if (serializer is ContinuationSerializer) {
                    isCoroutine = true
                    // have to check if it's a coroutine or not!
                    args[argStart] = ContinuationSerializer::class.java
                } else {
                    args[argStart] = kryo.readObjectOrNull(input, parameterTypes[index], serializer)
                }
            } else {
                args[argStart] = kryo.readClassAndObject(input)
            }
            index++
            argStart++
        }


        val invokeMethod = MethodRequest()
        invokeMethod.isGlobal = isGlobal
        invokeMethod.isCoroutine = isCoroutine
        invokeMethod.packedId = packedId
        invokeMethod.cachedMethod = cachedMethod
        invokeMethod.args = args

        return invokeMethod
    }
}
