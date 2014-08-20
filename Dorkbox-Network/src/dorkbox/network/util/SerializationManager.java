package dorkbox.network.util;

import io.netty.buffer.ByteBuf;

import com.esotericsoftware.kryo.ClassResolver;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.Registration;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.util.MapReferenceResolver;

import dorkbox.network.connection.Connection;
import dorkbox.network.rmi.RmiRegisterClassesCallback;
import dorkbox.network.rmi.SerializerRegistration;

/**
 * Threads reading/writing, it messes up a single instance.
 * it is possible to use a single kryo with the use of synchronize, however - that defeats the point of multi-threaded
 */
public interface SerializationManager {

    /**
     * If true, each appearance of an object in the graph after the first is
     * stored as an integer ordinal. When set to true,
     * {@link MapReferenceResolver} is used. This enables references to the same
     * object and cyclic graphs to be serialized, but typically adds overhead of
     * one byte per object. Default is true.
     *
     * @return The previous value.
     */
    public boolean setReferences(boolean references);

    /**
     * If true, an exception is thrown when an unregistered class is
     * encountered. Default is false.
     * <p>
     * If false, when an unregistered class is encountered, its fully qualified
     * class name will be serialized and the
     * {@link #addDefaultSerializer(Class, Class) default serializer} for the
     * class used to serialize the object. Subsequent appearances of the class
     * within the same object graph are serialized as an int id.
     * <p>
     * Registered classes are serialized as an int id, avoiding the overhead of
     * serializing the class name, but have the drawback of needing to know the
     * classes to be serialized up front.
     */
    public void setRegistrationRequired(boolean registrationRequired);

    /**
     * Registers the class using the lowest, next available integer ID and the
     * {@link Kryo#getDefaultSerializer(Class) default serializer}. If the class
     * is already registered, the existing entry is updated with the new
     * serializer. Registering a primitive also affects the corresponding
     * primitive wrapper.
     * <p>
     * Because the ID assigned is affected by the IDs registered before it, the
     * order classes are registered is important when using this method. The
     * order must be the same at deserialization as it was for serialization.
     */
    public void register(Class<?> clazz);

    /**
     * Registers the class using the lowest, next available integer ID and the
     * specified serializer. If the class is already registered, the existing
     * entry is updated with the new serializer. Registering a primitive also
     * affects the corresponding primitive wrapper.
     * <p>
     * Because the ID assigned is affected by the IDs registered before it, the
     * order classes are registered is important when using this method. The
     * order must be the same at deserialization as it was for serialization.
     */
    public void register(Class<?> clazz, Serializer<?> serializer);


    /**
     * Registers the class using the specified ID and serializer. If the ID is
     * already in use by the same type, the old entry is overwritten. If the ID
     * is already in use by a different type, a {@link KryoException} is thrown.
     * Registering a primitive also affects the corresponding primitive wrapper.
     * <p>
     * IDs must be the same at deserialization as they were for serialization.
     *
     * @param id
     *            Must be >= 0. Smaller IDs are serialized more efficiently. IDs
     *            0-8 are used by default for primitive types and String, but
     *            these IDs can be repurposed.
     */
    public Registration register (Class<?> type, Serializer<?> serializer, int id);

    /**
     * <b>primarily used by RMI</b> It is not common to call this method!
     * <p>
     * Registers the class using the lowest, next available integer ID and the
     * {@link Kryo#SerializerRegistration(Class) serializer}. If the class
     * is already registered, the existing entry is updated with the new
     * serializer. Registering a primitive also affects the corresponding
     * primitive wrapper.
     * <p>
     * Because the ID assigned is affected by the IDs registered before it, the
     * order classes are registered is important when using this method. The
     * order must be the same at deserialization as it was for serialization.
     */
    @SuppressWarnings({"rawtypes"})
    public void registerSerializer(Class<?> clazz, SerializerRegistration registration);

    /**
     * Determines if this buffer is encrypted or not.
     */
    public boolean isEncrypted(ByteBuf buffer);

    /**
     * Waits until a kryo is available to write, using CAS operations to prevent having to synchronize.
     *
     * No crypto and no sqeuence number
     *
     * There is a small speed penalty if there were no kryo's available to use.
     */
    public void write(ByteBuf buffer, Object message);

    /**
     * Waits until a kryo is available to write, using CAS operations to prevent having to synchronize.
     *
     * There is a small speed penalty if there were no kryo's available to use.
     */
    public void writeWithCryptoTcp(Connection connection, ByteBuf buffer, Object message);

    /**
     * Waits until a kryo is available to write, using CAS operations to prevent having to synchronize.
     *
     * There is a small speed penalty if there were no kryo's available to use.
     */
    public void writeWithCryptoUdp(Connection connection, ByteBuf buffer, Object message);

    /**
     * Reads an object from the buffer.
     *
     * No crypto and no sequence number
     *
     * @param connection can be NULL
     * @param length should ALWAYS be the length of the expected object!
     */
    public Object read(ByteBuf buffer, int length);

    /**
     * Reads an object from the buffer.
     *
     * Crypto + sequence number
     *
     * @param connection can be NULL
     * @param length should ALWAYS be the length of the expected object!
     */
    public Object readWithCryptoTcp(Connection connection, ByteBuf buffer, int length);

    /**
     * Reads an object from the buffer.
     *
     * Crypto + sequence number
     *
     * @param connection can be NULL
     * @param length should ALWAYS be the length of the expected object!
     */
    public Object readWithCryptoUdp(Connection connection, ByteBuf buffer, int length);

    /**
     * Necessary to register classes for RMI.
     */
    public void registerForRmiClasses(RmiRegisterClassesCallback callback);

    /**
     * If the class is not registered and {@link SerializationManager#setRegistrationRequired(boolean)} is false, it is
     * automatically registered using the {@link SerializationManager#addDefaultSerializer(Class, Class) default serializer}.
     *
     * @throws IllegalArgumentException
     *             if the class is not registered and {@link SerializationManager#setRegistrationRequired(boolean)} is true.
     * @see ClassResolver#getRegistration(Class)
     */
    public Registration getRegistration(Class<?> clazz);
}
