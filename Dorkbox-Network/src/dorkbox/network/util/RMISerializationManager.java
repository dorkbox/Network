package dorkbox.network.util;

import com.esotericsoftware.kryo.ClassResolver;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Registration;

/**
 *
 */
public
interface RMISerializationManager {

    /**
     * Necessary to register classes for RMI, only called once when the RMI bridge is created.
     */
    void initRmiSerialization();

    /**
     * If the class is not registered and {@link Kryo#setRegistrationRequired(boolean)} is false, it is
     * automatically registered using the {@link Kryo#addDefaultSerializer(Class, Class) default serializer}.
     *
     * @throws IllegalArgumentException if the class is not registered and registration is required.
     * @see ClassResolver#getRegistration(Class)
     */
    Registration getRegistration(Class<?> clazz);

    /**
     * Objects that we want to use RMI with, must be accessed via an interface. This method configures the serialization of an
     * implementation to be serialized via the defined interface, as a RemoteObject (ie: proxy object). If the implementation
     * class is ALREADY registered, then it's registration will be overwritten by this one
     *
     * @param ifaceClass The interface used to access the remote object
     * @param implClass The implementation class of the interface
     */
    <Iface, Impl extends Iface> void registerRemote(Class<Iface> ifaceClass, Class<Impl> implClass);
}
