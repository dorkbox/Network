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
package dorkbox.network.connection;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.IESParameters;
import org.bouncycastle.crypto.params.IESWithCipherParameters;
import org.slf4j.Logger;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.factories.ReflectionSerializerFactory;
import com.esotericsoftware.kryo.factories.SerializerFactory;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.CollectionSerializer;
import com.esotericsoftware.kryo.serializers.FieldSerializer;
import com.esotericsoftware.kryo.util.IntMap;
import com.esotericsoftware.kryo.util.MapReferenceResolver;

import dorkbox.network.connection.ping.PingMessage;
import dorkbox.network.rmi.ClassDefinitions;
import dorkbox.network.rmi.InvocationHandlerSerializer;
import dorkbox.network.rmi.InvocationResultSerializer;
import dorkbox.network.rmi.InvokeMethod;
import dorkbox.network.rmi.InvokeMethodResult;
import dorkbox.network.rmi.InvokeMethodSerializer;
import dorkbox.network.rmi.RemoteObjectSerializer;
import dorkbox.network.rmi.RmiRegistration;
import dorkbox.network.util.RmiSerializationManager;
import dorkbox.objectPool.ObjectPool;
import dorkbox.objectPool.PoolableObject;
import dorkbox.util.Property;
import dorkbox.util.serialization.ArraysAsListSerializer;
import dorkbox.util.serialization.EccPrivateKeySerializer;
import dorkbox.util.serialization.EccPublicKeySerializer;
import dorkbox.util.serialization.FieldAnnotationAwareSerializer;
import dorkbox.util.serialization.IesParametersSerializer;
import dorkbox.util.serialization.IesWithCipherParametersSerializer;
import dorkbox.util.serialization.IgnoreSerialization;
import dorkbox.util.serialization.UnmodifiableCollectionsSerializer;
import io.netty.buffer.ByteBuf;

/**
 * Threads reading/writing, it messes up a single instance. it is possible to use a single kryo with the use of synchronize, however - that
 * defeats the point of multi-threaded
 */
@SuppressWarnings({"unused", "StaticNonFinalField"})
public
class CryptoSerializationManager implements dorkbox.network.util.CryptoSerializationManager, RmiSerializationManager {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(CryptoSerializationManager.class);

    /**
     * Specify if we want KRYO to use unsafe memory for serialization, or to use the ASM backend. Unsafe memory use is WAY faster, but is
     * limited to the "same endianess" on all endpoints, and unsafe DOES NOT work on android.
     */
    @Property
    public static boolean useUnsafeMemory = false;

    private static final String OBJECT_ID = "objectID";

    public static
    CryptoSerializationManager DEFAULT() {
        return DEFAULT(true, true);
    }

    public static
    CryptoSerializationManager DEFAULT(final boolean references, final boolean registrationRequired) {
        // ignore fields that have the "@IgnoreSerialization" annotation.
        Collection<Class<? extends Annotation>> marks = new ArrayList<Class<? extends Annotation>>();
        marks.add(IgnoreSerialization.class);
        SerializerFactory disregardingFactory = new FieldAnnotationAwareSerializer.Factory(marks, true);

        final CryptoSerializationManager serializationManager = new CryptoSerializationManager(references,
                                                                                               registrationRequired,
                                                                                               disregardingFactory);

        serializationManager.register(PingMessage.class);
        serializationManager.register(byte[].class);

        serializationManager.register(IESParameters.class, new IesParametersSerializer());
        serializationManager.register(IESWithCipherParameters.class, new IesWithCipherParametersSerializer());
        serializationManager.register(ECPublicKeyParameters.class, new EccPublicKeySerializer());
        serializationManager.register(ECPrivateKeyParameters.class, new EccPrivateKeySerializer());
        serializationManager.register(dorkbox.network.connection.registration.Registration.class); // must use full package name!

        // necessary for the transport of exceptions.
        serializationManager.register(ArrayList.class, new CollectionSerializer());
        serializationManager.register(StackTraceElement.class);
        serializationManager.register(StackTraceElement[].class);

        // extra serializers
        //noinspection ArraysAsListWithZeroOrOneArgument
        serializationManager.register(Arrays.asList("")
                                            .getClass(), new ArraysAsListSerializer());

        UnmodifiableCollectionsSerializer.registerSerializers(serializationManager);

        return serializationManager;
    }


    private static class ClassSerializer {
        final Class<?> clazz;
        final Serializer<?> serializer;

