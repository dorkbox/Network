/*
 * Copyright 2019 dorkbox, llc
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
package dorkbox.network.serialization;

import org.slf4j.Logger;

import dorkbox.network.connection.CryptoConnection;
import dorkbox.network.connection.KryoExtra;
import dorkbox.network.rmi.RemoteObjectSerializer;

class ClassSerializerRmi extends ClassRegistration {
    final Class<?> implClass;

    ClassSerializerRmi(final Class<?> ifaceClass, final Class<?> implClass) {
        super(ifaceClass);
        this.implClass = implClass;
    }

    <C extends CryptoConnection> void register(final KryoExtra<C> kryo, final RemoteObjectSerializer remoteObjectSerializer) {
        this.id = kryo.register(clazz, remoteObjectSerializer).getId();
        this.serializer = remoteObjectSerializer;
    }

    void log(final Logger logger) {
        logger.trace("Registered {} -> (RMI) {}", id, implClass.getName());
    }
}
