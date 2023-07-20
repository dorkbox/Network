/*
 * Copyright 2023 dorkbox, llc
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

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.SerializerFactory
import com.esotericsoftware.kryo.serializers.DefaultSerializers
import com.esotericsoftware.kryo.serializers.ImmutableCollectionsSerializers
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy
import com.esotericsoftware.minlog.Log
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.network.connection.DisconnectMessage
import dorkbox.network.connection.streaming.StreamingControl
import dorkbox.network.connection.streaming.StreamingControlSerializer
import dorkbox.network.connection.streaming.StreamingData
import dorkbox.network.connection.streaming.StreamingDataSerializer
import dorkbox.network.handshake.HandshakeMessage
import dorkbox.network.ping.Ping
import dorkbox.network.ping.PingSerializer
import dorkbox.network.rmi.CachedMethod
import dorkbox.network.rmi.RmiUtils
import dorkbox.network.rmi.messages.*
import dorkbox.objectPool.BoundedPoolObject
import dorkbox.objectPool.ObjectPool
import dorkbox.objectPool.Pool
import dorkbox.os.OS
import dorkbox.serializers.*
import kotlinx.atomicfu.AtomicBoolean
import kotlinx.atomicfu.atomic
import mu.KLogger
import mu.KotlinLogging
import org.agrona.collections.Int2ObjectHashMap
import org.objenesis.instantiator.ObjectInstantiator
import org.objenesis.strategy.StdInstantiatorStrategy
import java.io.File
import java.io.IOException
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationHandler
import java.math.BigDecimal
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.URI
import java.util.*
import java.util.regex.*
import kotlin.coroutines.Continuation



//  Observability issues: make sure that we know WHAT connection is causing serialization errors when they occur!
//  ASYC isues: RMI can timeout when OTHER rmi connections happen! EACH RMI NEEDS TO BE SEPARATE IN THE IO DISPATCHER

/**
 * Threads reading/writing at the same time a single instance of kryo. it is possible to use a single kryo with the use of
 * synchronize, however - that defeats the point of having multithreaded serialization.
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
open class Serialization<CONNECTION: Connection>(private val references: Boolean = true, private val factory: SerializerFactory<*>? = null) {

    companion object {
        // -2 is the same value that kryo uses for invalid id's
        const val INVALID_KRYO_ID = -2

        init {
            Log.set(Log.LEVEL_ERROR)
        }

        val inet4AddressSerializer by lazy { Inet4AddressSerializer() }
        val inet6AddressSerializer by lazy { Inet6AddressSerializer() }
    }

    open class RmiSupport<CONNECTION: Connection> internal constructor(
        private val initialized: AtomicBoolean,
        private val classesToRegister: MutableList<ClassRegistration>,
        private val rmiServerSerializer: RmiServerSerializer<CONNECTION>
    ) {
        /**
         * There is additional overhead to using RMI.
         *
         * This enables a "remote endpoint" to access methods and create objects (RMI) for this endpoint.
         *
         * This is NOT bi-directional, and this endpoint cannot access or create remote objects on the "remote client".
         *
         * @param ifaceClass this must be the interface class used for RMI
         * @param implClass this must be the implementation class used for RMI
         *          If *null* it means that this endpoint is the rmi-client
         *          If *not-null* it means that this endpoint is the rmi-server
         *
         * @throws IllegalArgumentException if the iface/impl have previously been overridden
         */
        @Synchronized
        open fun <Iface, Impl : Iface> register(ifaceClass: Class<Iface>, implClass: Class<Impl>? = null): RmiSupport<CONNECTION> {
            require(!initialized.value) { "Serialization 'registerRmi(Class, Class)' cannot happen after client/server initialization!" }

            require(ifaceClass.isInterface) { "Cannot register an implementation for RMI access. It must be an interface." }

            if (implClass != null) {
                require(!implClass.isInterface) { "Cannot register an interface for RMI implementations. It must be an implementation." }
            }

            classesToRegister.add(ClassRegistrationForRmi(ifaceClass, implClass, rmiServerSerializer))
            return this
        }
    }

    private lateinit var logger: KLogger

    @Volatile
    private var maxMessageSize: Int = 500_000

    private val writeKryos: Pool<KryoWriter<CONNECTION>> = ObjectPool.nonBlockingBounded(
        poolObject = object : BoundedPoolObject<KryoWriter<CONNECTION>>() {
            override fun newInstance(): KryoWriter<CONNECTION> {
                logger.debug { "Creating new Kryo($maxMessageSize)" }
                return newWriteKryo(maxMessageSize)
            }
        },
        maxSize = OS.optimumNumberOfThreads * 2
    )


    private var initialized = atomic(false)

    // used by operations performed during kryo initialization, which are by default package access (since it's an anon-inner class)
    // All registration MUST happen in-order of when the register(*) method was called, otherwise there are problems.
    // Object checking is performed during actual registration.
    private val classesToRegister = mutableListOf<ClassRegistration>()
    private lateinit var finalClassRegistrations: Array<ClassRegistration>
    private lateinit var savedRegistrationDetails: ByteArray

    // the purpose of the method cache, is to accelerate looking up methods for specific class
    private val methodCache : Int2ObjectHashMap<Array<CachedMethod>> = Int2ObjectHashMap()


    // BY DEFAULT, DefaultInstantiatorStrategy() will use ReflectASM
    // StdInstantiatorStrategy will create classes bypasses the constructor (which can be useful in some cases) THIS IS A FALLBACK!
    private val instantiatorStrategy = DefaultInstantiatorStrategy(StdInstantiatorStrategy())

    private val methodRequestSerializer = MethodRequestSerializer<CONNECTION>(methodCache) // note: the methodCache is configured BEFORE anything reads from it!
    private val methodResponseSerializer = MethodResponseSerializer()
    private val continuationSerializer = ContinuationSerializer()

    private val rmiClientSerializer = RmiClientSerializer<CONNECTION>()
    private val rmiServerSerializer = RmiServerSerializer<CONNECTION>()

    private val streamingControlSerializer = StreamingControlSerializer()
    private val streamingDataSerializer = StreamingDataSerializer()
    private val pingSerializer = PingSerializer()

    internal val fileContentsSerializer = FileContentsSerializer<CONNECTION>()




    /**
     * There is additional overhead to using RMI.
     *
     * This enables access to methods from a "remote endpoint", in such a way as if it were local.
     *
     * This is NOT bi-directional.
     */
    val rmi = RmiSupport(initialized, classesToRegister, rmiServerSerializer)

    val rmiHolder = RmiHolder()

    // reflectASM doesn't work on android
    private val useAsm = !OS.isAndroid


    /**
     * Registers the class using the lowest, next available integer ID and the [default serializer][Kryo.getDefaultSerializer].
     * If the class is already registered, the existing entry is updated with the new serializer.
     *
     *
     * Registering a primitive also affects the corresponding primitive wrapper.
     *
     * Because the ID assigned is affected by the IDs registered before it, the order classes are registered is important when using this
     * method.
     *
     * The order must be the same at deserialization as it was for serialization.
     *
     * This must happen before the creation of the client/server
     */
    open fun <T> register(clazz: Class<T>): Serialization<CONNECTION> {
        require(!initialized.value) { "Serialization 'register(class)' cannot happen after client/server initialization!" }

        // The reason it must be an implementation, is because the reflection serializer DOES NOT WORK with field types, but rather
        // with object types... EVEN IF THERE IS A SERIALIZER
        require(!clazz.isInterface) { "Cannot register '${clazz}' with specified ID for serialization. It must be an implementation." }

        classesToRegister.add(ClassRegistration3(clazz))
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
     * This must happen before the creation of the client/server
     *
     * @param id Must be >= 0. Smaller IDs are serialized more efficiently. IDs 0-8 are used by default for primitive types and String, but
     * these IDs can be repurposed.
     */
    open fun <T> register(clazz: Class<T>, id: Int): Serialization<CONNECTION> {
        require(!initialized.value) { "Serialization 'register(Class, int)' cannot happen after client/server initialization!" }

        // The reason it must be an implementation, is because the reflection serializer DOES NOT WORK with field types, but rather
        // with object types... EVEN IF THERE IS A SERIALIZER
        require(!clazz.isInterface) { "Cannot register '${clazz}' with specified ID for serialization. It must be an implementation." }

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
    open fun <T> register(clazz: Class<T>, serializer: Serializer<T>): Serialization<CONNECTION> {
        require(!initialized.value) { "Serialization 'register(Class, Serializer)' cannot happen after client/server initialization!" }

        // The reason it must be an implementation, is because the reflection serializer DOES NOT WORK with field types, but rather
        // with object types... EVEN IF THERE IS A SERIALIZER
        require(!clazz.isInterface) { "Cannot register '${clazz.name}' with a serializer. It must be an implementation." }

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
    open fun <T> register(clazz: Class<T>, serializer: Serializer<T>, id: Int): Serialization<CONNECTION> {
        require(!initialized.value) { "Serialization 'register(Class, Serializer, int)' cannot happen after client/server initialization!" }

        // The reason it must be an implementation, is because the reflection serializer DOES NOT WORK with field types, but rather
        // with object types... EVEN IF THERE IS A SERIALIZER
        require(!clazz.isInterface) { "Cannot register '${clazz.name}'. It must be an implementation." }

        classesToRegister.add(ClassRegistration2(clazz, serializer, id))
        return this
    }

    /**
     * NOTE: When this fails, the CLIENT will just time out. We DO NOT want to send an error message to the client
     *   (it should check for updates or something else). We do not want to give "rogue" clients knowledge of the
     *   server, thus preventing them from trying to probe the server data structures.
     *
     * @return a compressed byte array of the details of all registration IDs -> Class name -> Serialization type used by kryo
     */
    fun getKryoRegistrationDetails(): ByteArray {
        return savedRegistrationDetails
    }

    /**
     * Kryo specifically for handshakes
     */
    internal fun newHandshakeKryo(kryo: Kryo) {
        // All registration MUST happen in-order of when the register(*) method was called, otherwise there are problems.
        kryo.instantiatorStrategy = instantiatorStrategy
        kryo.references = references

        if (factory != null) {
            kryo.setDefaultSerializer(factory)
        }

        kryo.register(ByteArray::class.java)
        kryo.register(HandshakeMessage::class.java)
    }

    /**
     * called as the first thing inside when initializing the classesToRegister
     */
    private fun newGlobalKryo(kryo: Kryo) {
        // NOTE:  classesRegistrations.forEach will be called after serialization init!
        // NOTE: All registration MUST happen in-order of when the register(*) method was called, otherwise there are problems.


        // WIP Java17
        // Serializes objects using Java's built in serialization mechanism.
        // Note that this is very inefficient and should be avoided if possible.
//        val javaSerializer = JavaSerializer()

        kryo.instantiatorStrategy = instantiatorStrategy
        kryo.references = references

        if (factory != null) {
            kryo.setDefaultSerializer(factory)
        }

        // wip java 17 serialization
//        kryo.addDefaultSerializer(Throwable::class.java, javaSerializer) // this doesn't work properly!

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

        kryo.register(Exception::class.java)
        kryo.register(IOException::class.java)
        kryo.register(RuntimeException::class.java)
        kryo.register(NullPointerException::class.java)

        kryo.register(BigDecimal::class.java)
        kryo.register(BitSet::class.java)

        // necessary for the transport of exceptions.
        kryo.register(StackTraceElement::class.java)
        kryo.register(Array<StackTraceElement>::class.java)
        kryo.register(ArrayList::class.java)
        kryo.register(HashMap::class.java)
        kryo.register(HashSet::class.java)

        kryo.register(EnumSet::class.java, DefaultSerializers.EnumSetSerializer())
        kryo.register(EnumMap::class.java, EnumMapSerializer())
        kryo.register(Arrays.asList("").javaClass, DefaultSerializers.ArraysAsListSerializer())

        kryo.register(emptyList<Any>().javaClass)
        kryo.register(emptySet<Any>().javaClass)
        kryo.register(emptyMap<Any, Any>().javaClass)
        kryo.register(Collections.EMPTY_LIST.javaClass)
        kryo.register(Collections.EMPTY_SET.javaClass)
        kryo.register(Collections.EMPTY_MAP.javaClass)

        kryo.register(Collections.emptyNavigableSet<Any>().javaClass)
        kryo.register(Collections.emptyNavigableMap<Any, Any>().javaClass)

        kryo.register(Collections.singletonMap("", "").javaClass, DefaultSerializers.CollectionsSingletonMapSerializer())
        kryo.register(listOf("").javaClass, DefaultSerializers.CollectionsSingletonListSerializer())
        kryo.register(setOf("").javaClass, DefaultSerializers.CollectionsSingletonSetSerializer())

        kryo.register(Pattern::class.java, RegexSerializer())
        kryo.register(URI::class.java, DefaultSerializers.URISerializer())
        kryo.register(UUID::class.java, DefaultSerializers.UUIDSerializer())

        kryo.register(Inet4Address::class.java, inet4AddressSerializer)
        kryo.register(Inet6Address::class.java, inet6AddressSerializer)
        kryo.register(File::class.java, fileContentsSerializer)

        ImmutableCollectionsSerializers.registerSerializers(kryo)
        UnmodifiableCollectionsSerializer.registerSerializers(kryo)
        SynchronizedCollectionsSerializer.registerSerializers(kryo)

        // RMI stuff!
        kryo.register(ConnectionObjectCreateRequest::class.java)
        kryo.register(ConnectionObjectCreateResponse::class.java)
        kryo.register(ConnectionObjectDeleteRequest::class.java)
        kryo.register(ConnectionObjectDeleteResponse::class.java)

        kryo.register(MethodRequest::class.java, methodRequestSerializer)
        kryo.register(MethodResponse::class.java, methodResponseSerializer)

        // Streaming/Chunked Messages!
        kryo.register(StreamingControl::class.java, streamingControlSerializer)
        kryo.register(StreamingData::class.java, streamingDataSerializer)

        kryo.register(Ping::class.java, pingSerializer)
        kryo.register(HandshakeMessage::class.java)
        kryo.register(DisconnectMessage::class.java)


        @Suppress("UNCHECKED_CAST")
        kryo.register(InvocationHandler::class.java as Class<Any>, rmiClientSerializer)
        kryo.register(Continuation::class.java, continuationSerializer)

        kryo.register(Reserved0::class.java)
        kryo.register(Reserved1::class.java)
        kryo.register(Reserved2::class.java)
        kryo.register(Reserved3::class.java)
        kryo.register(Reserved4::class.java)
        kryo.register(Reserved5::class.java)
        kryo.register(Reserved6::class.java)
        kryo.register(Reserved7::class.java)
        kryo.register(Reserved8::class.java)
        kryo.register(Reserved9::class.java)
    }

    /**
     * Called during EndPoint initialization
     *
     * This is to prevent (and recognize) out-of-order class/serializer registration. If an ID is already in use by a different type, an exception is thrown.
     */
    internal fun finishInit(type: Class<*>, maxMessageSize: Int) {
        logger = KotlinLogging.logger(type.simpleName)
        this.maxMessageSize = maxMessageSize

        val firstInitialization = initialized.compareAndSet(expect = false, update = true)

        if (type == Server::class.java) {
            if (!firstInitialization) {
                throw IllegalArgumentException("Unable to initialize object serialization more than once!")
            }

            val kryo = KryoWriter<CONNECTION>(maxMessageSize)
            newGlobalKryo(kryo)

            initializeRegistrations(kryo, classesToRegister)
            classesToRegister.clear() // don't need to keep a reference, since this can never be reinitialized.

            if (logger.isTraceEnabled) {
                logger.trace { "Registered classes for serialization:" }

                // log the in-order output first
                finalClassRegistrations.forEach { classRegistration ->
                    logger.trace("\t${classRegistration.info}")
                }
            }
        } else {
            // the client CAN initialize more than once, HOWEVER initialization happens in the handshake and this is explicitly permitted
        }
    }

    /**
     * Called when client connection receives kryo registration details.
     *
     * This is called BEFORE the connection object is created
     *
     * NOTE: to be clear, the "client" can ONLY registerRmi(IFACE, IMPL), to have extra info as the RMI-SERVER
     *       the client DOES NOT need to register anything else! It will register what the server sends.
     *
     * This is to prevent (and recognize) out-of-order class/serializer registration. If an ID is already in use by a different type, an exception is thrown.
     *
     * @return true if initialization was successful, false otherwise. DOES NOT CATCH EXCEPTIONS EXTERNALLY
     */
    internal fun finishClientConnect(kryoRegistrationDetailsFromServer: ByteArray, maxMessageSize: Int) {
        val readKryo = KryoReader<CONNECTION>(maxMessageSize)
        val writeKryo = KryoWriter<CONNECTION>(maxMessageSize)
        newGlobalKryo(readKryo)
        newGlobalKryo(writeKryo)

        // we self initialize our registrations, THEN we compare them to the server.
        val newRegistrations = initializeRegistrationsForClient(kryoRegistrationDetailsFromServer, classesToRegister, readKryo)
            ?: throw Exception("Unable to initialize class registration information from the server")

        initializeRegistrations(writeKryo, newRegistrations)

        // NOTE: we MUST be super careful to never modify `classesToRegister`!!
        // NOTE: DO NOT CLEAR THIS WITH CLIENTS, THEY HAVE TO REBUILD EVERY TIME WITH A NEW CONNECTION!
        // classesToRegister.clear() // don't need to keep a reference, since this can never be reinitialized.

        if (logger.isTraceEnabled) {
            // log the in-order output first
            finalClassRegistrations.forEach { classRegistration ->
                logger.trace(classRegistration.info)
            }
        }
    }


    /**
     * @throws IllegalArgumentException if there is are too many RMI methods OR if a problem setting up the registration details
     */
    private fun initializeRegistrations(kryo: KryoWriter<CONNECTION>, classesToRegister: List<ClassRegistration>) {
        val mergedRegistrations = mergeClassRegistrations(classesToRegister, kryo)

        // make sure our RMI cached methods have been initialized
        initializeRmiMethodCache(mergedRegistrations, kryo)

        // we can use any kryo, as long as that kryo is registered properly
        savedRegistrationDetails = createRegistrationDetails(mergedRegistrations, kryo)

        finalClassRegistrations = mergedRegistrations.toTypedArray()
    }


    /**
     * Merge all the registrations (since we can have registrations overwrite newer/specific registrations based on ID)
     */
    private fun mergeClassRegistrations(classesToRegister: List<ClassRegistration>, kryo: Kryo): List<ClassRegistration> {

        // check to see which interfaces are mapped to RMI (otherwise, the interface requires a serializer)
        // note, we have to check to make sure a class is not ALREADY registered for RMI before it is registered again
        classesToRegister.forEach { registration ->
            registration.register(kryo, rmiHolder)
        }

        // in order to get the ID's, these have to be registered with a kryo instance!
        val mergedRegistrations = mutableListOf<ClassRegistration>()
        classesToRegister.forEach { registration ->
            val id = registration.id

            // if the id == -1, it means that this registration was ignored!
            //
            // We don't want to include it -- but we want to log that something happened (the info has been customized)
            if (id == ClassRegistration.IGNORE_REGISTRATION) {
                logger.warn(registration.info)
                return@forEach
            }

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


        // now all the registrations are IN ORDER and MERGED (save back to original array)
        return mergedRegistrations
    }

    /**
     * This allows us to cache the relevant RMI methods
     *
     * @throws IllegalArgumentException if there are too many RMI methods
     */
    private fun initializeRmiMethodCache(classesToRegister: List<ClassRegistration>, kryo: Kryo) {
        classesToRegister.forEach { classRegistration ->
            // we should cache RMI methods! We don't always know if something is RMI or not (from just how things are registered...)
            // so it is super trivial to map out all possible, relevant types
            val kryoId = classRegistration.id

            if (classRegistration is ClassRegistrationForRmi<*>) {
                // on the "RMI server" (aka, where the object lives) side, there will be an interface + implementation!

                val implClass = classRegistration.implClass

                // TWO ways to do this. On RMI-SERVER, impl class will actually be an IMPL. On RMI-CLIENT, implClass will be IFACE!!
                if (implClass != null && !implClass.isInterface) {
                    // server

                    // RMI-server method caching
                    methodCache[kryoId] = RmiUtils.getCachedMethods(logger, kryo, useAsm, classRegistration.clazz, implClass, kryoId)

                    // we ALSO have to cache the instantiator for these, since these are used to create remote objects
                    @Suppress("UNCHECKED_CAST")
                    rmiHolder.idToInstantiator[kryoId] = kryo.instantiatorStrategy.newInstantiatorOf(implClass) as ObjectInstantiator<Any>
                } else {
                    // client

                    // RMI-client method caching
                    methodCache[kryoId] = RmiUtils.getCachedMethods(logger, kryo, useAsm, classRegistration.clazz, null, kryoId)
                }
            } else if (classRegistration.clazz.isInterface) {
                // non-RMI method caching
                methodCache[kryoId] = RmiUtils.getCachedMethods(logger, kryo, useAsm, classRegistration.clazz, null, kryoId)
            }

            if (kryoId >= 65535) {
                throw IllegalArgumentException("There are too many kryo class registrations!!")
            }
        }
    }

    /**
     * @throws IllegalArgumentException if there is a problem setting up the registration details
     */
    private fun createRegistrationDetails(classesToRegister: List<ClassRegistration>, kryo: KryoWriter<CONNECTION>): ByteArray {
        // now create the registration details, used to validate that the client/server have the EXACT same class registration setup
        val registrationDetails = arrayListOf<Array<Any>>()

        // now save all the registration IDs for quick verification/access
        classesToRegister.forEach { classRegistration ->
            registrationDetails.add(classRegistration.getInfoArray())
        }

        // save this as a byte array (so class registration validation during connection handshake is faster)
        val output = AeronOutput()
        try {
            kryo.write(output, registrationDetails.toTypedArray())
        } catch (e: Exception) {
            throw IllegalArgumentException("Unable to write compressed data for registration details", e)
        }

        val length = output.position()
        val savedRegistrationDetails = ByteArray(length)
        output.toBytes().copyInto(savedRegistrationDetails, 0, 0, length)
        output.close()

        return savedRegistrationDetails
    }

    /**
     * @return false will HARD FAIL the client. The server ignores the return NOTE: THIS IS BE EXCEPTION FREE!
     */
    @Suppress("UNCHECKED_CAST")
    private fun initializeRegistrationsForClient(
        kryoRegistrationDetailsFromServer: ByteArray, classesToRegister: List<ClassRegistration>, kryo: KryoReader<CONNECTION>
    ): MutableList<ClassRegistration>? {

        // we have to allow CUSTOM classes to register (where the order does not matter), so that if the CLIENT is the RMI-SERVER, it can
        // specify IMPL classes for RMI.
        classesToRegister.forEach { registration ->
            require(registration is ClassRegistrationForRmi<*>) { "Unable to register a *class* by itself. This is only permitted on the CLIENT for RMI. " +
                    "To fix this, remove xx.register(${registration.clazz.name})" }
        }

        @Suppress("UNCHECKED_CAST")
        val classesToRegisterForRmi = listOf(*classesToRegister.toTypedArray()) as List<ClassRegistrationForRmi<CONNECTION>>

        val input = AeronInput(kryoRegistrationDetailsFromServer)
        val clientClassRegistrations = kryo.read(input) as Array<Array<Any>>
        val newRegistrations = mutableListOf<ClassRegistration>()

        val maker = kryo.instantiatorStrategy
        val rmiSerializer = rmiServerSerializer

        try {
            // note: this list will be in order by ID!
            // We want our "classesToRegister" to be identical (save for RMI stuff) to the server side, so we construct it in the same way
            clientClassRegistrations.forEach { bytes ->
                val typeId = bytes[0] as Int
                val id = bytes[1] as Int
                val clazzName = bytes[2] as String
                val serializerName = bytes[3] as String

                // if we are a primitive type, use the type directly
                val clazz = when (clazzName) {
                    "boolean"   -> Boolean::class.java
                    "byte"      -> Byte::class.java
                    "char"      -> Char::class.java
                    "short"     -> Short::class.java
                    "int"       -> Int::class.java
                    "float"     -> Float::class.java
                    "long"      -> Long::class.java
                    "double"    -> Double::class.java

                    else        -> Class.forName(clazzName)
                }

                when (typeId) {
                    0 -> {
                        logger.trace { "REGISTRATION (0) ${clazz.name}" }
                        newRegistrations.add(ClassRegistration0(clazz, maker.newInstantiatorOf(Class.forName(serializerName)).newInstance() as Serializer<Any>))
                    }
                    1 -> {
                        logger.trace { "REGISTRATION (1) ${clazz.name} :: $id" }
                        newRegistrations.add(ClassRegistration1(clazz, id))
                    }
                    2 -> {
                        logger.trace { "REGISTRATION (2) ${clazz.name} :: $id" }
                        newRegistrations.add(ClassRegistration2(clazz, maker.newInstantiatorOf(Class.forName(serializerName)).newInstance() as Serializer<Any>, id))
                    }
                    3 -> {
                        logger.trace { "REGISTRATION (3) ${clazz.name}" }
                        newRegistrations.add(ClassRegistration3(clazz))
                    }
                    4 -> {
                        // NOTE: when reconstructing, if we have access to the IMPL, we use it. WE MIGHT NOT HAVE ACCESS TO IT ON THE CLIENT!
                        // we literally want everything to be 100% the same.
                        // the only WEIRD case is when the client == rmi-server (in which case, the IMPL object is on the client)
                        // for this, the server (rmi-client) WILL ALSO have the same registration info. (bi-directional RMI, but not really)
                        val implClassName = bytes[4] as String
                        var implClass: Class<*>? = classesToRegisterForRmi.firstOrNull { it.clazz.name == clazzName }?.implClass

                        // if we do not have the impl class specified by the registrations for RMI, then do a lookup to see if we have access to it as the client
                        if (implClass == null) {
                            try {
                                implClass = Class.forName(implClassName)
                            } catch (ignored: Exception) {
                            }
                        }

                        // NOTE: implClass can still be null!

                        logger.trace {
                            if (implClass != null) {
                                "REGISTRATION (RMI-CLIENT) ${clazz.name} -> ${implClass.name}"
                            } else {
                                "REGISTRATION (RMI-CLIENT) ${clazz.name}"
                            }
                        }

                        newRegistrations.add(ClassRegistrationForRmi(clazz, implClass, rmiSerializer))
                    }
                    else -> throw IllegalStateException("Unable to manage class registrations for unknown registration type $typeId")
                }

                // now all of our classes to register will be the same (except for RMI class registrations
            }
        } catch (e: Exception) {
            logger.error("Error creating client class registrations using server data!", e)
            return null
        }

        return newRegistrations
    }

    internal inline fun <T> withKryo(kryoAccess: KryoWriter<CONNECTION>.() -> T): T {
        val kryo = writeKryos.take()
        try {
            return kryoAccess(kryo)
        } finally {
            writeKryos.put(kryo)
        }
    }

    /**
     * NOTE: A kryo instance CANNOT be re-used until after it's buffer is flushed to the network!
     *
     * @return takes a kryo instance from the pool, or creates one if the pool was empty
     */
    fun newReadKryo(maxMessageSize: Int): KryoReader<CONNECTION> {
        val kryo = KryoReader<CONNECTION>(maxMessageSize)
        newGlobalKryo(kryo)

        // the final list of all registrations in the EndPoint. This cannot change for the serer.
        finalClassRegistrations.forEach { registration ->
            registration.register(kryo, rmiHolder)
        }

        return kryo
    }
    /**
     * NOTE: A kryo instance CANNOT be re-used until after it's buffer is flushed to the network!
     *
     * @return takes a kryo instance from the pool, or creates one if the pool was empty
     */
    fun newWriteKryo(maxMessageSize: Int): KryoWriter<CONNECTION> {
        val kryo = KryoWriter<CONNECTION>(maxMessageSize)
        newGlobalKryo(kryo)

        // the final list of all registrations in the EndPoint. This cannot change for the serer.
        finalClassRegistrations.forEach { registration ->
            registration.register(kryo, rmiHolder)
        }

        return kryo
    }

    /**
     * Returns the Kryo class registration ID. This is ALWAYS called on the client!
     */
    fun getKryoIdForRmiClient(interfaceClass: Class<*>): Int {
        require(interfaceClass.isInterface) { "Can only get the kryo IDs for RMI on an interface!" }

        // BI-DIRECTIONAL RMI -- WILL NOT CALL THIS METHOD!

        // the rmi-server will have iface+impl id's
        // the rmi-client will have iface id's

        val id = rmiHolder.ifaceToId[interfaceClass]
        require(id != null) { "Registration for $interfaceClass is invalid!!" }
        return id
    }

    /**
     * Creates a NEW object implementation based on the KRYO interface ID.
     *
     * @return the corresponding implementation object
     */
    fun createRmiObject(interfaceClassId: Int, objectParameters: Array<Any?>?): Any {
        try {
            if (objectParameters.isNullOrEmpty()) {
                // simple, easy, fast.
                val objectInstantiator = rmiHolder.idToInstantiator[interfaceClassId] ?:
                                         throw NullPointerException("Object instantiator for ID $interfaceClassId is null")
                return objectInstantiator.newInstance()
            }

            // we have to get the constructor for this object.
            val clazz: Class<*> = rmiHolder.idToImpl[interfaceClassId] ?:
                                  return NullPointerException("Cannot create RMI object for kryo interfaceClassId: $interfaceClassId (no class exists)")


            // now have to find the closest match.
            val constructors = clazz.declaredConstructors
            val size = objectParameters.size
            val matchedBySize = constructors.filter { it.parameterCount == size }

            if (matchedBySize.size == 1) {
                // this is our only option
                return matchedBySize[0].newInstance(*objectParameters)
            }

            // have to match by type
            val matchedByType = mutableListOf<Pair<Int, Constructor<*>>>()
            objectParameters.forEachIndexed { index, any ->
                if (any != null) {
                    matchedBySize.forEach { singleConstructor ->
                        var matchCount = 0
                        if (singleConstructor.parameterTypes[index] == any::class.java) {
                            matchCount++
                        }

                        matchedByType.add(Pair(matchCount, singleConstructor))
                    }
                }
            }

            // find the constructor with the highest match
            matchedByType.sortByDescending { it.first }
            return matchedByType[0].second.newInstance(*objectParameters)
        } catch (e: Exception) {
            return e
        }
    }

    /**
     * Gets the cached methods for the specified class ID
     */
    fun getMethods(classId: Int): Array<CachedMethod> {
        return methodCache[classId]
    }


//    /**
//     * # BLOCKING
//     *
//     * Waits until a kryo is available to write, using CAS operations to prevent having to synchronize.
//     *
//     * @throws IOException
//     */
//    override fun write(buffer: DirectBuffer, message: Any) {
//        runBlocking {
//            val kryo = takeKryo()
//            try {
//                val output = AeronOutput(buffer as MutableDirectBuffer)
//                kryo.writeClassAndObject(output, message)
//            } finally {
//                returnKryo(kryo)
//            }
//        }
//    }
//
//    /**
//     * # BLOCKING
//     *
//     * Reads an object from the buffer.
//     *
//     * @param length should ALWAYS be the length of the expected object!
//     */
//    @Throws(IOException::class)
//    override fun read(buffer: DirectBuffer, length: Int): Any? {
//        return runBlocking {
//            val kryo = takeKryo()
//            try {
//                val input = AeronInput(buffer)
//                kryo.readClassAndObject(input)
//            } finally {
//                returnKryo(kryo)
//            }
//        }
//    }
//
//    /**
//     * # BLOCKING
//     *
//     * Writes the class and object using an available kryo instance
//     */
//    @Throws(IOException::class)
//    override fun writeFullClassAndObject(output: Output, value: Any) {
//        runBlocking {
//            val kryo = takeKryo()
//            var prev = false
//            try {
//                prev = kryo.isRegistrationRequired
//                kryo.isRegistrationRequired = false
//                kryo.writeClassAndObject(output, value)
//            } catch (ex: Exception) {
//                val msg = "Unable to serialize buffer"
//                logger.error(msg, ex)
//                throw IOException(msg, ex)
//            } finally {
//                kryo.isRegistrationRequired = prev
//                returnKryo(kryo)
//            }
//        }
//    }
//
//    /**
//     * # BLOCKING
//     *
//     * Returns a class read from the input
//     */
//    @Throws(IOException::class)
//    override fun readFullClassAndObject(input: Input): Any {
//        return runBlocking {
//            val kryo = takeKryo()
//            var prev = false
//            try {
//                prev = kryo.isRegistrationRequired
//                kryo.isRegistrationRequired = false
//                kryo.readClassAndObject(input)
//            } catch (ex: Exception) {
//                val msg = "Unable to deserialize buffer"
//                logger.error(msg, ex)
//                throw IOException(msg, ex)
//            } finally {
//                kryo.isRegistrationRequired = prev
//                returnKryo(kryo)
//            }
//        }
//    }

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
