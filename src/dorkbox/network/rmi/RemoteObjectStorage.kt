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
package dorkbox.network.rmi

import dorkbox.collections.LockFreeIntBiMap
import mu.KLogger
import org.agrona.collections.IntArrayList
import java.util.concurrent.locks.*
import kotlin.concurrent.write

/**
 * This class allows you to store objects in it via an ID.
 *
 *
 * The ID can be reserved ahead of time, or it can be dynamically generated. Additionally, this class will recycle IDs, and prevent
 * reserved IDs from being dynamically selected.
 *
 * ADDITIONALLY, these IDs are limited to SHORT size (65535 max value) because when executing remote methods, a lot, it is important to
 * have as little data overhead in the message as possible.
 *
 * These data structures are not SHORTs because the JVM doesn't have good support for SHORT.
 *
 * From https://docs.oracle.com/javase/specs/jvms/se8/jvms8.pdf
 *
 * The Java Virtual Machine provides the most direct support for data of type int. This is partly in anticipation of efficient
 * implementations of the Java Virtual Machine's operand stacks and local variable arrays. It is also motivated by the frequency of
 * int data in typical programs. Other integral types have less direct support. There are no byte, char, or short versions of the
 * store, load, or add instructions, for instance.
 *
 *
 * In situations where we want to pass in the Connection (to an RMI method) as a parameter, we have to be able to override method A,
 * with method B.
 *
 * This is to support calling RMI methods from an interface (that does pass the connection reference) to an implType, that DOES pass
 * the connection reference. The remote side (that initiates the RMI calls), MUST use the interface, and the implType may override
 * the method, so that we add the connection as the first in the list of parameters.
 *
 *
 * for example:
 * Interface: foo(String x)
 * Impl: foo(Connection c, String x)
 *
 *
 * The implType (if it exists, with the same name, and with the same signature + connection parameter) will be called from the interface
 * instead of the method that would NORMALLY be called.
 *
 * @author Nathan Robinson
 */
internal class RemoteObjectStorage(val logger: KLogger) {

    companion object {
        const val INVALID_RMI = 0
        const val ASYNC_RMI = 1
    }

    // this is the ID -> Object RMI map. The RMI ID is used (not the kryo ID)
    private val objectMap = LockFreeIntBiMap<Any>(INVALID_RMI)

    private val idLock = ReentrantReadWriteLock()

    // object ID's are assigned OR requested, so we construct the data structures differently
    // there are 2 ways to get an RMI object ID
    //   1) request the next number from the counter
    //   2) specifically request a number
    // To solve this, we use 3 data structures, because it's also possible to RETURN no-longer needed object ID's (like when a connection closes)
    private var objectIdCounter: Int = 1
    private val reservedObjectIds = IntArrayList(1, INVALID_RMI)
    private val objectIds = IntArrayList(16, INVALID_RMI)

    init {
        (0..8).forEach { _ ->
            objectIds.addInt(objectIdCounter++)
        }
    }

    private fun validate(objectId: Int) {
        require(objectId > 0) { "The ID must be greater than 0" }
        require(objectId <= 65535) { "The ID must be less than 65,535" }
    }

    /**
     * @return the next ID or 0 (INVALID_RMI, if it's invalid)
     */
    private fun unsafeNextId(): Int {
        val id = if (objectIds.size > 0) {
            objectIds.removeAt(objectIds.size - 1)
        } else {
            objectIdCounter++
        }

        if (objectIdCounter > 65535) {
            // basically, it's a short (but collections are a LOT easier to deal with if it's an int)
            val msg = "Max ID size is 65535, because of how we pack the bytes when sending RMI messages. FATAL ERROR! (too many objects)"
            logger.error(msg)
            return INVALID_RMI
        }

        return id
    }

    /**
     * @return the next possible RMI object ID. Either one that is next available, or 0 (INVALID_RMI) if it was invalid
     */
    fun nextId(): Int {
        idLock.write {
            var idToReturn = unsafeNextId()
            while (reservedObjectIds.contains(idToReturn)) {
                idToReturn = unsafeNextId()
            }

            return idToReturn
        }
    }