        ClassSerializer(final Class<?> clazz, final Serializer<?> serializer) {
            this.clazz = clazz;
            this.serializer = serializer;
        }
    }
    private static class ClassSerializer2 {
        final Class<?> clazz;
        final Serializer<?> serializer;
        final int id;

        ClassSerializer2(final Class<?> clazz, final Serializer<?> serializer, final int id) {
            this.clazz = clazz;
            this.serializer = serializer;
            this.id = id;
        }
    }
    private static class RemoteIfaceClass {
        private final Class<?> ifaceClass;

        RemoteIfaceClass(final Class<?> ifaceClass) {
            this.ifaceClass = ifaceClass;
        }
    }

    private static class RemoteImplClass {
        private final Class<?> ifaceClass;
        private final Class<?> implClass;

        RemoteImplClass(final Class<?> ifaceClass, final Class<?> implClass) {
            this.ifaceClass = ifaceClass;
            this.implClass = implClass;
        }
    }

    private boolean initialized = false;
    private final ObjectPool<KryoExtra> kryoPool;

    // used by operations performed during kryo initialization, which are by default package access (since it's an anon-inner class)
    // All registration MUST happen in-order of when the register(*) method was called, otherwise there are problems.
    // Object checking is performed during actual registration.
    private final List<Object> classesToRegister = new ArrayList<Object>();

    private boolean usesRmi = false;
    private InvokeMethodSerializer methodSerializer = null;
    private Serializer<Object> invocationSerializer = null;
    private RemoteObjectSerializer remoteObjectSerializer;

    // used to track which interface -> implementation, for use by RMI
    private final IntMap<Class<?>> rmiIdToImpl = new IntMap<Class<?>>();
    private final IntMap<Class<?>> rmiIdToIface = new IntMap<Class<?>>();
    private final ClassDefinitions classDefinitions = new ClassDefinitions();

    /**
     * By default, the serialization manager will compress+encrypt data to connections with remote IPs, and only compress on the loopback IP
     * <p>
     * @param references
     *                 If true, each appearance of an object in the graph after the first is stored as an integer ordinal. When set to true,
     *                 {@link MapReferenceResolver} is used. This enables references to the same object and cyclic graphs to be serialized,
     *                 but typically adds overhead of one byte per object. (should be true)
     *                 <p>
     * @param registrationRequired
     *                 If true, an exception is thrown when an unregistered class is encountered.
     *                 <p>
     *                 If false, when an unregistered class is encountered, its fully qualified class name will be serialized and the {@link
     *                 Kryo#addDefaultSerializer(Class, Class) default serializer} for the class used to serialize the object. Subsequent
     *                 appearances of the class within the same object graph are serialized as an int id.
     *                 <p>
     *                 Registered classes are serialized as an int id, avoiding the overhead of serializing the class name, but have the
     *                 drawback of needing to know the classes to be serialized up front.
     *                 <p>
     * @param factory
     *                 Sets the serializer factory to use when no {@link Kryo#addDefaultSerializer(Class, Class) default serializers} match
     *                 an object's type. Default is {@link ReflectionSerializerFactory} with {@link FieldSerializer}. @see
     *                 Kryo#newDefaultSerializer(Class)
     *                 <p>
     */
    public
    CryptoSerializationManager(final boolean references, final boolean registrationRequired, final SerializerFactory factory) {
        kryoPool = ObjectPool.NonBlockingSoftReference(new PoolableObject<KryoExtra>() {
            @Override
            public
            KryoExtra create() {
                synchronized (CryptoSerializationManager.this) {
                    KryoExtra kryo = new KryoExtra(CryptoSerializationManager.this);

                    // we HAVE to pre-allocate the KRYOs
                    boolean useAsm = !useUnsafeMemory;

                    kryo.getFieldSerializerConfig().setUseAsm(useAsm);
                    kryo.setRegistrationRequired(registrationRequired);

                    kryo.setReferences(references);

                    if (usesRmi) {
                        kryo.register(Class.class);
                        kryo.register(RmiRegistration.class);
                        kryo.register(InvokeMethod.class, methodSerializer);
                        kryo.register(Object[].class);

                        // This has to be for each kryo instance!
                        InvocationResultSerializer resultSerializer = new InvocationResultSerializer(kryo);
                        resultSerializer.removeField(OBJECT_ID);

                        kryo.register(InvokeMethodResult.class, resultSerializer);
                        kryo.register(InvocationHandler.class, invocationSerializer);
                    }


                    // All registration MUST happen in-order of when the register(*) method was called, otherwise there are problems.
                    for (Object clazz : classesToRegister) {
                        if (clazz instanceof Class) {
                            kryo.register((Class)clazz);
                        }
                        else if (clazz instanceof ClassSerializer) {
                            ClassSerializer classSerializer = (ClassSerializer) clazz;
                            kryo.register(classSerializer.clazz, classSerializer.serializer);
                        }
                        else if (clazz instanceof ClassSerializer2) {
                            ClassSerializer2 classSerializer = (ClassSerializer2) clazz;
                            kryo.register(classSerializer.clazz, classSerializer.serializer, classSerializer.id);
                        }
                        else if (clazz instanceof RemoteIfaceClass) {
                            // THIS IS DONE ON THE CLIENT
                            //  "server" means the side of the connection that has the implementation details for the RMI object
                            //  "client" means the side of the connection that accesses the "server" side object via a proxy object
                            //  the client will NEVER send this object to the server.
                            //  the server will ONLY send this object to the client
                            RemoteIfaceClass remoteIfaceClass = (RemoteIfaceClass) clazz;

                            // registers the interface, so that when it is read, it becomes a "magic" proxy object
                            kryo.register(remoteIfaceClass.ifaceClass, remoteObjectSerializer);
                        }
                        else if (clazz instanceof RemoteImplClass) {
                            // THIS IS DONE ON THE SERVER
                            //  "server" means the side of the connection that has the implementation details for the RMI object
                            //  "client" means the side of the connection that accesses the "server" side object via a proxy object
                            //  the client will NEVER send this object to the server.
                            //  the server will ONLY send this object to the client
                            RemoteImplClass remoteImplClass = (RemoteImplClass) clazz;

                            // registers the implementation, so that when it is written, it becomes a "magic" proxy object
                            int id = kryo.register(remoteImplClass.implClass, remoteObjectSerializer).getId();

                            // sets up the RMI, so when we receive the iface class from the client, we know what impl to use
                            rmiIdToImpl.put(id, remoteImplClass.implClass);
                            rmiIdToIface.put(id, remoteImplClass.ifaceClass);
                        }
                    }

                    if (factory != null) {
                        kryo.setDefaultSerializer(factory);
                    }

                    return kryo;
                }
            }
        });
    }

