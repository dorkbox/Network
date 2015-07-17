package dorkbox.network.util;

import com.esotericsoftware.kryo.ClassResolver;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Registration;
import com.esotericsoftware.kryo.factories.ReflectionSerializerFactory;
import com.esotericsoftware.kryo.factories.SerializerFactory;
import com.esotericsoftware.kryo.serializers.FieldSerializer;
import dorkbox.network.rmi.RmiRegisterClassesCallback;
import dorkbox.network.rmi.SerializerRegistration;

/**
 *
 */
public
interface RMISerializationManager {

    /**
     * <b>primarily used by RMI</b> It is not common to call this method!
     * <p/>
     * Registers the class using the lowest, next available integer ID and the
     * {@link SerializerRegistration (Class) serializer}. If the class
     * is already registered, the existing entry is updated with the new
     * serializer. Registering a primitive also affects the corresponding
     * primitive wrapper.
     * <p/>
     * Because the ID assigned is affected by the IDs registered before it, the
     * order classes are registered is important when using this method. The
     * order must be the same at deserialization as it was for serialization.
     */
    @SuppressWarnings({"rawtypes"})
    void registerSerializer(Class<?> clazz, SerializerRegistration registration);

    /**
     * Necessary to register classes for RMI, only called once when the RMI bridge is created.
     */
    void registerForRmiClasses(RmiRegisterClassesCallback callback);

    /**
     * If the class is not registered and {@link Kryo#setRegistrationRequired(boolean)} is false, it is
     * automatically registered using the {@link Kryo#addDefaultSerializer(Class, Class) default serializer}.
     *
     * @throws IllegalArgumentException if the class is not registered and {@link ConnectionSerializationManager#setRegistrationRequired(boolean)} is true.
     * @see ClassResolver#getRegistration(Class)
     */
    Registration getRegistration(Class<?> clazz);
}
