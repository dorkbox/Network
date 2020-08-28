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
package dorkbox.network.rmi.messages

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import dorkbox.network.rmi.RmiClient
import dorkbox.network.serialization.KryoExtra
import java.lang.reflect.Proxy

/**
 * This is to manage serializing RMI objects across the wire...
 *
 * This is when the RMI client sends a java proxy object to a server, the server must receive the impl object
 *
 * NOTE:
 *   CLIENT: can never send the iface object, if it's RMI, it will send the java Proxy object instead.
 *   SERVER: can never send the iface object, it will always send the IMPL object instead (because of how kryo works)
 *
 *   **************************
 *   NOTE: This works because we TRICK kryo serialization by changing what the kryo ID serializer is on each end of the connection
 *   **************************
 *
 *   What we do is on the server, REWRITE the kryo ID for the impl so that it will send just the rmi ID instead of the object
 *   on the client, this SAME kryo ID must have this serializer as well, so the proxy object is re-assembled.
 *
 *   Kryo serialization works by inspecting the field VALUE type, not the field DEFINED type... So if you send an actual object, you must
 *   register specifically for the implementation object.
 *
 *
 * To recap:
 *  rmi-client: send proxy -> RmiIfaceSerializer -> network -> RmiIfaceSerializer -> impl object (rmi-server)
 *  rmi-server: send impl -> RmiImplSerializer -> network -> RmiImplSerializer -> proxy object (rmi-client)
 *
 *  During the handshake, if the impl object 'lives' on the CLIENT, then the client must tell the server that the iface ID must use this serializer.
 *  If the impl object 'lives' on the SERVER, then the server must tell the client about the iface ID
 */
class RmiClientSerializer : Serializer<Any>() {
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
        return connection.endPoint.rmiGlobalSupport.getImplObject(isGlobal, objectId, connection)
    }
}
