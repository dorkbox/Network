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
package dorkbox.network.rmi;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import dorkbox.network.connection.KryoExtra;

/**
 * This is required, because with RMI, it is possible that the IMPL and IFACE can have DIFFERENT class IDs, in which case, the "client" cannot read the correct
 * objects (because the IMPL class might not be registered, or that ID might be registered to a different class)
 */
public
class RmiRegistrationSerializer extends Serializer<RmiRegistration> {

    public
    RmiRegistrationSerializer() {
    }

    @Override
    public
    void write(Kryo kryo, Output output, RmiRegistration object) {
        output.writeBoolean(object.isRequest);
        output.writeInt(object.rmiId, true);
        output.writeInt(object.callbackId, true);

        int id = kryo.getRegistration(object.interfaceClass).getId();
        output.writeInt(id, true);

        if (object.remoteObject != null) {
            KryoExtra kryoExtra = (KryoExtra) kryo;
            id = kryoExtra.rmiSupport.getRegisteredId(object.remoteObject);
        } else {
            // can be < 0 or >= RmiBridge.INVALID_RMI (Integer.MAX_VALUE)
            id = -1;
        }
        output.writeInt(id, false);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public
    RmiRegistration read(Kryo kryo, Input input, Class implementationType) {

        boolean isRequest = input.readBoolean();
        int rmiId = input.readInt(true);
        int callbackId = input.readInt(true);

        int interfaceClassId = input.readInt(true);
        int remoteObjectId = input.readInt(false);


        // // We have to lookup the iface, since the proxy object requires it
        Class iface = kryo.getRegistration(interfaceClassId).getType();

        Object remoteObject = null;
        if (remoteObjectId >= 0 && remoteObjectId < RmiBridge.INVALID_RMI) {
            KryoExtra kryoExtra = (KryoExtra) kryo;
            remoteObject = kryoExtra.rmiSupport.getProxyObject(remoteObjectId, iface);
        }

        RmiRegistration rmiRegistration = new RmiRegistration(iface, rmiId, callbackId, remoteObject);
        rmiRegistration.isRequest = isRequest;

        return rmiRegistration;
    }
}
