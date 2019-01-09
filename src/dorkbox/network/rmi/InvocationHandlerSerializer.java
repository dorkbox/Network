/*
 * Copyright 2016 dorkbox, llc
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
package dorkbox.network.rmi;

import java.lang.reflect.Proxy;

import org.slf4j.Logger;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import dorkbox.network.connection.KryoExtra;

public
class InvocationHandlerSerializer extends Serializer<Object> {
    private final org.slf4j.Logger logger;

    public
    InvocationHandlerSerializer(final Logger logger) {
        this.logger = logger;
    }

    @Override
    public
    void write(Kryo kryo, Output output, Object object) {
        RmiProxyHandler handler = (RmiProxyHandler) Proxy.getInvocationHandler(object);
        output.writeInt(handler.rmiObjectId, true);
    }

    @Override
    @SuppressWarnings({"unchecked", "AutoBoxing"})
    public
    Object read(Kryo kryo, Input input, Class<Object> type) {
        int objectID = input.readInt(true);

        KryoExtra kryoExtra = (KryoExtra) kryo;
        Object object = kryoExtra.connection.getImplementationObject(objectID);

        if (object == null) {
            logger.error("Unknown object ID in RMI ObjectSpace: {}", objectID);
        }
        return object;
    }
}
