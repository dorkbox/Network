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
package dorkbox.network.rmi;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import dorkbox.network.connection.KryoExtra;

/**
 * Internal message to invoke methods remotely.
 */
public
class InvokeMethodSerializer extends Serializer<InvokeMethod> {

    public
    InvokeMethodSerializer() {
    }

    @Override
    @SuppressWarnings("rawtypes")
    public
    void write(Kryo kryo, Output output, InvokeMethod object) {
        output.writeInt(object.objectID, true);
        output.writeInt(object.cachedMethod.methodClassID, true);
        output.writeByte(object.cachedMethod.methodIndex);

        Serializer[] serializers = object.cachedMethod.serializers;
        Object[] args = object.args;
        for (int i = 0, n = serializers.length; i < n; i++) {
            Serializer serializer = serializers[i];
            if (serializer != null) {
                kryo.writeObjectOrNull(output, args[i], serializer);
            }
            else {
                kryo.writeClassAndObject(output, args[i]);
            }
        }

        output.writeByte(object.responseData);
    }

    @Override
    public
    InvokeMethod read(Kryo kryo, Input input, Class<InvokeMethod> type) {
        InvokeMethod invokeMethod = new InvokeMethod();

        invokeMethod.objectID = input.readInt(true);

        int methodClassID = input.readInt(true);
        Class<?> methodClass = kryo.getRegistration(methodClassID)
                                   .getType();

        byte methodIndex = input.readByte();
        try {
            KryoExtra kryoExtra = (KryoExtra) kryo;

            invokeMethod.cachedMethod = kryoExtra.getMethods(methodClass)[methodIndex];
        } catch (IndexOutOfBoundsException ex) {
            throw new KryoException("Invalid method index " + methodIndex + " for class: " + methodClass.getName());
        }

        Serializer<?>[] serializers = invokeMethod.cachedMethod.serializers;
        Class<?>[] parameterTypes = invokeMethod.cachedMethod.method.getParameterTypes();
        Object[] args = new Object[serializers.length];
        invokeMethod.args = args;
        for (int i = 0, n = args.length; i < n; i++) {
            Serializer<?> serializer = serializers[i];
            if (serializer != null) {
                args[i] = kryo.readObjectOrNull(input, parameterTypes[i], serializer);
            }
            else {
                args[i] = kryo.readClassAndObject(input);
            }
        }

        invokeMethod.responseData = input.readByte();

        return invokeMethod;
    }
}
