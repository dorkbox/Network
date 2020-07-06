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
package dorkbox.network.serialization

import com.esotericsoftware.kryo.Serializer
import dorkbox.network.connection.KryoExtra
import dorkbox.network.rmi.CachedMethod
import dorkbox.util.serialization.SerializationManager

interface NetworkSerializationManager : SerializationManager {
//    /**
//     * Waits until a kryo is available to write, using CAS operations to prevent having to synchronize.
//     *
//     *
//     * There is a small speed penalty if there were no kryo's available to use.
//     */
//    @Throws(IOException::class)
//    fun write(connection: Connection_, message: Any)

//    /**
//     * Reads an object from the buffer.
//     *
//     * @param length should ALWAYS be the length of the expected object!
//     */
//    @Throws(IOException::class)
//    fun read(connection: Connection_, length: Int): Any
//
//    /**
//     * Waits until a kryo is available to write, using CAS operations to prevent having to synchronize.
//     *
//     *
//     * There is a small speed penalty if there were no kryo's available to use.
//     */
//    @Throws(IOException::class)
//    fun writeWithCompression(connection: Connection_, message: Any)
//
//    /**
//     * Reads an object from the buffer.
//     *
//     * @param length should ALWAYS be the length of the expected object!
//     */
//    @Throws(IOException::class)
//    fun readWithCompression(connection: Connection_, length: Int): Any

//    /**
//     * Waits until a kryo is available to write, using CAS operations to prevent having to synchronize.
//     *
//     *
//     * There is a small speed penalty if there were no kryo's available to use.
//     */
//    @Throws(IOException::class)
//    fun writeWithCrypto(connection: Connection_, message: Any)
//
//    /**
//     * Reads an object from the buffer.
//     *
//     * @param length should ALWAYS be the length of the expected object!
//     */
//    @Throws(IOException::class)
//    fun readWithCrypto(connection: Connection_, length: Int): Any

    /**
     * Registers the class using the lowest, next available integer ID and the [default serializer][Kryo.getDefaultSerializer].
     * If the class is already registered, the existing entry is updated with the new serializer.
     *
     *
     * Registering a primitive also affects the corresponding primitive wrapper.
     *
     *
     * Because the ID assigned is affected by the IDs registered before it, the order classes are registered is important when using this
     * method. The order must be the same at deserialization as it was for serialization.
     */
    override fun <T> register(clazz: Class<T>): NetworkSerializationManager

    /**
     * Registers the class using the specified ID. If the ID is already in use by the same type, the old entry is overwritten. If the ID
     * is already in use by a different type, a [KryoException] is thrown.
     *
     *
     * Registering a primitive also affects the corresponding primitive wrapper.
     *
     *
     * IDs must be the same at deserialization as they were for serialization.
     *
     * @param id Must be >= 0. Smaller IDs are serialized more efficiently. IDs 0-8 are used by default for primitive types and String, but
     * these IDs can be repurposed.
     */
    override fun <T> register(clazz: Class<T>, id: Int): NetworkSerializationManager

    /**
     * Registers the class using the lowest, next available integer ID and the specified serializer. If the class is already registered,
     * the existing entry is updated with the new serializer.
     *
     *
     * Registering a primitive also affects the corresponding primitive wrapper.
     *
     *
     * Because the ID assigned is affected by the IDs registered before it, the order classes are registered is important when using this
     * method. The order must be the same at deserialization as it was for serialization.
     */
    override fun <T> register(clazz: Class<T>, serializer: Serializer<T>): NetworkSerializationManager

    /**
     * Registers the class using the specified ID and serializer. If the ID is already in use by the same type, the old entry is
     * overwritten. If the ID is already in use by a different type, a [KryoException] is thrown.
     *
     *
     * Registering a primitive also affects the corresponding primitive wrapper.
     *
     *
     * IDs must be the same at deserialization as they were for serialization.
     *
     * @param id Must be >= 0. Smaller IDs are serialized more efficiently. IDs 0-8 are used by default for primitive types and String, but
     * these IDs can be repurposed.
     */
    override fun <T> register(clazz: Class<T>, serializer: Serializer<T>, id: Int): NetworkSerializationManager

    /**
     * @return takes a kryo instance from the pool.
     */
    fun takeKryo(): KryoExtra

    /**
     * Returns a kryo instance to the pool.
     */
    fun returnKryo(kryo: KryoExtra)

    /**
     * @return true if the remote kryo registration are the same as our own
     */
    fun verifyKryoRegistration(clientBytes: ByteArray): Boolean

    /**
     * @return the details of all registration IDs -> Class name used by kryo
     */
    fun getKryoRegistrationDetails(): ByteArray

    /**
     * Creates a NEW object implementation based on the KRYO interface ID.
     *
     * @return the corresponding implementation object
     */
    fun createRmiObject(interfaceClassId: Int): Any

    /**
     * Returns the Kryo class registration ID
     */
    fun getClassId(iFace: Class<*>): Int

    /**
     * Returns the Kryo class from a registration ID
     */
    fun getClassFromId(interfaceClassId: Int): Class<*>

    /**
     * Gets the RMI implementation based on the specified interface
     *
     * @return the corresponding implementation
     */
    fun <T> getRmiImpl(iFace: Class<T>): Class<T>

    /**
     * There is additional overhead to using RMI.
     *
     * Specifically, It costs at least 2 bytes more to use remote method invocation than just sending the parameters. If the method has a
     * return value which is not [ignored][dorkbox.network.rmi.RemoteObject.setAsync], an extra byte is written.
     * If the type of a parameter is not final (primitives are final) then an extra byte is written for that parameter.
     *
     *
     * Enable a "remote endpoint" to access methods and create objects (RMI) for this endpoint.
     *
     * This is NOT bi-directional, and this endpoint cannot access or create remote objects on the "remote client".
     *
     * @throws IllegalArgumentException if the iface/impl have previously been overridden
     */
    fun <Iface, Impl : Iface> registerRmi(ifaceClass: Class<Iface>, implClass: Class<Impl>): NetworkSerializationManager

    /**
     * Gets the cached methods for the specified class ID
     */
    fun getMethods(classId: Int): Array<CachedMethod>


    /**
     * Called when initialization is complete. This is to prevent (and recognize) out-of-order class/serializer registration.
     */
    fun finishInit(endPointClass: Class<*>)

    /**
     * @return true if our initialization is complete. Some registrations (in the property store, for example) always register for client
     * and server, even if in the same JVM. This only attempts to register once.
     */
    fun initialized(): Boolean
}
