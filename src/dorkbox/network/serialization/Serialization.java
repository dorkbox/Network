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

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
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
import com.esotericsoftware.kryo.util.IdentityMap;
import com.esotericsoftware.kryo.util.IntMap;
import com.esotericsoftware.kryo.util.MapReferenceResolver;
import com.esotericsoftware.kryo.util.Util;

import dorkbox.network.connection.CryptoConnection;
import dorkbox.network.connection.KryoExtra;
import dorkbox.network.connection.ping.PingMessage;
import dorkbox.network.pipeline.ByteBufInput;
import dorkbox.network.pipeline.ByteBufOutput;
import dorkbox.network.rmi.CachedMethod;
import dorkbox.network.rmi.InvocationHandlerSerializer;
import dorkbox.network.rmi.InvocationResultSerializer;
import dorkbox.network.rmi.InvokeMethod;
import dorkbox.network.rmi.InvokeMethodResult;
import dorkbox.network.rmi.InvokeMethodSerializer;
import dorkbox.network.rmi.RemoteObjectSerializer;
import dorkbox.network.rmi.RmiRegistration;
import dorkbox.network.rmi.RmiRegistrationSerializer;
import dorkbox.network.rmi.RmiUtils;
import dorkbox.objectPool.ObjectPool;
import dorkbox.objectPool.PoolableObject;
import dorkbox.util.Property;
import dorkbox.util.serialization.ArraysAsListSerializer;
import dorkbox.util.serialization.EccPrivateKeySerializer;
import dorkbox.util.serialization.EccPublicKeySerializer;
import dorkbox.util.serialization.IesParametersSerializer;
import dorkbox.util.serialization.IesWithCipherParametersSerializer;
import dorkbox.util.serialization.UnmodifiableCollectionsSerializer;
import io.netty.bootstrap.DatagramCloseMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

/**
 * Threads reading/writing, it messes up a single instance. it is possible to use a single kryo with the use of synchronize, however - that
 * defeats the point of having multi-threaded serialization.
 * <p>
 * Additionally, this serialization manager will register the entire class+interface hierarchy for an object. If you want to specify a
 * serialization scheme for a specific class in an objects hierarchy, you must register that first.
 */
@SuppressWarnings({"unused", "StaticNonFinalField"})
public
class Serialization<C extends CryptoConnection> implements CryptoSerializationManager<C> {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(Serialization.class.getSimpleName());

    /**
     * Specify if we want KRYO to use unsafe memory for serialization, or to use the ASM backend. Unsafe memory use is WAY faster, but is
     * limited to the "same endianess" on all endpoints, and unsafe DOES NOT work on android.
     */
    @Property
    public static boolean useUnsafeMemory = false;

    public static
    Serialization DEFAULT() {
        return DEFAULT(true, true, null);
    }

    /**
     * By default, the serialization manager will compress+encrypt data to connections with remote IPs, and only compress on the loopback IP
     * <p>
     * Additionally, this serialization manager will register the entire class+interface hierarchy for an object. If you want to specify a
     * serialization scheme for a specific class in an objects hierarchy, you must register that first.
     *
     * @param references If true, each appearance of an object in the graph after the first is stored as an integer ordinal. When set to true,
     *         {@link MapReferenceResolver} is used. This enables references to the same object and cyclic graphs to be serialized,
     *         but typically adds overhead of one byte per object. (should be true)
     * @param registrationRequired If true, an exception is thrown when an unregistered class is encountered.
     *         <p>
     *         If false, when an unregistered class is encountered, its fully qualified class name will be serialized and the {@link
     *         Kryo#addDefaultSerializer(Class, Class) default serializer} for the class used to serialize the object. Subsequent
     *         appearances of the class within the same object graph are serialized as an int id.
     *         <p>
     *         Registered classes are serialized as an int id, avoiding the overhead of serializing the class name, but have the
     *         drawback of needing to know the classes to be serialized up front.
     * @param factory Sets the serializer factory to use when no {@link Kryo#addDefaultSerializer(Class, Class) default serializers} match
     *         an object's type. Default is {@link ReflectionSerializerFactory} with {@link FieldSerializer}. @see
     *         Kryo#newDefaultSerializer(Class)
     */
    public static
    <C extends CryptoConnection> Serialization<C> DEFAULT(final boolean references, final boolean registrationRequired, final SerializerFactory factory) {

        final Serialization<C> serialization = new Serialization<C>(references,
                                                              registrationRequired,
                                                              factory);

        serialization.register(PingMessage.class);
        serialization.register(DatagramCloseMessage.class);
        serialization.register(byte[].class);

        serialization.register(IESParameters.class, new IesParametersSerializer());
        serialization.register(IESWithCipherParameters.class, new IesWithCipherParametersSerializer());
        serialization.register(ECPublicKeyParameters.class, new EccPublicKeySerializer());
        serialization.register(ECPrivateKeyParameters.class, new EccPrivateKeySerializer());
        serialization.register(ClassRegistration.class);
        serialization.register(dorkbox.network.connection.registration.Registration.class); // must use full package name!

        // necessary for the transport of exceptions.
        serialization.register(ArrayList.class, new CollectionSerializer());
        serialization.register(StackTraceElement.class);
        serialization.register(StackTraceElement[].class);

        // extra serializers
        //noinspection ArraysAsListWithZeroOrOneArgument
        serialization.register(Arrays.asList("").getClass(), new ArraysAsListSerializer());

        UnmodifiableCollectionsSerializer.registerSerializers(serialization);

        return serialization;
    }

