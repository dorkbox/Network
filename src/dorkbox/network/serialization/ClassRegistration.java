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

import com.esotericsoftware.kryo.Registration;
import com.esotericsoftware.kryo.Serializer;

import dorkbox.network.connection.KryoExtra;
import dorkbox.network.rmi.RemoteObjectSerializer;

class ClassRegistration {
    Class<?> clazz;
    int id;
    Serializer serializer;

    ClassRegistration(final Class<?> clazz) {
        this.clazz = clazz;
    }

    void register(final KryoExtra kryo, final RemoteObjectSerializer remoteObjectSerializer) {
        Registration registration;

        if (clazz.isInterface()) {
            registration = kryo.register(clazz, remoteObjectSerializer);

        }
        else {
            registration = kryo.register(clazz);
        }

        id = registration.getId();
        serializer = registration.getSerializer();
    }

    void log(final Logger logger) {
        logger.trace("Registered {} -> {}", id, clazz.getName());
    }
}
