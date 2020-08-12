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
package dorkbox.network.rmi.messages

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import dorkbox.network.connection.KryoExtra
import dorkbox.network.rmi.RmiClient
import java.lang.reflect.Proxy

/**
 * this is to manage serializing proxy object objects across the wire...
 * SO the server sends an RMI object, and the client reads an RMI object
 */
class RmiClientRequestSerializer : Serializer<Any>() {
    override fun write(kryo: Kryo, output: Output, proxyObject: Any) {
        val handler = Proxy.getInvocationHandler(proxyObject) as RmiClient
        output.writeBoolean(handler.isGlobal)
        output.writeInt(handler.rmiObjectId, true)
    }

    override fun read(kryo: Kryo, input: Input, type: Class<*>?): Any? {
        val isGlobal = input.readBoolean()
        val objectId = input.readInt(true)
        kryo as KryoExtra

        val connection = kryo.connection
        return connection.endPoint().rmiGlobalSupport.getImplObject(isGlobal, objectId, connection)
    }
}