    /**
     * Reserves an ID so that other requests for ID's will never return this ID. The number must be > 0 and < 65535
     *
     * Reservations are permanent and it will ALWAYS be reserved!  You cannot "un-reserve" an ID.
     *
     * If you care about memory and performance, use the ID from "nextId()" instead.
     *
     * @return false if this ID was not able to be reserved
     */
    fun reserveId(id: Int): Boolean {
        validate(id)

        idLock.write {
            val contains = objectIds.remove(id)
            if (contains) {
                // this id is available for us to use (and was temporarily used before)
                return true
            }

            if (reservedObjectIds.contains(id)) {
                // this id is ALREADY used by something else
                return false
            }

            if (objectIdCounter < id) {
                // this id is ALREADY used by something else
                return false
            }

            if (objectIdCounter == id) {
                // we are available via the counter, so make sure the counter increments
                objectIdCounter++
                // we still want to mark this as reserved, so fall through
            }

            // this means that the counter is LARGER than the id (maybe even a LOT larger)
            // we just stuff this requested number in a small array and check it whenever we get a new number
            reservedObjectIds.add(id)
            return true
        }
    }

    /**
     * @return an ID to be used again. Reserved IDs will not be allowed to be returned
     */
    fun returnId(id: Int) {
        idLock.write {
            if (reservedObjectIds.contains(id)) {
                logger.error {
                    "Do not return a reserved ID ($id). Once an ID is reserved, it is permanent."
                }
                return
            }

            val shortCheck: Int = (id + 1)
            if (shortCheck == objectIdCounter) {
                objectIdCounter--
            } else {
                objectIds.add(id)
            }
            return
        }
    }




    /**
     * Automatically registers an object with the next available ID to allow a remote connection to access this object via the returned ID
     *
     * @return the RMI object ID, there are too many, it will fail with a Runtime Exception. (Max limit is 65535 objects)
     */
    fun register(`object`: Any): Int {
        // this will return INVALID_RMI if there are too many in the ObjectSpace
        val nextObjectId = nextId()
        if (nextObjectId != INVALID_RMI) {
            objectMap.put(nextObjectId, `object`)

            logger.trace {
                "Remote object <proxy:$nextObjectId> registered with .toString() = '${`object`}'"
            }
        }

        return nextObjectId
    }

    /**
     * Registers an object to allow a remote connection access to this object via the specified ID
     *
     * @param objectId Must not be <= 0 or > 65535
     *
     * @return true if successful, false if there was an error
     */
    fun register(`object`: Any, objectId: Int): Boolean {
        validate(objectId)

        objectMap.put(objectId, `object`)

        logger.trace {
            "Remote object <proxy:$objectId> registered with .toString() = '${`object`}'"
        }

        return true
    }

    /**
     * Removes an object. The remote connection will no longer be able to access it. This object may, or may not exist
     */
    fun <T> remove(objectId: Int): T? {
        validate(objectId)

        @Suppress("UNCHECKED_CAST")
        val rmiObject = objectMap.remove(objectId) as T?
        returnId(objectId)

        logger.trace {
            "Object <proxy #${objectId}> removed"
        }
        @Suppress("UNCHECKED_CAST")
        return rmiObject
    }

    /**
     * Removes an object, and the remote end of the RmiBridge connection will no longer be able to access it.
     */
    fun remove(remoteObject: Any) {
        val objectId = objectMap.inverse().remove(remoteObject)

        if (objectId == INVALID_RMI) {
            logger.error("Object {} could not be found in the ObjectSpace.", remoteObject)
        } else {
            returnId(objectId)

            logger.trace {
                "Object '${remoteObject}' (ID: ${objectId}) removed from RMI system."
            }
        }
    }

    /**
     * This object may, or may not exist
     *
     * @return the object registered with the specified ID.
     */
    operator fun get(objectId: Int): Any? {
        return objectMap[objectId]
    }

    /**
     * @return the ID registered for the specified object, or INVALID_RMI if not found.
     */
    fun <T> getId(remoteObject: T): Int {
        // Find an ID with the object.
        return objectMap.inverse()[remoteObject]
    }

    fun close() {
        objectMap.clear()
    }
}