    /**
     * Registers the class using the lowest, next available integer ID and the {@link Kryo#getDefaultSerializer(Class) default serializer}.
     * If the class is already registered, the existing entry is updated with the new serializer. Registering a primitive also affects the
     * corresponding primitive wrapper.
     * <p>
     * Because the ID assigned is affected by the IDs registered before it, the order classes are registered is important when using this
     * method. The order must be the same at deserialization as it was for serialization.
     */
    @Override
    public synchronized
    RmiSerializationManager register(Class<?> clazz) {
        if (initialized) {
            logger.warn("Serialization manager already initialized. Ignoring duplicate register(Class) call.");
        }
        else if (clazz.isInterface()) {
            throw new IllegalArgumentException("Cannot register an interface for serialization. It must be an implementation.");
        } else {
            classesToRegister.add(clazz);
        }

        return this;
    }

    /**
     * Registers the class using the lowest, next available integer ID and the specified serializer. If the class is already registered, the
     * existing entry is updated with the new serializer. Registering a primitive also affects the corresponding primitive wrapper.
     * <p>
     * Because the ID assigned is affected by the IDs registered before it, the order classes are registered is important when using this
     * method. The order must be the same at deserialization as it was for serialization.
     */
    @Override
    public synchronized
    RmiSerializationManager register(Class<?> clazz, Serializer<?> serializer) {
        if (initialized) {
            logger.warn("Serialization manager already initialized. Ignoring duplicate register(Class, Serializer) call.");
        }
        else if (clazz.isInterface()) {
            throw new IllegalArgumentException("Cannot register an interface for serialization. It must be an implementation.");
        }
        else {
            classesToRegister.add(new ClassSerializer(clazz, serializer));
        }

        return this;
    }

    /**
     * Registers the class using the specified ID and serializer. If the ID is already in use by the same type, the old entry is
     * overwritten. If the ID is already in use by a different type, a {@link KryoException} is thrown. Registering a primitive also affects
     * the corresponding primitive wrapper.
     * <p>
     * IDs must be the same at deserialization as they were for serialization.
     *
     * @param id Must be >= 0. Smaller IDs are serialized more efficiently. IDs 0-8 are used by default for primitive types and
     *           String, but these IDs can be repurposed.
     */
    @Override
    public synchronized
    RmiSerializationManager register(Class<?> clazz, Serializer<?> serializer, int id) {
        if (initialized) {
            logger.warn("Serialization manager already initialized. Ignoring duplicate register(Class, Serializer, int) call.");
        }
        else if (clazz.isInterface()) {
            throw new IllegalArgumentException("Cannot register an interface for serialization. It must be an implementation.");
        }
        else {
            classesToRegister.add(new ClassSerializer2(clazz, serializer, id));
        }

        return this;
    }

