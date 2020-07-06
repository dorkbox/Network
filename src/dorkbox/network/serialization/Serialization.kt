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

import com.esotericsoftware.kryo.*
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy
import com.esotericsoftware.kryo.util.IdentityMap
import de.javakaffee.kryoserializers.UnmodifiableCollectionsSerializer
import dorkbox.network.connection.KryoExtra
import dorkbox.network.connection.ping.PingMessage
import dorkbox.network.rmi.CachedMethod
import dorkbox.network.rmi.RmiUtils
import dorkbox.network.rmi.messages.*
import dorkbox.objectPool.ObjectPool
import dorkbox.objectPool.PoolableObject
import dorkbox.util.OS
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.agrona.collections.Int2ObjectHashMap
import org.objenesis.instantiator.ObjectInstantiator
import org.objenesis.strategy.StdInstantiatorStrategy
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.lang.reflect.InvocationHandler
import java.util.*

/**
 * Threads reading/writing at the same time a single instance of kryo. it is possible to use a single kryo with the use of
 * synchronize, however - that defeats the point of having multi-threaded serialization.
 *
 * Additionally, this serialization manager will register the entire class+interface hierarchy for an object. If you want to specify a
 * serialization scheme for a specific class in an objects hierarchy, you must register that first.
 *
 *
 * Additionally, this serialization manager will register the entire class+interface hierarchy for an object. If you want to specify a
 * serialization scheme for a specific class in an objects hierarchy, you must register that first.
 *
 * @param references If true, each appearance of an object in the graph after the first is stored as an integer ordinal.
 *                   When set to true, [MapReferenceResolver] is used. This enables references to the same object and cyclic
 *                   graphs to be serialized, but typically adds overhead of one byte per object. (should be true)
 *
 *
 * @param factory Sets the serializer factory to use when no default serializers match
 *                an object's type. Default is [ReflectionSerializerFactory] with [FieldSerializer]. @see
 *                Kryo#newDefaultSerializer(Class)
 */