    public static
    Serializer resolveSerializerInstance(Kryo k, Class superClass, Class<? extends Serializer> serializerClass) {
        try {
            try {
                return serializerClass.getConstructor(Kryo.class, Class.class)
                                      .newInstance(k, superClass);
            } catch (NoSuchMethodException ex1) {
                try {
                    return serializerClass.getConstructor(Kryo.class)
                                          .newInstance(k);
                } catch (NoSuchMethodException ex2) {
                    try {
                        return serializerClass.getConstructor(Class.class)
                                              .newInstance(superClass);
                    } catch (NoSuchMethodException ex3) {
                        return serializerClass.newInstance();
                    }
                }
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Unable to create serializer \"" + serializerClass.getName() + "\" for class: " + superClass.getName(), ex);
        }
    }
    private boolean initialized = false;
    private final ObjectPool<KryoExtra<C>> kryoPool;

    private final boolean registrationRequired;

    // used by operations performed during kryo initialization, which are by default package access (since it's an anon-inner class)
    // All registration MUST happen in-order of when the register(*) method was called, otherwise there are problems.
    // Object checking is performed during actual registration.
    private final List<ClassRegistration> classesToRegister = new ArrayList<ClassRegistration>();
    private ClassRegistration[] mergedRegistrations;
    private byte[] savedRegistrationDetails;

    private boolean usesRmi = false;

    private InvokeMethodSerializer methodSerializer = null;
    private Serializer<Object> invocationSerializer = null;

    // the purpose of the method cache, is to accelerate looking up methods for specific class
    private IntMap<CachedMethod[]> methodCache;
    private RemoteObjectSerializer remoteObjectSerializer;
    private RmiRegistrationSerializer rmiRegistrationSerializer;

    private final IdentityMap<Class<?>, Class<?>> rmiIfaceToImpl = new IdentityMap<Class<?>, Class<?>>();
    private final IdentityMap<Class<?>, Class<?>> rmiImplToIface = new IdentityMap<Class<?>, Class<?>>();


    // reflectASM doesn't work on android
    private final boolean useAsm = !useUnsafeMemory && !Util.IS_ANDROID;
    private Logger wireReadLogger;
    private Logger wireWriteLogger;

