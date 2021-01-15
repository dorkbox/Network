/*
 * Copyright 2014 dorkbox, llc
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
package dorkbox.network.other

import com.conversantmedia.util.concurrent.MultithreadConcurrentQueue
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.minlog.Log
import dorkbox.network.serialization.ClassRegistration
import dorkbox.network.serialization.ClassRegistration0
import dorkbox.network.serialization.ClassRegistration1
import dorkbox.network.serialization.ClassRegistration2
import dorkbox.network.serialization.ClassRegistration3
import dorkbox.network.serialization.KryoExtra
import dorkbox.util.serialization.SerializationDefaults
import kotlinx.atomicfu.atomic

class PooledSerialization {
    companion object {
        init {
            Log.set(Log.LEVEL_ERROR)
        }
    }

    private var initialized = atomic(false)
    private val classesToRegister = mutableListOf<ClassRegistration>()

    private var kryoPoolSize = 16
    private val kryoInUse = atomic(0)

    @Volatile
    private var kryoPool = MultithreadConcurrentQueue<KryoExtra>(kryoPoolSize)

    /**
     * If you customize anything, you will want to register custom types before init() is called!
     */
    fun init() {
        // NOTE: there are problems if our serializer is THE SAME serializer used by the network stack!
        //  We are explicitly differet types to prevent that form happening

        initialized.value = true
    }

    private fun initKryo(): KryoExtra {
        val kryo = KryoExtra()

        SerializationDefaults.register(kryo)

        classesToRegister.forEach { registration ->
            registration.register(kryo)
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
     * Because the ID assigned is affected by the IDs registered before it, the order classes are registered is important when using this
     * method.
     *
     * The order must be the same at deserialization as it was for serialization.
     *
     * This must happen before the creation of the client/server
     */
    fun <T> register(clazz: Class<T>): PooledSerialization {
        require(!initialized.value) { "Serialization 'register(class)' cannot happen after initialization!" }

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
    fun <T> register(clazz: Class<T>, id: Int): PooledSerialization {
        require(!initialized.value) { "Serialization 'register(Class, int)' cannot happen after initialization!" }

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
    fun <T> register(clazz: Class<T>, serializer: Serializer<T>): PooledSerialization {
        require(!initialized.value) { "Serialization 'register(Class, Serializer)' cannot happen after initialization!" }

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
    fun <T> register(clazz: Class<T>, serializer: Serializer<T>, id: Int): PooledSerialization {
        require(!initialized.value) { "Serialization 'register(Class, Serializer, int)' cannot happen after initialization!" }

        // The reason it must be an implementation, is because the reflection serializer DOES NOT WORK with field types, but rather
        // with object types... EVEN IF THERE IS A SERIALIZER
        require(!clazz.isInterface) { "Cannot register '${clazz.name}'. It must be an implementation." }

        classesToRegister.add(ClassRegistration2(clazz, serializer, id))
        return this
    }

    /**
     * @return takes a kryo instance from the pool, or creates one if the pool was empty
     */
    fun takeKryo(): KryoExtra {
        kryoInUse.getAndIncrement()

        // ALWAYS get as many as needed. We recycle them (with an auto-growing pool) to prevent too many getting created
        return kryoPool.poll() ?: initKryo()
    }

    /**
     * Returns a kryo instance to the pool for re-use later on
     */
    fun returnKryo(kryo: KryoExtra) {
        val kryoCount = kryoInUse.getAndDecrement()
        if (kryoCount > kryoPoolSize) {
            // this is CLEARLY a problem, as we have more kryos in use that our pool can support.
            // This happens when we send messages REALLY fast.
            //
            // We fix this by increasing the size of the pool, so kryos aren't thrown away (and create a GC hit)

            synchronized(kryoInUse) {
                // we have a double check here on purpose. only 1 will work
                if (kryoCount > kryoPoolSize) {
                    val oldPool = kryoPool
                    val oldSize = kryoPoolSize
                    val newSize = kryoPoolSize * 2

                    kryoPoolSize = newSize
                    kryoPool = MultithreadConcurrentQueue<KryoExtra>(kryoPoolSize)


                    // take all of the old kryos and put them in the new one
                    val array = arrayOfNulls<KryoExtra>(oldSize)
                    val count = oldPool.remove(array)

                    for (i in 0 until count) {
                        kryoPool.offer(array[i])
                    }
                }
            }
        }

        kryoPool.offer(kryo)
    }
}
