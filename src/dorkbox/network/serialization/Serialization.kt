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

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.SerializerFactory
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy
import com.esotericsoftware.minlog.Log
import dorkbox.network.Server
import dorkbox.network.connection.Connection
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
import dorkbox.network.rmi.messages.RmiClientSerializer
import dorkbox.network.rmi.messages.RmiServerSerializer
import dorkbox.objectPool.Pool
import dorkbox.objectPool.ObjectPool
import dorkbox.objectPool.PoolObject
import dorkbox.os.OS
import dorkbox.util.serialization.SerializationDefaults
import kotlinx.atomicfu.atomic
import mu.KLogger
import mu.KotlinLogging
import org.agrona.DirectBuffer
import org.agrona.collections.Int2ObjectHashMap
import org.objenesis.instantiator.ObjectInstantiator
import org.objenesis.strategy.StdInstantiatorStrategy
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationHandler
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
open class Serialization(private val references: Boolean = true, private val factory: SerializerFactory<*>? = null) {

    companion object {
        // -2 is the same value that kryo uses for invalid id's
        const val INVALID_KRYO_ID = -2

        init {
            Log.set(Log.LEVEL_ERROR)
        }
    }

    private lateinit var logger: KLogger

    private var initialized = atomic(false)

    // used by operations performed during kryo initialization, which are by default package access (since it's an anon-inner class)
    // All registration MUST happen in-order of when the register(*) method was called, otherwise there are problems.
    // Object checking is performed during actual registration.
    private val classesToRegister = mutableListOf<ClassRegistration>()
    private lateinit var savedRegistrationDetails: ByteArray

    // the purpose of the method cache, is to accelerate looking up methods for specific class
    private val methodCache : Int2ObjectHashMap<Array<CachedMethod>> = Int2ObjectHashMap()


    // BY DEFAULT, DefaultInstantiatorStrategy() will use ReflectASM
    // StdInstantiatorStrategy will create classes bypasses the constructor (which can be useful in some cases) THIS IS A FALLBACK!
    private val instantiatorStrategy = DefaultInstantiatorStrategy(StdInstantiatorStrategy())

    private val methodRequestSerializer = MethodRequestSerializer(methodCache) // note: the methodCache is configured BEFORE anything reads from it!
    private val methodResponseSerializer = MethodResponseSerializer()
    private val continuationSerializer = ContinuationSerializer()

    private val rmiClientSerializer = RmiClientSerializer()
    private val rmiServerSerializer = RmiServerSerializer()

    val rmiHolder = RmiHolder()

    // reflectASM doesn't work on android
    private val useAsm = !OS.isAndroid()

    // These are GLOBAL, single threaded only kryo instances.
    // The readKryo WILL RE-CONFIGURED during the client handshake! (it is all the same thread, so object visibility is not a problem)
    // NOTE: These following can ONLY be called on a single thread!
    private var readKryo = initGlobalKryo()

    private val kryoPool: Pool<KryoExtra>