    /**
     * Enable remote method invocation (RMI) for this connection. There is additional overhead to using RMI.
     * <p/>
     * Specifically, It costs at least 2 bytes more to use remote method invocation than just sending the parameters. If the method has a
     * return value which is not {@link dorkbox.network.rmi.RemoteObject#setAsync(boolean) ignored}, an extra byte is written. If the
     * type of a parameter is not final (primitives are final) then an extra byte is written for that parameter.
     */
    @Override
    public synchronized
    RmiSerializationManager registerRmiInterface(Class<?> ifaceClass) {
        if (initialized) {
            logger.warn("Serialization manager already initialized. Ignoring duplicate registerRemote(Class, Class) call.");
            return this;
        }

        else if (!ifaceClass.isInterface()) {
            throw new IllegalArgumentException("Cannot register an implementation for RMI access. It must be an interface.");
        }

        usesRmi = true;
        classesToRegister.add(new RemoteIfaceClass(ifaceClass));
        return this;
    }

    /**
     * This method overrides the interface -> implementation. This is so incoming proxy objects will get auto-changed into their correct
     * implementation type, so this side of the connection knows what to do with the proxy object.
     * <p>
     * NOTE: You must have ALREADY registered the implementation class. This method just enables the proxy magic.
     *
     * @throws IllegalArgumentException if the iface/impl have previously been overridden
     */
    @Override
    public synchronized
    <Iface, Impl extends Iface> RmiSerializationManager registerRmiImplementation(Class<Iface> ifaceClass, Class<Impl> implClass) {
        if (initialized) {
            logger.warn("Serialization manager already initialized. Ignoring duplicate registerRemote(Class, Class) call.");
            return this;
        }

        usesRmi = true;
        classesToRegister.add(new RemoteImplClass(ifaceClass, implClass));

        // this MUST BE UNIQUE otherwise unexpected things can happen.
        classDefinitions.set(ifaceClass, implClass);

        return this;
    }

    /**
     * Necessary to register classes for RMI, only called once if/when the RMI bridge is created.
     *
     * @return true if there are classes that have been registered for RMI
     */
    @Override
    public synchronized
    boolean initRmiSerialization() {
        if (!usesRmi) {
            return false;
        }

        if (initialized) {
            logger.warn("Serialization manager already initialized. Ignoring duplicate initRmiSerialization() call.");
            return true;
        }

        methodSerializer = new InvokeMethodSerializer();
        invocationSerializer = new InvocationHandlerSerializer(logger);
        remoteObjectSerializer = new RemoteObjectSerializer();

        return true;
    }

    /**
     * Called when initialization is complete. This is to prevent (and recognize) out-of-order class/serializer registration.
     */
    @Override
    public synchronized
    void finishInit() {
        initialized = true;
        // initialize the kryo pool with at least 1 kryo instance. This ALSO makes sure that all of our class registration is done correctly
        kryoPool.put(takeKryo());
    }

    @Override
    public synchronized
    boolean initialized() {
        return initialized;
    }

    /**
     * Gets the RMI interface based on the specified ID (which is the ID for the registered implementation)
     *
     * @param objectId ID of the registered interface, which will map to the corresponding implementation.
     * @return the implementation for the interface, or null
     */
    @Override
    public
    Class<?> getRmiIface(int objectId) {
        return rmiIdToIface.get(objectId);
    }

    /**
     * Gets the RMI implementation based on the specified interface
     *
     * @return the corresponding implementation
     */
    @Override
    public
    Class<?> getRmiImpl(Class<?> iface) {
        return classDefinitions.get(iface);
    }

    /**
     * Gets the RMI interface based on the specified implementation
     *
     * @return the corresponding interface
     */
    @Override
    public
    Class<?> getRmiIface(Class<?> implementation) {
        return classDefinitions.getReverse(implementation);
    }

    /**
     * Gets the RMI implementation based on the specified ID (which is the ID for the registered interface)
     *
     * @param objectId ID of the registered interface, which will map to the corresponding implementation.
     * @return the implementation for the interface, or null
     */
    @Override
    public
    Class<?> getRmiImpl(int objectId) {
        return rmiIdToImpl.get(objectId);
    }

