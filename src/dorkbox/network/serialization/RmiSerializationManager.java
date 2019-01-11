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
package dorkbox.network.serialization;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.Serializer;

import dorkbox.network.connection.KryoExtra;
import dorkbox.network.rmi.CachedMethod;
import dorkbox.util.serialization.SerializationManager;

public
interface RmiSerializationManager extends SerializationManager {

    /**
     * Registers the class using the lowest, next available integer ID and the {@link Kryo#getDefaultSerializer(Class) default serializer}.
     * If the class is already registered, the existing entry is updated with the new serializer.
     * <p>
     * Registering a primitive also affects the corresponding primitive wrapper.
     * <p>
     * Because the ID assigned is affected by the IDs registered before it, the order classes are registered is important when using this
     * method. The order must be the same at deserialization as it was for serialization.
     */
    @Override
    RmiSerializationManager register(Class<?> clazz);

    /**
     * Registers the class using the specified ID. If the ID is already in use by the same type, the old entry is overwritten. If the ID
     * is already in use by a different type, a {@link KryoException} is thrown.
     * <p>
     * Registering a primitive also affects the corresponding primitive wrapper.
     * <p>
     * IDs must be the same at deserialization as they were for serialization.
     *
     * @param id Must be >= 0. Smaller IDs are serialized more efficiently. IDs 0-8 are used by default for primitive types and String, but
     *           these IDs can be repurposed.
     */
    @Override
    RmiSerializationManager register(Class<?> clazz, int id);

    /**
     * Registers the class using the lowest, next available integer ID and the specified serializer. If the class is already registered,
     * the existing entry is updated with the new serializer.
     * <p>
     * Registering a primitive also affects the corresponding primitive wrapper.
     * <p>
     * Because the ID assigned is affected by the IDs registered before it, the order classes are registered is important when using this
     * method. The order must be the same at deserialization as it was for serialization.
     */
    @Override
    RmiSerializationManager register(Class<?> clazz, Serializer<?> serializer);

    /**
     * Registers the class using the specified ID and serializer. If the ID is already in use by the same type, the old entry is
     * overwritten. If the ID is already in use by a different type, a {@link KryoException} is thrown.
     * <p>
     * Registering a primitive also affects the corresponding primitive wrapper.
     * <p>
     * IDs must be the same at deserialization as they were for serialization.
     *
     * @param id Must be >= 0. Smaller IDs are serialized more efficiently. IDs 0-8 are used by default for primitive types and String, but
     *           these IDs can be repurposed.
     */
    @Override
    RmiSerializationManager register(Class<?> clazz, Serializer<?> serializer, int id);

    /**
     * Necessary to register classes for RMI, only called once when the RMI bridge is created.
     *
     * @return true if there are classes that have been registered for RMI
     */
    boolean initRmiSerialization();

    /**
     * @return takes a kryo instance from the pool.
     */
    KryoExtra takeKryo();

    /**
     * Returns a kryo instance to the pool.
     */
    void returnKryo(KryoExtra kryo);

    /**
     * @return true if the remote kryo registration are the same as our own
     */
    boolean verifyKryoRegistration(byte[] bytes);

    /**
     * @return the details of all registration IDs -> Class name used by kryo
     */
    byte[] getKryoRegistrationDetails();


    /**
     * Gets the RMI implementation based on the specified interface
     *
     * @return the corresponding implementation
     */
    Class<?> getRmiImpl(Class<?> iFace);

    /**
     * Enable a "remote client" to access methods and create objects (RMI) for this endpoint. This is NOT bi-directional, and this endpoint cannot access or \
     * create remote objects on the "remote client".
     * <p>
     * Calling this method with a null parameter for the implementation class is the same as calling {@link RmiSerializationManager#registerRmi(Class)}
     * <p>
     * There is additional overhead to using RMI.
     * <p>
     * Specifically, It costs at least 2 bytes more to use remote method invocation than just sending the parameters. If the method has a
     * return value which is not {@link dorkbox.network.rmi.RemoteObject#setAsync(boolean) ignored}, an extra byte is written.
     * <p>
     * If the type of a parameter is not final (primitives are final) then an extra byte is written for that parameter.
     * <p>
     *
     * @throws IllegalArgumentException if the iface/impl have previously been overridden
     */
    <Iface, Impl extends Iface> RmiSerializationManager registerRmi(Class<Iface> ifaceClass, Class<Impl> implClass);

    /**
     * Gets the cached methods for the specified class ID
     */
    CachedMethod[] getMethods(int classID);
}