    /**
     * By default, the serialization manager will compress+encrypt data to connections with remote IPs, and only compress on the loopback IP
     * <p>
     * Additionally, this serialization manager will register the entire class+interface hierarchy for an object. If you want to specify a
     * serialization scheme for a specific class in an objects hierarchy, you must register that first.
     *
     * @param references If true, each appearance of an object in the graph after the first is stored as an integer ordinal. When set to true,
     *         {@link MapReferenceResolver} is used. This enables references to the same object and cyclic graphs to be serialized,
     *         but typically adds overhead of one byte per object. (should be true)
     *         <p>
     * @param registrationRequired If true, an exception is thrown when an unregistered class is encountered.
     *         <p>
     *         If false, when an unregistered class is encountered, its fully qualified class name will be serialized and the {@link
     *         Kryo#addDefaultSerializer(Class, Class) default serializer} for the class used to serialize the object. Subsequent
     *         appearances of the class within the same object graph are serialized as an int id.
     *         <p>
     *         Registered classes are serialized as an int id, avoiding the overhead of serializing the class name, but have the
     *         drawback of needing to know the classes to be serialized up front.
     *         <p>
     * @param factory Sets the serializer factory to use when no {@link Kryo#addDefaultSerializer(Class, Class) default serializers} match
     *         an object's type. Default is {@link ReflectionSerializerFactory} with {@link FieldSerializer}. @see
     *         Kryo#newDefaultSerializer(Class)
     */
    public
    Serialization(final boolean references,
                  final boolean registrationRequired,
                  final SerializerFactory factory) {

        this.registrationRequired = registrationRequired;

        this.kryoPool = ObjectPool.NonBlockingSoftReference(new PoolableObject<KryoExtra<C>>() {
            @Override
            public
            KryoExtra<C> create() {
                synchronized (Serialization.this) {
                    // we HAVE to pre-allocate the KRYOs
                    KryoExtra<C> kryo = new KryoExtra<C>(Serialization.this);

                    kryo.getFieldSerializerConfig().setUseAsm(useAsm);
                    kryo.setRegistrationRequired(registrationRequired);

                    kryo.setReferences(references);

                    if (usesRmi) {
                        kryo.register(Class.class);
                        kryo.register(RmiRegistration.class, rmiRegistrationSerializer);
                        kryo.register(InvokeMethod.class, methodSerializer);
                        kryo.register(Object[].class);

                        // This has to be for each kryo instance!
                        InvocationResultSerializer resultSerializer = new InvocationResultSerializer(kryo);
                        resultSerializer.removeField("rmiObjectId");

                        kryo.register(InvokeMethodResult.class, resultSerializer);
                        kryo.register(InvocationHandler.class, invocationSerializer);
                    }

                    // All registration MUST happen in-order of when the register(*) method was called, otherwise there are problems.
                    // additionally, if a class registered is an INTERFACE, then we register it as an RMI class.
                    for (ClassRegistration clazz : classesToRegister) {
                        clazz.register(kryo, remoteObjectSerializer);
                    }

                    if (factory != null) {
                        kryo.setDefaultSerializer(factory);
                    }

                    return kryo;
                }
            }
        });
    }

    private static
    ArrayList<Class<?>> getHierarchy(Class<?> clazz) {
        final ArrayList<Class<?>> allClasses = new ArrayList<Class<?>>();
        LinkedList<Class<?>> parseClasses = new LinkedList<Class<?>>();
        parseClasses.add(clazz);

        Class<?> nextClass;
        while (!parseClasses.isEmpty()) {
            nextClass = parseClasses.removeFirst();
            allClasses.add(nextClass);

            // add all interfaces from our class (if any)
            parseClasses.addAll(Arrays.asList(nextClass.getInterfaces()));

            Class<?> superclass = nextClass.getSuperclass();
            if (superclass != null) {
                parseClasses.add(superclass);
            }
        }

        // remove the first class, because we don't need it
        allClasses.remove(clazz);

        return allClasses;
    }

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
    public synchronized
    RmiSerializationManager register(Class<?> clazz) {
        if (initialized) {
            logger.warn("Serialization manager already initialized. Ignoring duplicate register(Class) call.");
        }
        else {
            if (clazz.isInterface()) {
                // we have to enable RMI
                usesRmi = true;
            }

            classesToRegister.add(new ClassRegistration(clazz));
        }

        return this;
    }

    /**
     * Registers the class using the specified ID. If the ID is already in use by the same type, the old entry is overwritten. If the ID
     * is already in use by a different type, a {@link KryoException} is thrown.
     * <p>
     * Registering a primitive also affects the corresponding primitive wrapper.
     * <p>
     * IDs must be the same at deserialization as they were for serialization.
     *
     * @param id Must be >= 0. Smaller IDs are serialized more efficiently. IDs 0-8 are used by default for primitive types and String, but
     *         these IDs can be repurposed.
     */
    @Override
    public synchronized
    RmiSerializationManager register(Class<?> clazz, int id) {
        if (initialized) {
            logger.warn("Serialization manager already initialized. Ignoring duplicate register(Class, int) call.");
        }
        else if (clazz.isInterface()) {
            throw new IllegalArgumentException("Cannot register an interface with specified ID for serialization. It must be an implementation.");
        }
        else {
            classesToRegister.add(new ClassSerializer1(clazz, id));
        }

        return this;
    }

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
    public synchronized
    RmiSerializationManager register(Class<?> clazz, Serializer<?> serializer) {
        if (initialized) {
            logger.warn("Serialization manager already initialized. Ignoring duplicate register(Class, Serializer) call.");
        }
        else if (clazz.isInterface()) {
            throw new IllegalArgumentException("Cannot register an interface with specified serializer for serialization. It must be an implementation.");
        }
        else {
            classesToRegister.add(new ClassSerializer(clazz, serializer));
        }

        return this;
    }

