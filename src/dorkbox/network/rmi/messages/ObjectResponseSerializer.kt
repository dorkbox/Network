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
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.esotericsoftware.kryo.util.IdentityMap
import dorkbox.network.serialization.KryoExtra

/**
 * this is to manage serializing proxy object objects across the wire...
 *
 * SO the "rmi client" sends an RMI proxy object, and the "rmi server" reads an actual object
 *
 * Serializes an object registered with the RmiBridge so the receiving side gets a [RemoteObject] proxy rather than the bytes for the
 * serialized object.
 *
 * @author Nathan Sweet <misc></misc>@n4te.com>
 */
class ObjectResponseSerializer(private val rmiImplToIface: IdentityMap<Class<*>, Class<*>>) : Serializer<Any>(false) {
    override fun write(kryo: Kryo, output: Output, `object`: Any) {
        println(" FIX ObjectResponseSerializer ")
        val kryoExtra = kryo as KryoExtra
//        val id = kryoExtra.rmiSupport.getRegisteredId(`object`) //
//        output.writeInt(id, true)
        output.writeInt(0, true)
    }

    override fun read(kryo: Kryo, input: Input, implementationType: Class<*>): Any? {
        println(" FIX ObjectResponseSerializer ")
        val kryoExtra = kryo as KryoExtra
        val objectID = input.readInt(true)

        // We have to lookup the iface, since the proxy object requires it
        val iface = rmiImplToIface.get(implementationType)
        val connection = kryoExtra.connection
//        return kryoExtra.rmiSupport.getProxyObject(connection, objectID, iface)
        return null
    }
}

/// TODO: FIX THIS CLASS MAYBE!