    init {
        val poolObject = object : PoolObject<KryoExtra>() {
            override fun newInstance(): KryoExtra {
                return initKryo()
            }
        }

        kryoPool = ObjectPool.nonBlocking(poolObject)
    }


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
    open fun <T> register(clazz: Class<T>): Serialization {
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
    open fun <T> register(clazz: Class<T>, id: Int): Serialization {
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
    open fun <T> register(clazz: Class<T>, serializer: Serializer<T>): Serialization {
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
    open fun <T> register(clazz: Class<T>, serializer: Serializer<T>, id: Int): Serialization {
        require(!initialized.value) { "Serialization 'register(Class, Serializer, int)' cannot happen after client/server initialization!" }

        // The reason it must be an implementation, is because the reflection serializer DOES NOT WORK with field types, but rather
        // with object types... EVEN IF THERE IS A SERIALIZER
        require(!clazz.isInterface) { "Cannot register '${clazz.name}'. It must be an implementation." }

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
     * @param ifaceClass this must be the interface class used for RMI
     * @param implClass this must be the implementation class used for RMI
     *          If *null* it means that this endpoint is the rmi-client
     *          If *not-null* it means that this endpoint is the rmi-server
     *
     * @throws IllegalArgumentException if the iface/impl have previously been overridden
     */
    @Synchronized
    open fun <Iface, Impl : Iface> registerRmi(ifaceClass: Class<Iface>, implClass: Class<Impl>? = null): Serialization {
        require(!initialized.value) { "Serialization 'registerRmi(Class, Class)' cannot happen after client/server initialization!" }

        require(ifaceClass.isInterface) { "Cannot register an implementation for RMI access. It must be an interface." }

        if (implClass != null) {
            require(!implClass.isInterface) { "Cannot register an interface for RMI implementations. It must be an implementation." }
        }

        classesToRegister.add(ClassRegistrationForRmi(ifaceClass, implClass, rmiServerSerializer))
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
    internal fun initHandshakeKryo(): KryoExtra {
        val kryo = KryoExtra()

        kryo.instantiatorStrategy = instantiatorStrategy
        kryo.references = references

        if (factory != null) {
            kryo.setDefaultSerializer(factory)
        }

        // All registration MUST happen in-order of when the register(*) method was called, otherwise there are problems.
        SerializationDefaults.register(kryo)

        kryo.register(HandshakeMessage::class.java)

        return kryo
    }

    /**
    * called as the first thing inside when initializing the classesToRegister
    */
    private fun initGlobalKryo(): KryoExtra {
        // NOTE:  classesToRegister.forEach will be called after serialization init!

        val kryo = KryoExtra()

        kryo.instantiatorStrategy = instantiatorStrategy
        kryo.references = references

        if (factory != null) {
            kryo.setDefaultSerializer(factory)
        }

        // All registration MUST happen in-order of when the register(*) method was called, otherwise there are problems.
        SerializationDefaults.register(kryo)

//            serialization.register(PingMessage::class.java) // TODO this is built into aeron!??!?!?!

        // TODO: this is for diffie hellmen handshake stuff!
//            serialization.register(IESParameters::class.java, IesParametersSerializer())
//            serialization.register(IESWithCipherParameters::class.java, IesWithCipherParametersSerializer())
        // TODO: fix kryo to work the way we want, so we can register interfaces + serializers with kryo
//            serialization.register(XECPublicKey::class.java, XECPublicKeySerializer())
//            serialization.register(XECPrivateKey::class.java, XECPrivateKeySerializer())
//            serialization.register(Message::class.java) // must use full package name!

        // RMI stuff!
        kryo.register(GlobalObjectCreateRequest::class.java)
        kryo.register(GlobalObjectCreateResponse::class.java)

        kryo.register(ConnectionObjectCreateRequest::class.java)
        kryo.register(ConnectionObjectCreateResponse::class.java)

        kryo.register(MethodRequest::class.java, methodRequestSerializer)
        kryo.register(MethodResponse::class.java, methodResponseSerializer)

        @Suppress("UNCHECKED_CAST")
        kryo.register(InvocationHandler::class.java as Class<Any>, rmiClientSerializer)

        kryo.register(Continuation::class.java, continuationSerializer)

        return kryo
    }

    /**
     * called as the first thing inside when initializing the classesToRegister
     */
    private fun initKryo(): KryoExtra {
        val kryo = KryoExtra()

        kryo.instantiatorStrategy = instantiatorStrategy
        kryo.references = references

        if (factory != null) {
            kryo.setDefaultSerializer(factory)
        }

        // All registration MUST happen in-order of when the register(*) method was called, otherwise there are problems.
        SerializationDefaults.register(kryo)

//            serialization.register(PingMessage::class.java) // TODO this is built into aeron!??!?!?!

        // TODO: this is for diffie hellmen handshake stuff!
//            serialization.register(IESParameters::class.java, IesParametersSerializer())
//            serialization.register(IESWithCipherParameters::class.java, IesWithCipherParametersSerializer())
        // TODO: fix kryo to work the way we want, so we can register interfaces + serializers with kryo
//            serialization.register(XECPublicKey::class.java, XECPublicKeySerializer())
//            serialization.register(XECPrivateKey::class.java, XECPrivateKeySerializer())
//            serialization.register(Message::class.java) // must use full package name!

        // RMI stuff!
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
        // note, we have to check to make sure a class is not ALREADY registered for RMI before it is registered again
        classesToRegister.forEach { registration ->
            registration.register(kryo, rmiHolder)
        }

        return kryo
    }


    /**
     * Called when server initialization is complete.
     * Called when client connection receives kryo registration details
     *
     * This is to prevent (and recognize) out-of-order class/serializer registration. If an ID is already in use by a different type, an exception is thrown.
     */
    internal fun finishInit(type: Class<*>, kryoRegistrationDetailsFromServer: ByteArray = ByteArray(0)): Boolean {

        logger = KotlinLogging.logger(type.simpleName)

        // this will set up the class registration information
        return if (type == Server::class.java) {
            if (!initialized.compareAndSet(expect = false, update = true)) {
                require(false) { "Unable to initialize serialization more than once!" }
                return false
            }

            val kryo = initKryo()
            initializeClassRegistrations(kryo)
        } else {
            if (!initialized.compareAndSet(expect = false, update = true)) {
                // the client CAN initialize more than once, since initialization happens in the handshake now
                return true
            }

            // we have to allow CUSTOM classes to register (where the order does not matter), so that if the CLIENT is the RMI-SERVER, it can
            // specify IMPL classes for RMI.
            classesToRegister.forEach { registration ->
                require(registration is ClassRegistrationForRmi) { "Unable to initialize a class registrations for anything OTHER than RMI!! To fix this, remove ${registration.clazz}" }
            }

            @Suppress("UNCHECKED_CAST")
            val classesToRegisterForRmi = listOf(*classesToRegister.toTypedArray()) as List<ClassRegistrationForRmi>
            classesToRegister.clear()

            // NOTE: to be clear, the "client" can ONLY registerRmi(IFACE, IMPL), to have extra info as the RMI-SERVER!!

            val kryo = initKryo() // this will initialize the class registrations
            initializeClient(kryoRegistrationDetailsFromServer, classesToRegisterForRmi, kryo)
        }
    }

    private fun initializeClassRegistrations(kryo: KryoExtra): Boolean {
        // now MERGE all of the registrations (since we can have registrations overwrite newer/specific registrations based on ID
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


        // now all of the registrations are IN ORDER and MERGED (save back to original array)


        // set 'classesToRegister' to our mergedRegistrations, because this is now the correct order
        classesToRegister.clear()
        classesToRegister.addAll(mergedRegistrations)


        // now create the registration details, used to validate that the client/server have the EXACT same class registration setup
        val registrationDetails = arrayListOf<Array<Any>>()

        if (logger.isDebugEnabled) {
            // log the in-order output first
            classesToRegister.forEach { classRegistration ->
                logger.debug(classRegistration.info)
            }
        }

        classesToRegister.forEach { classRegistration ->
            // now save all of the registration IDs for quick verification/access
            registrationDetails.add(classRegistration.getInfoArray())

            // we should cache RMI methods! We don't always know if something is RMI or not (from just how things are registered...)
            // so it is super trivial to map out all possible, relevant types
            val kryoId = classRegistration.id

            if (classRegistration is ClassRegistrationForRmi) {
                // on the "RMI server" (aka, where the object lives) side, there will be an interface + implementation!

                val implClass = classRegistration.implClass

                // TWO ways to do this. On RMI-SERVER, impl class will actually be an IMPL. On RMI-CLIENT, implClass will be IFACE!!
                if (implClass != null && !implClass.isInterface) {
                    // server

                    // RMI-server method caching
                    methodCache[kryoId] =
                            RmiUtils.getCachedMethods(logger, kryo, useAsm, classRegistration.clazz, implClass, kryoId)

                    // we ALSO have to cache the instantiator for these, since these are used to create remote objects
                    @Suppress("UNCHECKED_CAST")
                    rmiHolder.idToInstantiator[kryoId] =
                            kryo.instantiatorStrategy.newInstantiatorOf(implClass) as ObjectInstantiator<Any>
                } else {
                    // client

                    // RMI-client method caching
                    methodCache[kryoId] =
                            RmiUtils.getCachedMethods(logger, kryo, useAsm, classRegistration.clazz, null, kryoId)
                }
            } else if (classRegistration.clazz.isInterface) {
                // non-RMI method caching
                methodCache[kryoId] =
                        RmiUtils.getCachedMethods(logger, kryo, useAsm, classRegistration.clazz, null, kryoId)
            }

            if (kryoId >= 65535) {
                throw RuntimeException("There are too many kryo class registrations!!")
            }
        }


        // we have to check to make sure all classes are registered on the GLOBAL READ KRYO !!!
        //    Because our classes are registered LAST, this will always be correct.
        classesToRegister.forEach { registration ->
            registration.register(readKryo, rmiHolder)
        }

        // save this as a byte array (so class registration validation during connection handshake is faster)
        val output = AeronOutput()
        try {
            kryo.write(output, registrationDetails.toTypedArray())
        } catch (e: Exception) {
            logger.error("Unable to write compressed data for registration details", e)
            return false
        }

        val length = output.position()
        savedRegistrationDetails = ByteArray(length)
        output.toBytes().copyInto(savedRegistrationDetails, 0, 0, length)
        output.close()

        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun initializeClient(kryoRegistrationDetailsFromServer: ByteArray,
                                 classesToRegisterForRmi: List<ClassRegistrationForRmi>,
                                 kryo: KryoExtra): Boolean {
        val input = AeronInput(kryoRegistrationDetailsFromServer)
        val clientClassRegistrations = kryo.read(input) as Array<Array<Any>>

        val maker = kryo.instantiatorStrategy

        try {
            // note: this list will be in order by ID!
            // We want our "classesToRegister" to be identical (save for RMI stuff) to the server side, so we construct it in the same way
            clientClassRegistrations.forEach { bytes ->
                val typeId = bytes[0] as Int
                val id = bytes[1] as Int
                val clazzName = bytes[2] as String
                val serializerName = bytes[3] as String

                val clazz = Class.forName(clazzName)

                when (typeId) {
                    0 -> classesToRegister.add(ClassRegistration0(clazz, maker.newInstantiatorOf(Class.forName(serializerName)).newInstance() as Serializer<Any>))
                    1 -> classesToRegister.add(ClassRegistration1(clazz, id))
                    2 -> classesToRegister.add(ClassRegistration2(clazz, maker.newInstantiatorOf(Class.forName(serializerName)).newInstance() as Serializer<Any>, id))
                    3 -> classesToRegister.add(ClassRegistration3(clazz))
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

                        logger.trace("CLIENT RMI REG $clazz  $implClass")

                        // implClass MIGHT BE NULL!
                        classesToRegister.add(ClassRegistrationForRmi(clazz, implClass, rmiServerSerializer))

                    }
                    else -> throw IllegalStateException("Unable to manage class registrations for unknown registration type $typeId")
                }

                // now all of our classes to register will be the same (except for RMI class registrations
            }
        } catch (e: Exception) {
            logger.error("Error creating client class registrations using server data!", e)
            return false
        }

        // so far, our CURRENT kryo instance was 'registered' with everything, EXCEPT our classesToRegister.
        // fortunately for us, this always happens LAST, so we can "do it" here instead of having to reInit kryo all over
        classesToRegister.forEach { registration ->
            registration.register(kryo, rmiHolder)
        }

        // now do a round-trip through the class registrations
        return initializeClassRegistrations(kryo)
    }

    /**
     * @return takes a kryo instance from the pool, or creates one if the pool was empty
     */
    fun takeKryo(): KryoExtra {
        return kryoPool.take()
    }

    /**
     * Returns a kryo instance to the pool for re-use later on
     */
    fun returnKryo(kryo: KryoExtra) {
        kryoPool.put(kryo)
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

    // NOTE: These following functions are ONLY called on a single thread!
    fun readMessage(buffer: DirectBuffer, offset: Int, length: Int, connection: Connection): Any? {
        return readKryo.read(buffer, offset, length, connection)
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