    /**
     * Registers the class using the specified ID and serializer. If the ID is already in use by the same type, the old entry is
     * overwritten. If the ID is already in use by a different type, a {@link KryoException} is thrown.
     * <p>
     * Registering a primitive also affects the corresponding primitive wrapper.
     * <p>
     * IDs must be the same at deserialization as they were for serialization.
     *
     * @param id Must be >= 0. Smaller IDs are serialized more efficiently. IDs 0-8 are used by default for primitive types and String, but
     *         these IDs can be repurposed.
     */
    @Override
    public synchronized
    RmiSerializationManager register(Class<?> clazz, Serializer<?> serializer, int id) {
        if (initialized) {
            logger.warn("Serialization manager already initialized. Ignoring duplicate register(Class, Serializer, int) call.");
        }
        else if (clazz.isInterface()) {
            throw new IllegalArgumentException("Cannot register an interface with specified ID/serializer for serialization. It must be an implementation.");
        }
        else {
            classesToRegister.add(new ClassSerializer2(clazz, serializer, id));
        }

        return this;
    }

    /**
     * Enable a "remote client" to access methods and create objects (RMI) for this endpoint. This is NOT bi-directional, and this endpoint cannot access or
     * create remote objects on the "remote client".
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
    @Override
    public synchronized
    <Iface, Impl extends Iface> RmiSerializationManager registerRmi(Class<Iface> ifaceClass, Class<Impl> implClass) {
        if (initialized) {
            logger.warn("Serialization manager already initialized. Ignoring duplicate registerRmiImplementation(Class, Class) call.");
            return this;
        }

        if (!ifaceClass.isInterface()) {
            throw new IllegalArgumentException("Cannot register an implementation for RMI access. It must be an interface.");
        }
        if (implClass.isInterface()) {
            throw new IllegalArgumentException("Cannot register an interface for RMI implementations. It must be an implementation.");
        }

        usesRmi = true;
        classesToRegister.add(new ClassSerializerRmi(ifaceClass, implClass));

        // rmiIfaceToImpl tells us, "the server" how to create a (requested) remote object
        // this MUST BE UNIQUE otherwise unexpected and BAD things can happen.
        Class<?> a = this.rmiIfaceToImpl.put(ifaceClass, implClass);
        Class<?> b = this.rmiImplToIface.put(implClass, ifaceClass);

        if (a != null || b != null) {
            throw new IllegalArgumentException("Unable to override interface (" + ifaceClass + ") and implementation (" + implClass + ") " +
                                               "because they have already been overridden by something else. It is critical that they are" +
                                               " both unique per JVM");
        }

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
        remoteObjectSerializer = new RemoteObjectSerializer(rmiImplToIface);
        rmiRegistrationSerializer = new RmiRegistrationSerializer();
        methodCache = new IntMap<CachedMethod[]>();

        return true;
    }

    /**
     * Called when initialization is complete. This is to prevent (and recognize) out-of-order class/serializer registration. If an ID
     * is already in use by a different type, a {@link KryoException} is thrown.
     */
    @Override
    public synchronized
    void finishInit(final Logger wireReadLogger, final Logger wireWriteLogger) {
        this.wireReadLogger = wireReadLogger;
        this.wireWriteLogger = wireWriteLogger;

        initialized = true;

        // initialize the kryo pool with at least 1 kryo instance. This ALSO makes sure that all of our class registration is done
        // correctly and (if not) we are are notified on the initial thread (instead of on the network update thread)
        KryoExtra<C> kryo = kryoPool.take();
        try {

            // now MERGE all of the registrations (since we can have registrations overwrite newer/specific registrations

            int size = classesToRegister.size();
            ArrayList<ClassRegistration> mergedRegistrations = new ArrayList<ClassRegistration>();

            for (ClassRegistration registration : classesToRegister) {
                Class<?> clazz = registration.clazz;
                int id = registration.id;

                // if we ALREADY contain this registration (based ONLY on ID), then overwrite the existing one and REMOVE the current one
                boolean found = false;
                for (int index = 0; index < mergedRegistrations.size(); index++) {
                    final ClassRegistration existingRegistration = mergedRegistrations.get(index);
                    if (existingRegistration.id == id) {
                        mergedRegistrations.set(index, registration);
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    mergedRegistrations.add(registration);
                }
            }

            // now all of the registrations are IN ORDER and MERGED
            this.mergedRegistrations = mergedRegistrations.toArray(new ClassRegistration[0]);

            Object[][] registrationDetails = new Object[mergedRegistrations.size()][2];

            for (int i = 0; i < mergedRegistrations.size(); i++) {
                final ClassRegistration registration = mergedRegistrations.get(i);
                registration.log(logger);

                // now save all of the registration IDs for quick verification/access
                registrationDetails[i] = new Object[] {registration.id, registration.clazz.getName()};



                // now we have to manage caching methods (only as necessary)
                if (registration.clazz.isInterface()) {
                    // can be a normal class or an RMI class...
                    Class<?> implClass = null;

                    if (registration instanceof ClassSerializerRmi) {
                        implClass = ((ClassSerializerRmi) registration).implClass;
                    }

                    CachedMethod[] cachedMethods = RmiUtils.getCachedMethods(Serialization.logger, kryo, useAsm, registration.clazz, implClass, registration.id);
                    methodCache.put(registration.id, cachedMethods);
                }
            }


            // save this as a byte array (so registration is faster)
            ByteBuf buffer = Unpooled.buffer();
            ByteBufOutput writer = new ByteBufOutput();
            writer.setBuffer(buffer);

            kryo.setRegistrationRequired(false);
            kryo.writeObject(writer, registrationDetails);

            savedRegistrationDetails = new byte[buffer.writerIndex()];
            buffer.getBytes(0, savedRegistrationDetails);

            buffer.release();
        } finally {
            if (registrationRequired) {
                kryo.setRegistrationRequired(true);
            }

            kryoPool.put(kryo);
        }
    }

    /**
     * @return true if kryo registration is required for all classes sent over the wire
     */
    @Override
    public
    boolean verifyKryoRegistration(byte[] otherRegistrationData) {
        // verify the registration IDs if necessary with our own. The CLIENT does not verify anything, only the server!
        byte[] kryoRegistrationDetails = savedRegistrationDetails;
        boolean equals = java.util.Arrays.equals(kryoRegistrationDetails, otherRegistrationData);
        if (equals) {
            return true;
        }


        // now we need to figure out WHAT was screwed up so we know what to fix
        KryoExtra kryo = takeKryo();

        ByteBuf byteBuf = Unpooled.wrappedBuffer(otherRegistrationData);

        try {
            ByteBufInput reader = new ByteBufInput();
            reader.setBuffer(byteBuf);

            kryo.setRegistrationRequired(false);
            Object[][] classRegistrations = kryo.readObject(reader, Object[][].class);


            int lengthOrg = mergedRegistrations.length;
            int lengthNew = classRegistrations.length;
            int index = 0;

            // list all of the registrations that are mis-matched between the server/client
            for (; index < lengthOrg; index++) {
                final ClassRegistration classOrg = mergedRegistrations[index];

                if (index >= lengthNew) {
                    logger.error("Missing client registration for {} -> {}", classOrg.id, classOrg.clazz.getName());
                }
                else {
                    Object[] classNew = classRegistrations[index];
                    int idNew = (Integer) classNew[0];
                    String nameNew = (String) classNew[1];

                    int idOrg = classOrg.id;
                    String nameOrg = classOrg.clazz.getName();

                    if (idNew != idOrg || !nameOrg.equals(nameNew)) {
                        logger.error("Server registration : {} -> {}", idOrg, nameOrg);
                        logger.error("Client registration : {} -> {}", idNew, nameNew);
                    }
                }
            }

            // list all of the registrations that are missing on the server
            if (index < lengthNew) {
                for (; index < lengthNew; index++) {
                    Object[] holderClass = classRegistrations[index];
                    int id = (Integer) holderClass[0];
                    String name = (String) holderClass[1];

                    logger.error("Missing server registration : {} -> {}", id, name);
                }
            }
        } catch(Exception e) {
            logger.error("{} during registration validation", e.getMessage());

        } finally {
            if (registrationRequired) {
                kryo.setRegistrationRequired(true);
            }

            returnKryo(kryo);
            byteBuf.release();
        }

        return false;
    }

    /**
     * @return the details of all registration IDs -> Class name used by kryo
     */
    @Override
    public
    byte[] getKryoRegistrationDetails() {
        return savedRegistrationDetails;
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
     * Gets the RMI interface based on the specified implementation
     *
     * @return the corresponding interface
     */
    @Override
    public
    Class<?> getRmiImpl(Class<?> iface) {
        return rmiIfaceToImpl.get(iface);
    }

    @Override
    public
    CachedMethod[] getMethods(final int classId) {
        return methodCache.get(classId);
    }

    @Override
    public synchronized
    boolean initialized() {
        return initialized;
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
        final KryoExtra<C> kryo = kryoPool.take();
        try {
            if (wireWriteLogger.isTraceEnabled()) {
                int start = buffer.writerIndex();
                kryo.write(buffer, message);
                int end = buffer.writerIndex();

                wireWriteLogger.trace(ByteBufUtil.hexDump(buffer, start, end - start));
            }
            else {
                kryo.write(buffer, message);
            }
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
        final KryoExtra<C> kryo = kryoPool.take();
        try {
            if (wireReadLogger.isTraceEnabled()) {
                int start = buffer.readerIndex();
                Object object = kryo.read(buffer);
                int end = buffer.readerIndex();

                wireReadLogger.trace(ByteBufUtil.hexDump(buffer, start, end - start));

                return object;
            }
            else {
                return kryo.read(buffer);
            }
        } finally {
            kryoPool.put(kryo);
        }
    }

    /**
     * Writes the class and object using an available kryo instance
     */
    @Override
    public
    void writeFullClassAndObject(final Output output, final Object value) throws IOException {
        KryoExtra<C> kryo = kryoPool.take();
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
    Object readFullClassAndObject(final Input input) throws IOException {
        KryoExtra<C> kryo = kryoPool.take();
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
     * There is a small speed penalty if there were no kryo's available to use.
     */
    @Override
    public final
    void writeWithCrypto(final C connection, final ByteBuf buffer, final Object message) throws IOException {
        final KryoExtra<C> kryo = kryoPool.take();
        try {
            // we only need to encrypt when NOT on loopback, since encrypting on loopback is a waste of CPU
            if (connection.isLoopback()) {
                if (wireWriteLogger.isTraceEnabled()) {
                    int start = buffer.writerIndex();
                    kryo.writeCompressed(connection, buffer, message);
                    int end = buffer.writerIndex();

                    wireWriteLogger.trace(ByteBufUtil.hexDump(buffer, start, end - start));
                }
                else {
                    kryo.writeCompressed(connection, buffer, message);
                }
            }
            else {
                if (wireWriteLogger.isTraceEnabled()) {
                    int start = buffer.writerIndex();
                    kryo.writeCrypto(connection, buffer, message);
                    int end = buffer.writerIndex();

                    wireWriteLogger.trace(ByteBufUtil.hexDump(buffer, start, end - start));
                }
                else {
                    kryo.writeCrypto(connection, buffer, message);
                }
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
    Object readWithCrypto(final C connection, final ByteBuf buffer, final int length) throws IOException {
        final KryoExtra<C> kryo = kryoPool.take();
        try {
            // we only need to encrypt when NOT on loopback, since encrypting on loopback is a waste of CPU
            if (connection.isLoopback()) {
                if (wireReadLogger.isTraceEnabled()) {
                    int start = buffer.readerIndex();
                    Object object = kryo.readCompressed(connection, buffer, length);
                    int end = buffer.readerIndex();

                    wireReadLogger.trace(ByteBufUtil.hexDump(buffer, start, end - start));

                    return object;
                }
                else {
                    return kryo.readCompressed(connection, buffer, length);
                }
            }
            else {
                if (wireReadLogger.isTraceEnabled()) {
                    int start = buffer.readerIndex();
                    Object object = kryo.readCrypto(connection, buffer, length);
                    int end = buffer.readerIndex();

                    wireReadLogger.trace(ByteBufUtil.hexDump(buffer, start, end - start));

                    return object;
                }
                else {
                    return kryo.readCrypto(connection, buffer, length);
                }
            }
        } finally {
            kryoPool.put(kryo);
        }
    }
}
