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
 */
package dorkbox.network.rmi.messages

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import dorkbox.network.connection.KryoExtra
import dorkbox.network.rmi.RmiServer

/**
 * This is required, because with RMI, it is possible that the IMPL and IFACE can have DIFFERENT class IDs, in which case, the "client" cannot read the correct
 * objects (because the IMPL class might not be registered, or that ID might be registered to a different class)
 */
class DOResponseSerializer : Serializer<DynamicObjectResponse>() {
    override fun write(kryo: Kryo, output: Output, objectResponse: DynamicObjectResponse) {
        output.writeInt(objectResponse.rmiId, true)
        output.writeInt(objectResponse.callbackId, true)

        var id = kryo.getRegistration(objectResponse.interfaceClass).id
        output.writeInt(id, true)

        id = if (objectResponse.remoteObject != null) {
            val kryoExtra = kryo as KryoExtra
            kryoExtra.rmiSupport.getRegisteredId(objectResponse.remoteObject)
        } else {
            // can be < 0 or >= RmiBridge.INVALID_RMI (Integer.MAX_VALUE)
            -1
        }

        output.writeInt(id, false)
    }

    override fun read(kryo: Kryo, input: Input, implementationType: Class<out DynamicObjectResponse>): DynamicObjectResponse {
        val rmiId = input.readInt(true)
        val callbackId = input.readInt(true)
        val interfaceClassId = input.readInt(true)
        val remoteObjectId = input.readInt(false)


        // // We have to lookup the iface, since the proxy object requires it
        val iface = kryo.getRegistration(interfaceClassId).type
        var remoteObject: Any? = null

        if (remoteObjectId >= 0 && remoteObjectId < RmiServer.INVALID_RMI) {
            val kryoExtra = kryo as KryoExtra
            val connection = kryoExtra.connection
            remoteObject = kryoExtra.rmiSupport.getProxyObject(connection, remoteObjectId, iface)
        }

        return DynamicObjectResponse(iface, rmiId, callbackId, remoteObject)
    }
}
