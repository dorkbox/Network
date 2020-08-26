/*
 * Copyright 2020 dorkbox, llc
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

import com.esotericsoftware.kryo.ClassResolver
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.KryoException
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.SerializerFactory
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy
import com.esotericsoftware.kryo.util.IdentityMap
import dorkbox.network.connection.Connection
import dorkbox.network.connection.ping.PingMessage
import dorkbox.network.handshake.HandshakeMessage
import dorkbox.network.rmi.CachedMethod
import dorkbox.network.rmi.RmiUtils
import dorkbox.network.rmi.messages.ConnectionObjectCreateRequest
import dorkbox.network.rmi.messages.ConnectionObjectCreateResponse
import dorkbox.network.rmi.messages.ContinuationSerializer
import dorkbox.network.rmi.messages.GlobalObjectCreateRequest
import dorkbox.network.rmi.messages.GlobalObjectCreateResponse
import dorkbox.network.rmi.messages.MethodRequest
import dorkbox.network.rmi.messages.MethodRequestSerializer
import dorkbox.network.rmi.messages.MethodResponse
import dorkbox.network.rmi.messages.MethodResponseSerializer
import dorkbox.network.rmi.messages.RmiClientReverseSerializer
import dorkbox.network.rmi.messages.RmiClientSerializer
import dorkbox.os.OS
import dorkbox.util.serialization.SerializationDefaults
import dorkbox.util.serialization.SerializationManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import mu.KLogger
import mu.KotlinLogging
import org.agrona.DirectBuffer
import org.agrona.MutableDirectBuffer
import org.agrona.collections.Int2ObjectHashMap
import org.objenesis.instantiator.ObjectInstantiator
import org.objenesis.strategy.StdInstantiatorStrategy
import java.io.IOException
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationHandler
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation

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
class Serialization(private val references: Boolean,
                    private val factory: SerializerFactory<*>?) : SerializationManager<DirectBuffer> {


    companion object {
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

            serialization.register(PingMessage::class.java) // TODO this is built into aeron!??!?!?!

            // TODO: this is for diffie hellmen handshake stuff!
//            serialization.register(IESParameters::class.java, IesParametersSerializer())
//            serialization.register(IESWithCipherParameters::class.java, IesWithCipherParametersSerializer())
            // TODO: fix kryo to work the way we want, so we can register interfaces + serializers with kryo
//            serialization.register(XECPublicKey::class.java, XECPublicKeySerializer())
//            serialization.register(XECPrivateKey::class.java, XECPrivateKeySerializer())
//            serialization.register(Message::class.java) // must use full package name!

            return serialization
        }
    }

    private lateinit var logger: KLogger

    private var initialized = false
    private val kryoPool: Channel<KryoExtra>
    lateinit var classResolver: ClassResolver

    // used by operations performed during kryo initialization, which are by default package access (since it's an anon-inner class)
    // All registration MUST happen in-order of when the register(*) method was called, otherwise there are problems.
    // Object checking is performed during actual registration.
    private val classesToRegister = mutableListOf<ClassRegistration>()
    internal lateinit var savedKryoIdsForRmi: IntArray
    private lateinit var savedRegistrationDetails: ByteArray

    /// RMI things
    private val rmiIfaceToInstantiator : Int2ObjectHashMap<ObjectInstantiator<Any>> = Int2ObjectHashMap()
    private val rmiIfaceToImpl = IdentityMap<Class<*>, Class<*>>()
    private val rmiImplToIface = IdentityMap<Class<*>, Class<*>>()

    // This is a GLOBAL, single threaded only kryo instance.
    private val globalKryo: KryoExtra by lazy { initKryo() }

    // BY DEFAULT, DefaultInstantiatorStrategy() will use ReflectASM
    // StdInstantiatorStrategy will create classes bypasses the constructor (which can be useful in some cases) THIS IS A FALLBACK!
    private val instantiatorStrategy = DefaultInstantiatorStrategy(StdInstantiatorStrategy())

    private val methodRequestSerializer = MethodRequestSerializer()
    private val methodResponseSerializer = MethodResponseSerializer()

    private val continuationSerializer = ContinuationSerializer()

    private val rmiClientSerializer = RmiClientSerializer()
    private val rmiClientReverseSerializer = RmiClientReverseSerializer(rmiImplToIface)

    // list of already seen client RMI ids (which the server might not have registered as RMI types).
    private var existingRmiIds = CopyOnWriteArrayList<Int>()



    // the purpose of the method cache, is to accelerate looking up methods for specific class
    private val methodCache : Int2ObjectHashMap<Array<CachedMethod>> = Int2ObjectHashMap()


    // reflectASM doesn't work on android
    private val useAsm = !OS.isAndroid()

    init {
        // reasonable size of available kryo's before coroutines are suspended during read/write
        val KRYO_COUNT = 64

        kryoPool = Channel(KRYO_COUNT)

    }

    @Synchronized
    private fun initKryo(): KryoExtra {
        val kryo = KryoExtra(methodCache)

        kryo.instantiatorStrategy = instantiatorStrategy
        kryo.references = references

        // All registration MUST happen in-order of when the register(*) method was called, otherwise there are problems.
        SerializationDefaults.register(kryo)

        // RMI stuff!
        kryo.register(HandshakeMessage::class.java)
        kryo.register(GlobalObjectCreateRequest::class.java)
        kryo.register(GlobalObjectCreateResponse::class.java)

        kryo.register(ConnectionObjectCreateRequest::class.java)
        kryo.register(ConnectionObjectCreateResponse::class.java)

        kryo.register(MethodRequest::class.java, methodRequestSerializer)
        kryo.register(MethodResponse::class.java, methodResponseSerializer)

        @Suppress("UNCHECKED_CAST")
        kryo.register(InvocationHandler::class.java as Class<Any>, rmiClientSerializer)

        kryo.register(Continuation::class.java, continuationSerializer)

        // check to see which interfaces are mapped to RMI (otherwise, the interface requires a serializer)
        classesToRegister.forEach { registration ->
            registration.register(kryo)
        }

        if (factory != null) {
            kryo.setDefaultSerializer(factory)
        }

        return kryo
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
    override fun <T> register(clazz: Class<T>): Serialization {
        if (initialized) {
            logger.warn("Serialization manager already initialized. Ignoring duplicate register(Class) call.")
        } else {
            classesToRegister.add(ClassRegistration3(clazz))
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
    override fun <T> register(clazz: Class<T>, id: Int): Serialization {
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
    override fun <T> register(clazz: Class<T>, serializer: Serializer<T>): Serialization {
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
    override fun <T> register(clazz: Class<T>, serializer: Serializer<T>, id: Int): Serialization {
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
    fun <Iface, Impl : Iface> registerRmi(ifaceClass: Class<Iface>, implClass: Class<Impl>): Serialization {
        if (initialized) {
            logger.warn("Serialization manager already initialized. Ignoring duplicate registerRmiImplementation(Class, Class) call.")
            return this
        }

        require(ifaceClass.isInterface) { "Cannot register an implementation for RMI access. It must be an interface." }
        require(!implClass.isInterface) { "Cannot register an interface for RMI implementations. It must be an implementation." }

        classesToRegister.add(ClassRegistrationIfaceAndImpl(ifaceClass, implClass, rmiClientReverseSerializer))

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
    fun finishInit(endPointClass: Class<*>) {
        this.logger = KotlinLogging.logger(endPointClass.simpleName)

        initialized = true

        // initialize the kryo pool with at least 1 kryo instance. This ALSO makes sure that all of our class registration is done
        // correctly and (if not) we are are notified on the initial thread (instead of on the network update thread)
        // save off the class-resolver, so we can lookup the class <-> id relationships
        classResolver = globalKryo.classResolver


        // now MERGE all of the registrations (since we can have registrations overwrite newer/specific registrations based on ID
        // in order to get the ID's, these have to be registered with a kryo instance!
        val mergedRegistrations = mutableListOf<ClassRegistration>()
        classesToRegister.forEach { registration ->
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


        // now all of the registrations are IN ORDER and MERGED (save back to original array)


        // set 'classesToRegister' to our mergedRegistrations, because this is now the correct order
        classesToRegister.clear()
        classesToRegister.addAll(mergedRegistrations)


        // now create the registration details, used to validate that the client/server have the EXACT same class registration setup
        val registrationDetails = arrayListOf<Array<Any>>()

        if (logger.isDebugEnabled) {
            // log the in-order output first
            classesToRegister.forEach { classRegistration ->
                logger.debug(classRegistration.info())
            }
        }

        val kryoIdsForRmi = mutableListOf<Int>()

        classesToRegister.forEach { classRegistration ->
            // now save all of the registration IDs for quick verification/access
            registrationDetails.add(classRegistration.getInfoArray())

            // we should cache RMI methods! We don't always know if something is RMI or not (from just how things are registered...)
            // so it is super trivial to map out all possible, relevant types
            val kryoId = classRegistration.id

            if (classRegistration is ClassRegistrationIfaceAndImpl) {
                // on the "RMI server" (aka, where the object lives) side, there will be an interface + implementation!

                // RMI method caching
                methodCache[kryoId] =
                    RmiUtils.getCachedMethods(logger, globalKryo, useAsm, classRegistration.ifaceClass, classRegistration.implClass, kryoId)

                // we ALSO have to cache the instantiator for these, since these are used to create remote objects
                val instantiator = globalKryo.instantiatorStrategy.newInstantiatorOf(classRegistration.implClass)

                @Suppress("UNCHECKED_CAST")
                rmiIfaceToInstantiator[kryoId] = instantiator as ObjectInstantiator<Any>

                // finally, we must save this ID, to tell the remote connection that their interface serializer must change to support
                // receiving an RMI impl object as a proxy object
                kryoIdsForRmi.add(kryoId)
            } else if (classRegistration.clazz.isInterface) {
                // non-RMI method caching
                methodCache[kryoId] =
                    RmiUtils.getCachedMethods(logger, globalKryo, useAsm, classRegistration.clazz, null, kryoId)
            }

            if (kryoId > 65000) {
                throw RuntimeException("There are too many kryo class registrations!!")
            }
        }

        // save as an array to make it faster to send this info to the remote connection
        savedKryoIdsForRmi = kryoIdsForRmi.toIntArray()

        // have to add all of our EXISTING RMI id's, so we don't try to duplicate them (in case RMI registration is duplicated)
        existingRmiIds.addAllAbsent(kryoIdsForRmi)


        // save this as a byte array (so class registration validation during connection handshake is faster)
        val output = AeronOutput()

        try {
            globalKryo.writeCompressed(logger, output, registrationDetails.toTypedArray())
        } catch (e: Exception) {
            logger.error("Unable to write compressed data for registration details", e)
        }

        val length = output.position()
        savedRegistrationDetails = ByteArray(length)
        output.toBytes().copyInto(savedRegistrationDetails, 0, 0, length)
        output.close()
    }

    /**
     * NOTE: When this fails, the CLIENT will just time out. We DO NOT want to send an error message to the client
     * (it should check for updates or something else). We do not want to give "rogue" clients knowledge of the
     * server, thus preventing them from trying to probe the server data structures.
     *
     * @return a compressed byte array of the details of all registration IDs -> Class name -> Serialization type used by kryo
     */
    fun getKryoRegistrationDetails(): ByteArray {
        return savedRegistrationDetails
    }

    /**
     * @return the details of all registration IDs for RMI iface serializer rewrites
     */
    fun getKryoRmiIds(): IntArray {
        return savedKryoIdsForRmi
    }

    /**
     * NOTE: When this fails, the CLIENT will just time out. We DO NOT want to send an error message to the client
     * (it should check for updates or something else). We do not want to give "rogue" clients knowledge of the
     * server, thus preventing them from trying to probe the server data structures.
     *
     * @return true if kryo registration is required for all classes sent over the wire
     */
    @Suppress("DuplicatedCode")
    fun verifyKryoRegistration(clientBytes: ByteArray): Boolean {
        // verify the registration IDs if necessary with our own. The CLIENT does not verify anything, only the server!
        val kryoRegistrationDetails = savedRegistrationDetails
        val equals = kryoRegistrationDetails.contentEquals(clientBytes)
        if (equals) {
            return true
        }

        // RMI details might be one reason the arrays are different

        // now we need to figure out WHAT was screwed up so we know what to fix
        // NOTE: it could just be that the byte arrays are different, because java has a non-deterministic iteration of hash maps.
        val kryo = takeKryo()
        val input = AeronInput(clientBytes)

        try {
            var success = true
            @Suppress("UNCHECKED_CAST")
            val clientClassRegistrations = kryo.readCompressed(logger, input, clientBytes.size) as Array<Array<Any>>
            val lengthServer = classesToRegister.size
            val lengthClient = clientClassRegistrations.size
            var index = 0

            // list all of the registrations that are mis-matched between the server/client
            for (i in 0 until lengthServer) {
                index = i
                val classServer = classesToRegister[index]

                if (index >= lengthClient) {
                    success = false
                    logger.error("Missing client registration for {} -> {}", classServer.id, classServer.clazz.name)
                    continue
                }


                val classClient = clientClassRegistrations[index]

                val idClient = classClient[0] as Int
                val nameClient = classClient[1] as String
                val serializerClient = classClient[2] as String

                val idServer = classServer.id
                val nameServer = classServer.clazz.name
                val serializerServer = classServer.serializer?.javaClass?.name ?: ""

                // JUST MAYBE this is a serializer for RMI. The client doesn't have to register for RMI stuff
                //  this logic is unwrapped, and seemingly complex in order to specifically check for this in a performant way
                val idMatches = idClient == idServer
                if (!idMatches) {
                    success = false
                    logger.error("MISMATCH: Registration $idClient Client -> $nameClient ($serializerClient)")
                    logger.error("MISMATCH: Registration $idServer Server -> $nameServer ($serializerServer)")
                    continue
                }


                val nameMatches = nameServer == nameClient
                if (!nameMatches) {
                    success = false
                    logger.error("MISMATCH: Registration $idClient Client -> $nameClient ($serializerClient)")
                    logger.error("MISMATCH: Registration $idServer Server -> $nameServer ($serializerServer)")
                    continue
                }


                val serializerMatches = serializerServer == serializerClient
                if (!serializerMatches) {
                    // JUST MAYBE this is a serializer for RMI. The client doesn't have to register for RMI stuff explicitly

                    when {
                        serializerServer == rmiClientReverseSerializer::class.java.name -> {
                            // this is for when the impl is on server, and iface is on client

                            // after this check, we tell the client that this ID is for RMI
                            //  This necessary because only 1 side registers RMI iface/impl info
                        }
                        serializerClient == rmiClientReverseSerializer::class.java.name -> {
                            // this is for when the impl is on client, and iface is on server

                            // after this check, we tell MYSELF (the server) that this id is for RMI
                            //  This necessary because only 1 side registers RMI iface/impl info
                        }
                        else -> {
                            success = false
                            logger.error("MISMATCH: Registration $idClient Client -> $nameClient ($serializerClient)")
                            logger.error("MISMATCH: Registration $idServer Server -> $nameServer ($serializerServer)")
                        }
                    }
                }
            }

            // +1 because we are going from index -> length
            index++

            // list all of the registrations that are missing on the server
            if (index < lengthClient) {
                success = false
                for (i in index - 1 until lengthClient) {
                    val holderClass = clientClassRegistrations[i]
                    val id = holderClass[0] as Int
                    val name = holderClass[1] as String
                    val serializer = holderClass[2] as String
                    logger.error("Missing server registration : {} -> {} ({})", id, name, serializer)
                }
            }

            // maybe everything was actually correct, and the byte arrays were different because hashmaps use non-deterministic ordering.
            return success
        } catch (e: Exception) {
            logger.error("Error [{}] during registration validation", e.message)
        } finally {
            returnKryo(kryo)
            input.close()
        }


        return false
    }

    /**
     * @return takes a kryo instance from the pool, or creates one if the pool was empty
     */
    fun takeKryo(): KryoExtra {
        // ALWAYS get as many as needed. Recycle them to prevent too many getting created
        return kryoPool.poll() ?: initKryo()
    }

    /**
     * Returns a kryo instance to the pool for re-use later on
     */
    fun returnKryo(kryo: KryoExtra) {
        // return as much as we can. don't suspend if the pool is full, we just throw it away.
        kryoPool.offer(kryo)
    }

    /**
     * Returns the Kryo class registration ID
     */
    fun getKryoIdForRmi(interfaceClass: Class<*>): Int {
        if (!interfaceClass.isInterface) {
            throw KryoException("Can only get the kryo IDs for RMI on an interface!")
        }

        val implClass = rmiIfaceToImpl[interfaceClass]

        // for RMI, we store the IMPL class in the class registration -- not the iface!
        return classResolver.getRegistration(implClass).id
    }

    /**
     * Creates a NEW object implementation based on the KRYO interface ID.
     *
     * @return the corresponding implementation object
     */
    fun createRmiObject(interfaceClassId: Int, objectParameters: Array<Any?>?): Any {
        try {
            if (objectParameters == null) {
                return rmiIfaceToInstantiator[interfaceClassId].newInstance()
            }

            val size = objectParameters.size

            // we have to get the constructor for this object.
            val clazz = classResolver.getRegistration(interfaceClassId).type
            val constructors = clazz.declaredConstructors

            // now have to find the closest match.
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
        } catch(e: Exception) {
            return e
        }
    }

    /**
     * Gets the cached methods for the specified class ID
     */
    fun getMethods(classId: Int): Array<CachedMethod> {
        return methodCache[classId]
    }

    /**
     * @return true if our initialization is complete. Some registrations (in the property store, for example) always register for client
     *              and server, even if in the same JVM. This only attempts to register once.
     */
    @Synchronized
    fun initialized(): Boolean {
        return initialized
    }

    /**
     * # BLOCKING
     *
     * Waits until a kryo is available to write, using CAS operations to prevent having to synchronize.
     */
    @Throws(IOException::class)
    override fun write(buffer: DirectBuffer, message: Any) {
        runBlocking {
            val kryo = takeKryo()
            try {
                val output = AeronOutput(buffer as MutableDirectBuffer)
                kryo.writeClassAndObject(output, message)
            } finally {
                returnKryo(kryo)
            }
        }
    }

    /**
     * # BLOCKING
     *
     * Reads an object from the buffer.
     *
     * @param length should ALWAYS be the length of the expected object!
     */
    @Throws(IOException::class)
    override fun read(buffer: DirectBuffer, length: Int): Any? {
        return runBlocking {
            val kryo = takeKryo()
            try {
                val input = AeronInput(buffer)
                kryo.readClassAndObject(input)
            } finally {
                returnKryo(kryo)
            }
        }
    }

    /**
     * # BLOCKING
     *
     * Writes the class and object using an available kryo instance
     */
    @Throws(IOException::class)
    override fun writeFullClassAndObject(output: Output, value: Any) {
        runBlocking {
            val kryo = takeKryo()
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
                returnKryo(kryo)
            }
        }
    }

    /**
     * # BLOCKING
     *
     * Returns a class read from the input
     */
    @Throws(IOException::class)
    override fun readFullClassAndObject(input: Input): Any {
        return runBlocking {
            val kryo = takeKryo()
            var prev = false
            try {
                prev = kryo.isRegistrationRequired
                kryo.isRegistrationRequired = false
                kryo.readClassAndObject(input)
            } catch (ex: Exception) {
                val msg = "Unable to deserialize buffer"
                logger.error(msg, ex)
                throw IOException(msg, ex)
            } finally {
                kryo.isRegistrationRequired = prev
                returnKryo(kryo)
            }
        }
    }

    suspend fun <CONNECTION: Connection> updateKryoIdsForRmi(connection: CONNECTION, rmiModificationIds: IntArray, onError: suspend (String) -> Unit) {
        val endPoint = connection.endPoint()
        val typeName = endPoint.type.simpleName

        rmiModificationIds.forEach {
            if (!existingRmiIds.contains(it)) {
                existingRmiIds.add(it)

                // have to modify the network read kryo with the correct registration id -> serializer info. This is a GLOBAL change made on
                // a single thread.
                // NOTE: This change will ONLY modify the network-read kryo. This is all we need to modify. The write kryo's will already be correct

                val registration = globalKryo.getRegistration(it)
                val regMessage = "$typeName-side RMI serializer for registration $it -> ${registration.type}"

                if (registration.type.isInterface) {
                    logger.debug {
                        "Modifying $regMessage"
                    }
                    // RMI must be with an interface. If it's not an interface then something is wrong
                    registration.serializer = rmiClientReverseSerializer
                } else {
                    // note: one way that this can be called is when BOTH the client + server register the same way for RMI IDs. When
                    //   the endpoint serialization is initialized, we also add the RMI IDs to this list, so we don't have to worry about this specific
                    //   scenario
                    onError("Attempting an unsafe modification of $regMessage")
                }
            }
        }
    }

    // NOTE: These following functions are ONLY called on a single thread!
    fun readMessage(buffer: DirectBuffer, offset: Int, length: Int): Any? {
        return globalKryo.read(buffer, offset, length)
    }
    fun readMessage(buffer: DirectBuffer, offset: Int, length: Int, connection: Connection): Any? {
        return globalKryo.read(buffer, offset, length, connection)
    }
    fun writeMessage(message: Any): AeronOutput {
        globalKryo.write(message)
        return globalKryo.writerBuffer
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