class Serialization(references: Boolean,
                    factory: SerializerFactory<*>?) : NetworkSerializationManager {


    companion object {
        const val CLASS_REGISTRATION_VALIDATION_FRAGMENT_SIZE = 400
        private val UNMODIFIABLE_COLLECTION_SERIALIZERS: Array<Pair<Class<Any>, Serializer<Any>>>

        init {
            val unmodSerializers = mutableListOf<Pair<Class<Any>, Serializer<Any>>>()

            // hacky way to register unmodifiable serializers. This MUST be done here, because we ONLY want internal objects created once
            @Suppress("UNCHECKED_CAST")
            val kryo: Kryo = object : Kryo() {
                override fun register(type: Class<*>, serializer: Serializer<*>): Registration {
                    val type1 = type as Class<Any>
                    val serializer1 = serializer as Serializer<Any>
                    unmodSerializers.add(Pair(type1, serializer1))
                    return super.register(type, serializer)
                }
            }
            UnmodifiableCollectionsSerializer.registerSerializers(kryo)

            UNMODIFIABLE_COLLECTION_SERIALIZERS = unmodSerializers.toTypedArray()
            // end hack
        }

        /**
         * Additionally, this serialization manager will register the entire class+interface hierarchy for an object. If you want to specify a
         * serialization scheme for a specific class in an objects hierarchy, you must register that first.
         *
         * @param references If true, each appearance of an object in the graph after the first is stored as an integer ordinal. When set to true,
         *         [MapReferenceResolver] is used. This enables references to the same object and cyclic graphs to be serialized,
         *         but typically adds overhead of one byte per object. (should be true)
         *
         * @param factory Sets the serializer factory to use when no {@link Kryo#addDefaultSerializer(Class, Class) default serializers} match
         *         an object's type. Default is {@link ReflectionSerializerFactory} with {@link FieldSerializer}. @see
         *         Kryo#newDefaultSerializer(Class)
         */
        fun DEFAULT(references: Boolean = true, factory: SerializerFactory<*>? = null): Serialization {
            val serialization = Serialization(references, factory)

            serialization.register(ControlMessage::class.java)
            serialization.register(PingMessage::class.java) // TODO this is built into aeron!??!?!?!

            // TODO: this is for diffie hellmen handshake stuff!
//            serialization.register(IESParameters::class.java, IesParametersSerializer())
//            serialization.register(IESWithCipherParameters::class.java, IesWithCipherParametersSerializer())
            // TODO: fix kryo to work the way we want, so we can register interfaces + serializers with kryo
//            serialization.register(XECPublicKey::class.java, XECPublicKeySerializer())
//            serialization.register(XECPrivateKey::class.java, XECPrivateKeySerializer())
            serialization.register(dorkbox.network.connection.registration.Registration::class.java) // must use full package name!

            return serialization
        }
    }

    private lateinit var logger: Logger

    private var initialized = false
    private val kryoPool: ObjectPool<KryoExtra>
    lateinit var classResolver: ClassResolver

    // used by operations performed during kryo initialization, which are by default package access (since it's an anon-inner class)
    // All registration MUST happen in-order of when the register(*) method was called, otherwise there are problems.
    // Object checking is performed during actual registration.
    private val classesToRegister = mutableListOf<ClassRegistration>()
    private lateinit var savedRegistrationDetails: ByteArray

    /// RMI things
    private val rmiIfaceToInstantiator : Int2ObjectHashMap<ObjectInstantiator<Any>> = Int2ObjectHashMap()
    private val rmiIfaceToImpl = IdentityMap<Class<*>, Class<*>>()
    private val rmiImplToIface = IdentityMap<Class<*>, Class<*>>()


    // BY DEFAULT, DefaultInstantiatorStrategy() will use ReflectASM
    // StdInstantiatorStrategy will create classes bypasses the constructor (which can be useful in some cases) THIS IS A FALLBACK!
    private val instantiatorStrategy = DefaultInstantiatorStrategy(StdInstantiatorStrategy())

    private val methodRequestSerializer = MethodRequestSerializer()
    private val methodResponseSerializer = MethodResponseSerializer()
    private val objectRequestSerializer = RmiClientRequestSerializer()
    private val objectResponseSerializer = ObjectResponseSerializer(rmiImplToIface)



    // the purpose of the method cache, is to accelerate looking up methods for specific class
    private val methodCache : Int2ObjectHashMap<Array<CachedMethod>> = Int2ObjectHashMap()


    // reflectASM doesn't work on android
    private val useAsm = !OS.isAndroid()

    init {
        kryoPool = ObjectPool.NonBlockingSoftReference(object : PoolableObject<KryoExtra>() {
            override fun create(): KryoExtra {
                synchronized(this@Serialization) {

                    // we HAVE to pre-allocate the KRYOs
                    val kryo = KryoExtra(methodCache)

                    kryo.instantiatorStrategy = instantiatorStrategy
                    kryo.references = references

                    // All registration MUST happen in-order of when the register(*) method was called, otherwise there are problems.

                    // these are registered using the default serializers. We don't customize these, because we don't care about it.
                    kryo.register(String::class.java)
                    kryo.register(Array<String>::class.java)

                    kryo.register(IntArray::class.java)
                    kryo.register(ShortArray::class.java)
                    kryo.register(FloatArray::class.java)
                    kryo.register(DoubleArray::class.java)
                    kryo.register(LongArray::class.java)
                    kryo.register(ByteArray::class.java)
                    kryo.register(CharArray::class.java)
                    kryo.register(BooleanArray::class.java)

                    kryo.register(Array<Int>::class.java)
                    kryo.register(Array<Short>::class.java)
                    kryo.register(Array<Float>::class.java)
                    kryo.register(Array<Double>::class.java)
                    kryo.register(Array<Long>::class.java)
                    kryo.register(Array<Byte>::class.java)
                    kryo.register(Array<Char>::class.java)
                    kryo.register(Array<Boolean>::class.java)


                    kryo.register(Array<Any>::class.java)
                    kryo.register(Array<Array<Any>>::class.java)
                    kryo.register(Class::class.java)

                    // necessary for the transport of exceptions.
                    kryo.register(StackTraceElement::class.java)
                    kryo.register(Array<StackTraceElement>::class.java)

                    kryo.register(arrayListOf<Any>().javaClass)
                    kryo.register(hashMapOf<Any, Any>().javaClass)
                    kryo.register(hashSetOf<Any>().javaClass)

                    kryo.register(emptyList<Any>().javaClass)
                    kryo.register(emptySet<Any>().javaClass)
                    kryo.register(emptyMap<Any, Any>().javaClass)

                    kryo.register(Collections.EMPTY_LIST::class.java)
                    kryo.register(Collections.EMPTY_SET::class.java)
                    kryo.register(Collections.EMPTY_MAP::class.java)
                    kryo.register(Collections.emptyNavigableSet<Any>().javaClass)
                    kryo.register(Collections.emptyNavigableMap<Any, Any>().javaClass)

                    UNMODIFIABLE_COLLECTION_SERIALIZERS.forEach {
                        kryo.register(it.first, it.second)
                    }

                    // RMI stuff!
                    kryo.register(GlobalObjectCreateRequest::class.java)
                    kryo.register(GlobalObjectCreateResponse::class.java)

                    kryo.register(ConnectionObjectCreateRequest::class.java)
                    kryo.register(ConnectionObjectCreateResponse::class.java)

                    kryo.register(MethodRequest::class.java, methodRequestSerializer)
                    kryo.register(MethodResponse::class.java, methodResponseSerializer)

                    @Suppress("UNCHECKED_CAST")
                    kryo.register(InvocationHandler::class.java as Class<Any>, objectRequestSerializer)


                    // check to see which interfaces are mapped to RMI (otherwise, the interface requires a serializer)
                    classesToRegister.forEach { registration ->
                        registration.register(kryo)
                    }

                    if (factory != null) {
                        kryo.setDefaultSerializer(factory)
                    }

                    return kryo
                }
            }
        })
    }


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
    @Synchronized
    override fun <T> register(clazz: Class<T>): NetworkSerializationManager {
        if (initialized) {
            logger.warn("Serialization manager already initialized. Ignoring duplicate register(Class) call.")
        } else {
            classesToRegister.add(ClassRegistration(clazz))
        }

        return this
    }

    /**
     * Registers the class using the specified ID. If the ID is already in use by the same type, the old entry is overwritten. If the ID
     * is already in use by a different type, an exception is thrown.
     *
     *
     * Registering a primitive also affects the corresponding primitive wrapper.
     *
     * IDs must be the same at deserialization as they were for serialization.
     *
     * @param id Must be >= 0. Smaller IDs are serialized more efficiently. IDs 0-8 are used by default for primitive types and String, but
     * these IDs can be repurposed.
     */
    @Synchronized
    override fun <T> register(clazz: Class<T>, id: Int): NetworkSerializationManager {
        if (initialized) {
            logger.warn("Serialization manager already initialized. Ignoring duplicate register(Class, int) call.")
            return this
        }

        // The reason it must be an implementation, is because the reflection serializer DOES NOT WORK with field types, but rather
        // with object types... EVEN IF THERE IS A SERIALIZER
        require(!clazz.isInterface) { "Cannot register an interface '${clazz}' with specified ID for serialization. It must be an implementation." }

        classesToRegister.add(ClassRegistration1(clazz, id))
        return this
    }

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
    @Synchronized
    override fun <T> register(clazz: Class<T>, serializer: Serializer<T>): NetworkSerializationManager {
        if (initialized) {
            logger.warn("Serialization manager already initialized. Ignoring duplicate register(Class, Serializer) call.")
            return this
        }

        // The reason it must be an implementation, is because the reflection serializer DOES NOT WORK with field types, but rather
        // with object types... EVEN IF THERE IS A SERIALIZER
        require(!clazz.isInterface) { "Cannot register an interface '${clazz.name}' with a serializer. It must be an implementation." }

        classesToRegister.add(ClassRegistration0(clazz, serializer))
        return this
    }

    /**
     * Registers the class using the specified ID and serializer. If the ID is already in use by the same type, the old entry is
     * overwritten. If the ID is already in use by a different type, an exception is thrown.
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
    @Synchronized
    override fun <T> register(clazz: Class<T>, serializer: Serializer<T>, id: Int): NetworkSerializationManager {
        if (initialized) {
            logger.warn("Serialization manager already initialized. Ignoring duplicate register(Class, Serializer, int) call.")
            return this
        }

        // The reason it must be an implementation, is because the reflection serializer DOES NOT WORK with field types, but rather
        // with object types... EVEN IF THERE IS A SERIALIZER
        require(!clazz.isInterface) { "Cannot register an interface '${clazz.name}'. It must be an implementation." }

        classesToRegister.add(ClassRegistration2(clazz, serializer, id))
        return this
    }

    /**
     * There is additional overhead to using RMI.
     *
     * This enables a "remote endpoint" to access methods and create objects (RMI) for this endpoint.
     *
     * This is NOT bi-directional, and this endpoint cannot access or create remote objects on the "remote client".
     *
     * @throws IllegalArgumentException if the iface/impl have previously been overridden
     */
    @Synchronized
    override fun <Iface, Impl : Iface> registerRmi(ifaceClass: Class<Iface>, implClass: Class<Impl>): NetworkSerializationManager {
        if (initialized) {
            logger.warn("Serialization manager already initialized. Ignoring duplicate registerRmiImplementation(Class, Class) call.")
            return this
        }

        require(ifaceClass.isInterface) { "Cannot register an implementation for RMI access. It must be an interface." }
        require(!implClass.isInterface) { "Cannot register an interface for RMI implementations. It must be an implementation." }

        classesToRegister.add(ClassRegistrationIfaceAndImpl(ifaceClass, implClass, objectResponseSerializer))

        // rmiIfaceToImpl tells us, "the server" how to create a (requested) remote object
        // this MUST BE UNIQUE otherwise unexpected and BAD things can happen.
        val a = rmiIfaceToImpl.put(ifaceClass, implClass)
        val b = rmiImplToIface.put(implClass, ifaceClass)

        require(!(a != null || b != null)) {
            "Unable to override interface ($ifaceClass) and implementation ($implClass) " +
                    "because they have already been overridden by something else. It is critical that they are both unique per JVM"
        }
        return this
    }

    /**
     * Called when initialization is complete. This is to prevent (and recognize) out-of-order class/serializer registration. If an ID
     * is already in use by a different type, an exception is thrown.
     */
    @Synchronized
    override fun finishInit(endPointClass: Class<*>) {
        val name = endPointClass.simpleName

        this.logger = LoggerFactory.getLogger("$name.SERIAL")

        initialized = true

        // initialize the kryo pool with at least 1 kryo instance. This ALSO makes sure that all of our class registration is done
        // correctly and (if not) we are are notified on the initial thread (instead of on the network update thread)
        val kryo = kryoPool.take()
        // save off the class-resolver, so we can lookup the class <-> id relationships
        classResolver = kryo.classResolver


        try {
            // now MERGE all of the registrations (since we can have registrations overwrite newer/specific registrations based on ID
            // in order to get the ID's, these have to be registered with a kryo instance!
            val mergedRegistrations = mutableListOf<ClassRegistration>()
            classesToRegister.forEach {  registration ->
                val id = registration.id

                // if we ALREADY contain this registration (based ONLY on ID), then overwrite the existing one and REMOVE the current one
                var found = false
                mergedRegistrations.forEachIndexed { index, classRegistration ->
                    if (classRegistration.id == id) {
                        mergedRegistrations[index] = registration
                        found = true
                        return@forEachIndexed
                    }
                }

                if (!found) {
                    mergedRegistrations.add(registration)
                }
            }

            // sort these by ID, because that is what they should be registered as...
            mergedRegistrations.sortBy { it.id }


            // TODO? is this next part necessary?
            // next, go through all of the registrations and see WHICH ones are actually for RMI (and need the remote-object-serializer) and
            // which ones do not need RMI stuff.
            // We know this 2 ways:
            //  1) the class will be registered via "ClassRegistrationIfaceAndImpl"
            //  2) the class will be an interface with NO DEFINED serializer
//            val interfaceOnlyRegistrations = mergedRegistrations.filter { it.clazz.isInterface && it.serializer == null }
//            mergedRegistrations.forEach { classRegistration ->
//
//            }

            // now all of the registrations are IN ORDER and MERGED (save back to original array)


            // set 'classesToRegister' to our mergedRegistrations, because this is now the correct order
            classesToRegister.clear()
            classesToRegister.addAll(mergedRegistrations)




            // now create the registration details, used to validate that the client/server have the EXACT same class registration setup
            val registrationDetails = arrayListOf<Array<Any>>()
            classesToRegister.forEach { classRegistration ->
                classRegistration.log(logger)

                // now save all of the registration IDs for quick verification/access
                registrationDetails.add(classRegistration.getInfoArray())

                // we should cache RMI methods! We don't always know if something is RMI or not (from just how things are registered...)
                // so it is super trivial to map out all possible, relevant types
                if (classRegistration is ClassRegistrationIfaceAndImpl) {
                    // on the "RMI server" (aka, where the object lives) side, there will be an interface + implementation!
                    methodCache[classRegistration.id] =
                        RmiUtils.getCachedMethods(logger, kryo, useAsm, classRegistration.clazz, classRegistration.implClass, classRegistration.id)

                    // we ALSO have to cache the instantiator for these, since these are used to create remote objects
                    val instantiator = kryo.instantiatorStrategy.newInstantiatorOf(classRegistration.implClass)
                    @Suppress("UNCHECKED_CAST")
                    rmiIfaceToInstantiator[classRegistration.id] = instantiator as ObjectInstantiator<Any>
                } else if (classRegistration.clazz.isInterface) {
                    // on the "RMI client"
                    methodCache[classRegistration.id] =
                        RmiUtils.getCachedMethods(logger, kryo, useAsm, classRegistration.clazz, null, classRegistration.id)
                }

                if (classRegistration.id > 65000) {
                    throw RuntimeException("There are too many kryo class registrations!!")
                }
            }

            // save this as a byte array (so class registration validation during connection handshake is faster)
            val buffer = Unpooled.buffer(CLASS_REGISTRATION_VALIDATION_FRAGMENT_SIZE)

            try {
                kryo.writeCompressed(logger, buffer, registrationDetails.toTypedArray())
            } catch (e: Exception) {
                logger.error("Unable to write compressed data for registration details", e)
            }

            val length = buffer.readableBytes()
            savedRegistrationDetails = ByteArray(length)
            buffer.getBytes(0, savedRegistrationDetails, 0, length)
            buffer.release()
        } finally {
            kryoPool.put(kryo)
        }
    }

    /**
     * NOTE: When this fails, the CLIENT will just time out. We DO NOT want to send an error message to the client
     * (it should check for updates or something else). We do not want to give "rogue" clients knowledge of the
     * server, thus preventing them from trying to probe the server data structures.
     *
     * @return a compressed byte array of the details of all registration IDs -> Class name -> Serialization type used by kryo
     */
    override fun getKryoRegistrationDetails(): ByteArray {
        return savedRegistrationDetails
    }

    /**
     * NOTE: When this fails, the CLIENT will just time out. We DO NOT want to send an error message to the client
     * (it should check for updates or something else). We do not want to give "rogue" clients knowledge of the
     * server, thus preventing them from trying to probe the server data structures.
     *
     * @return true if kryo registration is required for all classes sent over the wire
     */
    override fun verifyKryoRegistration(clientBytes: ByteArray): Boolean {
        // verify the registration IDs if necessary with our own. The CLIENT does not verify anything, only the server!
        val kryoRegistrationDetails = savedRegistrationDetails
        val equals = kryoRegistrationDetails.contentEquals(clientBytes)
        if (equals) {
            return true
        }

        // now we need to figure out WHAT was screwed up so we know what to fix
        // NOTE: it could just be that the byte arrays are different, because java has a non-deterministic iteration of hash maps.
        val kryo = takeKryo()
        val byteBuf = Unpooled.wrappedBuffer(clientBytes)
        try {
            var success = true
            @Suppress("UNCHECKED_CAST")
            val clientClassRegistrations = kryo.readCompressed(logger, byteBuf, clientBytes.size) as Array<Array<Any>>
            val lengthServer = classesToRegister.size
            val lengthClient = clientClassRegistrations.size
            var index = 0

            // list all of the registrations that are mis-matched between the server/client
            while (index < lengthServer) {
                val classServer = classesToRegister[index]
                if (index < lengthClient) {
                    val classClient = clientClassRegistrations[index]
                    val idClient = classClient[0] as Int
                    val nameClient = classClient[1] as String
                    val serializerClient = classClient[2] as String
                    val idServer = classServer.id
                    val nameServer = classServer.clazz.name
                    val serializerServer = classServer.serializer?.javaClass?.name ?: ""

                    if (idClient != idServer || nameServer != nameClient || !serializerClient.equals(serializerServer, ignoreCase = true)) {
                        success = false
                        logger.error("Registration {} Client -> {} ({})", idClient, nameClient, serializerClient)
                        logger.error("Registration {} Server -> {} ({})", idServer, nameServer, serializerServer)
                    }
                } else {
                    success = false
                    logger.error("Missing client registration for {} -> {}", classServer.id, classServer.clazz.name)
                }
                index++
            }

            // list all of the registrations that are missing on the server
            if (index < lengthClient) {
                success = false
                while (index < lengthClient) {
                    val holderClass = clientClassRegistrations[index]
                    val id = holderClass[0] as Int
                    val name = holderClass[1] as String
                    val serializer = holderClass[2] as String
                    logger.error("Missing server registration : {} -> {} ({})", id, name, serializer)
                    index++
                }
            }

            // maybe everything was actually correct, and the byte arrays were different because hashmaps use non-deterministic ordering.
            return success
        } catch (e: Exception) {
            logger.error("Error [{}] during registration validation", e.message)
        } finally {
            returnKryo(kryo)
            byteBuf.release()
        }
        return false
    }

    /**
     * @return takes a kryo instance from the pool.
     */
    override fun takeKryo(): KryoExtra {
        return kryoPool.take()
    }

    /**
     * Returns a kryo instance to the pool.
     */
    override fun returnKryo(kryo: KryoExtra) {
        kryoPool.put(kryo)
    }

    /**
     * Returns the Kryo class registration ID
     */
    override fun getClassId(iFace: Class<*>): Int {
        return classResolver.getRegistration(iFace).id
    }

    /**
     * Returns the Kryo class from a registration ID
     */
    override fun getClassFromId(interfaceClassId: Int): Class<*> {
        return classResolver.getRegistration(interfaceClassId).type
    }


    /**
     * Creates a NEW object implementation based on the KRYO interface ID.
     *
     * @return the corresponding implementation object
     */
    override fun createRmiObject(interfaceClassId: Int): Any {
        return rmiIfaceToInstantiator[interfaceClassId].newInstance()
    }


    /**
     * Gets the RMI interface based on the specified implementation
     *
     * @return the corresponding interface
     */
    @Suppress("UNCHECKED_CAST")
    override fun <T> getRmiImpl(iFace: Class<T>): Class<T> {
        return rmiIfaceToImpl[iFace] as Class<T>
    }

    override fun getMethods(classId: Int): Array<CachedMethod> {
        return methodCache[classId]
    }

    @Synchronized
    override fun initialized(): Boolean {
        return initialized
    }

    /**
     * Waits until a kryo is available to write, using CAS operations to prevent having to synchronize.
     *
     *
     * No crypto and no sequence number
     *
     *
     * There is a small speed penalty if there were no kryo's available to use.
     */
    @Throws(IOException::class)
    override fun write(buffer: ByteBuf, message: Any) {
//        val kryo = kryoPool.take()
//        try {
//            kryo.writeClassAndObject(buffer, message)
//            kryo.write(NOP_CONNECTION, message)
//        } finally {
//            kryoPool.put(kryo)
//        }
    }

    /**
     * Reads an object from the buffer.
     *
     *
     * No crypto and no sequence number
     *
     * @param length should ALWAYS be the length of the expected object!
     */
    @Throws(IOException::class)
    override fun read(buffer: ByteBuf, length: Int): Any? {
//        val kryo = kryoPool.take()
//        return try {
//            if (wireReadLogger.isTraceEnabled) {
//                val start = buffer.readerIndex()
//                val `object` = kryo.read(buffer)
//                val end = buffer.readerIndex()
//                wireReadLogger.trace(ByteBufUtil.hexDump(buffer, start, end - start))
//                `object`
//            } else {
//                kryo.read(NOP_CONNECTION, buffer)
//            }
//        } finally {
//            kryoPool.put(kryo)
//        }
        return null
    }

    /**
     * Writes the class and object using an available kryo instance
     */
    @Throws(IOException::class)
    override fun writeFullClassAndObject(output: Output, value: Any) {
        val kryo = kryoPool.take()
        var prev = false
        try {
            prev = kryo.isRegistrationRequired
            kryo.isRegistrationRequired = false
            kryo.writeClassAndObject(output, value)
        } catch (ex: Exception) {
            val msg = "Unable to serialize buffer"
            logger.error(msg, ex)
            throw IOException(msg, ex)
        } finally {
            kryo.isRegistrationRequired = prev
            kryoPool.put(kryo)
        }
    }

    /**
     * Returns a class read from the input
     */
    @Throws(IOException::class)
    override fun readFullClassAndObject(input: Input): Any {
        val kryo = kryoPool.take()
        var prev = false
        return try {
            prev = kryo.isRegistrationRequired
            kryo.isRegistrationRequired = false
            kryo.readClassAndObject(input)
        } catch (ex: Exception) {
            val msg = "Unable to deserialize buffer"
            logger.error(msg, ex)
            throw IOException(msg, ex)
        } finally {
            kryo.isRegistrationRequired = prev
            kryoPool.put(kryo)
        }
    }

//    /**
//     * Waits until a kryo is available to write, using CAS operations to prevent having to synchronize.
//     *
//     *
//     * There is a small speed penalty if there were no kryo's available to use.
//     */
//    @Throws(IOException::class)
//    override fun writeWithCompression(connection: Connection_, message: Any) {
//        val kryo = kryoPool.take()
//        try {
////            if (wireWriteLogger.isTraceEnabled) {
////                val start = buffer.writerIndex()
////                kryo.writeCompressed(wireWriteLogger, connection, buffer, message)
////                val end = buffer.writerIndex()
////                wireWriteLogger.trace(ByteBufUtil.hexDump(buffer, start, end - start))
////            } else {
////                kryo.writeCompressed(wireWriteLogger, connection, buffer, message)
////            }
//        } finally {
//            kryoPool.put(kryo)
//        }
//    }

//    /**
//     * Reads an object from the buffer.
//     *
//     * @param length should ALWAYS be the length of the expected object!
//     */
//    @Throws(IOException::class)
//    override fun read(connection: Connection_, length: Int): Any {
//        val kryo = kryoPool.take()
//        return try {
////            if (wireReadLogger.isTraceEnabled) {
////                val start = buffer.readerIndex()
////                val `object` = kryo.read(connection)
////                val end = buffer.readerIndex()
////                wireReadLogger.trace(ByteBufUtil.hexDump(buffer, start, end - start))
////                `object`
////            } else {
//                kryo.read(connection, buffer)
////            }
//        } finally {
//            kryoPool.put(kryo)
//        }
//    }

//    /**
//     * Waits until a kryo is available to write, using CAS operations to prevent having to synchronize.
//     *
//     *
//     * There is a small speed penalty if there were no kryo's available to use.
//     */
//    @Throws(IOException::class)
//    override fun write(connection: Connection_, message: Any) {
//        val kryo = kryoPool.take()
//        try {
//            kryo.write(connection, message)
//        } finally {
//            kryoPool.put(kryo)
//        }
//    }

//    /**
//     * Reads an object from the buffer.
//     *
//     * @param connection can be NULL
//     * @param length should ALWAYS be the length of the expected object!
//     */
//    @Throws(IOException::class)
//    override fun readWithCompression(connection: Connection_, length: Int): Any {
//        val kryo = kryoPool.take()
//
//        return try {
//            if (wireReadLogger.isTraceEnabled) {
//                val start = buffer.readerIndex()
//                val `object` = kryo.readCompressed(wireReadLogger, connection, buffer, length)
//                val end = buffer.readerIndex()
//                wireReadLogger.trace(ByteBufUtil.hexDump(buffer, start, end - start))
//                `object`
//            } else {
//                kryo.readCompressed(wireReadLogger, connection, buffer, length)
//            }
//        } finally {
//            kryoPool.put(kryo)
//        }
//    }
//
//    /**
//     * Waits until a kryo is available to write, using CAS operations to prevent having to synchronize.
//     *
//     *
//     * There is a small speed penalty if there were no kryo's available to use.
//     */
//    @Throws(IOException::class)
//    override fun writeWithCrypto(connection: Connection_, message: Any) {
//        val kryo = kryoPool.take()
//
//        try {
//            if (wireWriteLogger.isTraceEnabled) {
//                val start = buffer.writerIndex()
//                kryo.writeCrypto(wireWriteLogger, connection, buffer, message)
//                val end = buffer.writerIndex()
//                wireWriteLogger.trace(ByteBufUtil.hexDump(buffer, start, end - start))
//            } else {
//                kryo.writeCrypto(wireWriteLogger, connection, buffer, message)
//            }
//        } finally {
//            kryoPool.put(kryo)
//        }
//    }
//
//    /**
//     * Reads an object from the buffer.
//     *
//     * @param connection can be NULL
//     * @param length should ALWAYS be the length of the expected object!
//     */
//    @Throws(IOException::class)
//    override fun readWithCrypto(connection: Connection_, length: Int): Any {
//        val kryo = kryoPool.take()
//
//        return try {
//            if (wireReadLogger.isTraceEnabled) {
//                val start = buffer.readerIndex()
//                val `object` = kryo.readCrypto(wireReadLogger, connection, buffer, length)
//                val end = buffer.readerIndex()
//                wireReadLogger.trace(ByteBufUtil.hexDump(buffer, start, end - start))
//                `object`
//            } else {
//                kryo.readCrypto(wireReadLogger, connection, buffer, length)
//            }
//        } finally {
//            kryoPool.put(kryo)
//        }
//    }
}
