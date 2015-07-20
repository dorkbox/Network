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
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.KryoExtra;
import dorkbox.util.exceptions.NetException;

/**
 * Serializes an object registered with the RmiBridge so the receiving side
 * gets a {@link RemoteObject} proxy rather than the bytes for the serialized
 * object.
 *
 * @author Nathan Sweet <misc@n4te.com>
 */
public
class RemoteObjectSerializer<T> extends Serializer<T> {

    public
    RemoteObjectSerializer() {
    }

    @Override
    public
    void write(Kryo kryo, Output output, T object) {
        KryoExtra kryoExtra = (KryoExtra) kryo;
        int id = kryoExtra.connection.getRegisteredId(object);
        if (id == Integer.MAX_VALUE) {
            throw new NetException("Object not found in an ObjectSpace: " + object);
        }

        output.writeInt(id, true);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public
    T read(Kryo kryo, Input input, Class type) {
        KryoExtra kryoExtra = (KryoExtra) kryo;
        int objectID = input.readInt(true);
        final ConnectionImpl connection = kryoExtra.connection;
        return (T) connection.rmiBridge.getRemoteObject(connection, objectID, type);
    }
}