    /**
     * @return takes a kryo instance from the pool.
     */
    @Override
    public
    KryoExtra takeKryo() {
        return kryoPool.take();
    }

    /**
     * Returns a kryo instance to the pool.
     */
    @Override
    public
    void returnKryo(KryoExtra kryo) {
        kryoPool.put(kryo);
    }

    /**
     * Determines if this buffer is encrypted or not.
     */
    public static
    boolean isEncrypted(final ByteBuf buffer) {
        // read off the magic byte
        byte magicByte = buffer.getByte(buffer.readerIndex());
        return (magicByte & KryoExtra.crypto) == KryoExtra.crypto;
    }

    /**
     * Writes the class and object using an available kryo instance
     */
    @Override
    public
    void writeFullClassAndObject(final Logger logger, final Output output, final Object value) throws IOException {
        KryoExtra kryo = kryoPool.take();
        boolean prev = false;

        try {
            prev = kryo.isRegistrationRequired();
            kryo.setRegistrationRequired(false);

            kryo.writeClassAndObject(output, value);
        } catch (Exception ex) {
            final String msg = "Unable to serialize buffer";
            if (logger != null) {
                logger.error(msg, ex);
            }
            throw new IOException(msg, ex);
        } finally {
            kryo.setRegistrationRequired(prev);
            kryoPool.put(kryo);
        }
    }

    /**
     * Returns a class read from the input
     */
    @Override
    public
    Object readFullClassAndObject(final Logger logger, final Input input) throws IOException {
        KryoExtra kryo = kryoPool.take();
        boolean prev = false;

        try {
            prev = kryo.isRegistrationRequired();
            kryo.setRegistrationRequired(false);

            return kryo.readClassAndObject(input);
        } catch (Exception ex) {
            final String msg = "Unable to deserialize buffer";
            if (logger != null) {
                logger.error(msg, ex);
            }
            throw new IOException(msg, ex);
        } finally {
            kryo.setRegistrationRequired(prev);
            kryoPool.put(kryo);
        }
    }

    /**
     * Waits until a kryo is available to write, using CAS operations to prevent having to synchronize.
     * <p>
     * No crypto and no sequence number
     * <p>
     * There is a small speed penalty if there were no kryo's available to use.
     */
    @Override
    public final
    void write(final ByteBuf buffer, final Object message) throws IOException {
        final KryoExtra kryo = kryoPool.take();
        try {
            kryo.write(buffer, message);
        } finally {
            kryoPool.put(kryo);
        }
    }

    /**
     * Reads an object from the buffer.
     * <p>
     * No crypto and no sequence number
     *
     * @param length should ALWAYS be the length of the expected object!
     */
    @Override
    public final
    Object read(final ByteBuf buffer, final int length) throws IOException {
        final KryoExtra kryo = kryoPool.take();
        try {
            return kryo.read(buffer);
        } finally {
            kryoPool.put(kryo);
        }
    }

    /**
     * Waits until a kryo is available to write, using CAS operations to prevent having to synchronize.
     * <p>
     * There is a small speed penalty if there were no kryo's available to use.
     */
    @Override
    public final
    void writeWithCrypto(final ConnectionImpl connection, final ByteBuf buffer, final Object message) throws IOException {
        final KryoExtra kryo = kryoPool.take();
        try {
            // we only need to encrypt when NOT on loopback, since encrypting on loopback is a waste of CPU
            if (connection.isLoopback()) {
                kryo.writeCompressed(connection, buffer, message);
            }
            else {
                kryo.writeCrypto(connection, buffer, message);
            }
        } finally {
            kryoPool.put(kryo);
        }
    }

    /**
     * Reads an object from the buffer.
     * <p>
     * Crypto + sequence number
     *
     * @param connection can be NULL
     * @param length should ALWAYS be the length of the expected object!
     */
    @SuppressWarnings("Duplicates")
    @Override
    public final
    Object readWithCrypto(final ConnectionImpl connection, final ByteBuf buffer, final int length) throws IOException {
        final KryoExtra kryo = kryoPool.take();
        try {
            // we only need to encrypt when NOT on loopback, since encrypting on loopback is a waste of CPU
            if (connection.isLoopback()) {
                return kryo.readCompressed(connection, buffer, length);
            }
            else {
                return kryo.readCrypto(connection, buffer, length);
            }
        } finally {
            kryoPool.put(kryo);
        }
    }
}
