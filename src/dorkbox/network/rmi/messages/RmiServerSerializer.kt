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
/*
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
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import dorkbox.network.connection.Connection
import dorkbox.network.rmi.RemoteObjectStorage
import dorkbox.network.rmi.RmiSupportConnection
import dorkbox.network.serialization.KryoReader
import dorkbox.network.serialization.KryoWriter

/**
 * This is to manage serializing RMI objects across the wire...
 *
 * This is when the RMI server sends an impl object to a client, the client must receive a proxy object (instead of the impl object)
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
 *  rmi-server MUST registerRmi both the iface+impl
 *
 *  During the handshake, if the impl object 'lives' on the CLIENT, then the client must tell the server that the iface ID must use this serializer.
 *  If the impl object 'lives' on the SERVER, then the server must tell the client about the iface ID
 */
@Suppress("UNCHECKED_CAST")
class RmiServerSerializer<CONNECTION: Connection> : Serializer<Any>(false) {

    override fun write(kryo: Kryo, output: Output, `object`: Any) {
        val kryoExtra = kryo as KryoWriter<CONNECTION>
        val connection = kryoExtra.connection
        val rmi = connection.rmi
        // have to write what the rmi ID is ONLY. A remote object sent via a connection IS ONLY a connection-scope object!

        // check if we have saved it already
        var rmiId = connection.rmi.getId(`object`)
        if (rmiId == RemoteObjectStorage.INVALID_RMI) {
            // this means we have to save it. This object is cached so we can have an association between rmiID <-> object
            rmiId = rmi.saveImplObject(`object`)

            if (rmiId == RemoteObjectStorage.INVALID_RMI) {
                connection.logger.error("Unable to save $`object` for use as RMI!")
            }
        }

        output.writeInt(rmiId, true)
    }

    override fun read(kryo: Kryo, input: Input, interfaceClass: Class<*>): Any? {
        val kryoExtra = kryo as KryoReader<CONNECTION>
        val rmiId = input.readInt(true)

        val connection = kryoExtra.connection
        val endPoint = connection.endPoint
        val serialization = endPoint.serialization

        if (rmiId == RemoteObjectStorage.INVALID_RMI) {
            throw NullPointerException("RMI ID is invalid. Unable to use proxy object!")
        }

        // the rmi-server will have iface+impl id's
        // the rmi-client will have iface id's
        val rmi = connection.rmi as RmiSupportConnection<CONNECTION>

        return if (interfaceClass.isInterface) {
            // normal case. RMI only on 1 side
            val kryoId = serialization.rmiHolder.ifaceToId[interfaceClass]
            require(kryoId != null) { "Registration for $interfaceClass is invalid!!" }

            rmi.getProxyObject(false, connection, kryoId, rmiId, interfaceClass)
        } else {
            // BI-DIRECTIONAL RMI -- THIS IS NOT NORMAL!
            // this won't be an interface. It will be an impl (because of how RMI is setup)
            val kryoId = serialization.rmiHolder.implToId[interfaceClass]
            require(kryoId != null) { "Registration for $interfaceClass is invalid!!" }
            val iface = serialization.rmiHolder.idToIface[kryoId]

            rmi.getProxyObject(false, connection, kryoId, rmiId, iface)
        }
    }
}
