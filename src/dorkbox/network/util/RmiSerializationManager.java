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
package dorkbox.network.util;

import com.esotericsoftware.kryo.Serializer;

import dorkbox.network.connection.KryoExtra;
import dorkbox.util.SerializationManager;

public
interface RmiSerializationManager extends SerializationManager {

    /**
     * Registers the class using the lowest, next available integer ID and the
     * {@link Kryo#getDefaultSerializer(Class) default serializer}. If the class
     * is already registered, the existing entry is updated with the new
     * serializer. Registering a primitive also affects the corresponding
     * primitive wrapper.
     * <p/>
     * Because the ID assigned is affected by the IDs registered before it, the
     * order classes are registered is important when using this method. The
     * order must be the same at deserialization as it was for serialization.
     */
    @Override
    RmiSerializationManager register(Class<?> clazz);

    /**
     * Registers the class using the lowest, next available integer ID and the
     * specified serializer. If the class is already registered, the existing
     * entry is updated with the new serializer. Registering a primitive also
     * affects the corresponding primitive wrapper.
     * <p/>
     * Because the ID assigned is affected by the IDs registered before it, the
     * order classes are registered is important when using this method. The
     * order must be the same at deserialization as it was for serialization.
     */
    @Override
    RmiSerializationManager register(Class<?> clazz, Serializer<?> serializer);

    /**
     * Registers the class using the specified ID and serializer. If the ID is
     * already in use by the same type, the old entry is overwritten. If the ID
     * is already in use by a different type, a {@link KryoException} is thrown.
     * Registering a primitive also affects the corresponding primitive wrapper.
     * <p/>
     * IDs must be the same at deserialization as they were for serialization.
     *
     * @param id Must be >= 0. Smaller IDs are serialized more efficiently. IDs
     *         0-8 are used by default for primitive types and String, but
     *         these IDs can be repurposed.
     */
    @Override
    RmiSerializationManager register(Class<?> clazz, Serializer<?> serializer, int id);


    /**
     * Necessary to register classes for RMI, only called once when the RMI bridge is created.
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
     * Gets the RMI implementation based on the specified ID (which is the ID for the registered interface)
     *
     * @param objectId ID of the registered interface, which will map to the corresponding implementation.
     *
     * @return the implementation for the interface, or null
     */
    Class<?> getRmiImpl(int objectId);

    /**
     * Enable remote method invocation (RMI) for this connection. There is additional overhead to using RMI.
     * <p/>
     * Specifically, It costs at least 2 bytes more to use remote method invocation than just sending the parameters. If the method has a
     * return value which is not {@link dorkbox.network.rmi.RemoteObject#setAsync(boolean) ignored}, an extra byte is written. If the
     * type of a parameter is not final (primitives are final) then an extra byte is written for that parameter.
     */
    RmiSerializationManager registerRmiInterface(Class<?> ifaceClass);



    /**
     * Objects that we want to use RMI with, must be accessed via an interface. This method configures the serialization of an
     * implementation to be serialized via the defined interface, as a RemoteObject (ie: proxy object). If the implementation class is
     * ALREADY registered, then it's registration will be overwritten by this one
     *
     * @param ifaceClass The interface used to access the remote object
     * @param implClass The implementation class of the interface
     */
    <Iface, Impl extends Iface> RmiSerializationManager registerRmiImplementation(Class<Iface> ifaceClass, Class<Impl> implClass);

    /**
     * This method overrides the interface -> implementation. This is so incoming proxy objects will get auto-changed into their correct
     * implementation type, so this side of the connection knows what to do with the proxy object.
     * <p>
     * NOTE: You must have ALREADY registered the implementation class. This method just enables the proxy magic.
     * This is for the "server" side, where "server" means the connection side where the implementation is used.
     *
     * @param ifaceClass The interface used to access the remote object
     * @param implClass The implementation class of the interface
     */
    // RmiSerializationManager rmiImplementation();
}
