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
package dorkbox.network.rmi

import dorkbox.network.connection.Connection
import dorkbox.network.connection.ListenerManager
import mu.KLogger

/**
 * Cache for implementation and proxy objects.
 *
 * The impl/proxy objects CANNOT be stored in the same data structure, because their IDs are not tied to the same ID source (and there
 * would be conflicts in the data structure)
 */
internal open class RmiObjectCache<CONNECTION: Connection>(private val listenerManager: ListenerManager<CONNECTION>, logger: KLogger) {

    private val implObjects = RemoteObjectStorage(logger)


    /**
     * @return the newly registered RMI ID for this object. [RemoteObjectStorage.INVALID_RMI] means it was invalid (an error log will be emitted)
     */
    suspend fun saveImplObject(rmiObject: Any): Int {
        val rmiId = implObjects.register(rmiObject)

        if (rmiId == RemoteObjectStorage.INVALID_RMI) {
            val exception = Exception("RMI implementation '${rmiObject::class.java}' could not be saved! No more RMI id's could be generated")
            ListenerManager.cleanStackTrace(exception)
            listenerManager.notifyError(exception)
        }

        return rmiId
    }

    /**
     * @return the true if it was a success saving this object. False means it was invalid (an error log will be emitted)
     */
    suspend fun saveImplObject(rmiObject: Any, objectId: Int): Boolean {
        val success = implObjects.register(rmiObject, objectId)

        if (!success) {
            val exception = Exception("RMI implementation '${rmiObject::class.java}' could not be saved! No more RMI id's could be generated")
            ListenerManager.cleanStackTrace(exception)
            listenerManager.notifyError(exception)
        }

        return success
    }

    fun <T> getImplObject(rmiId: Int): T? {
        @Suppress("UNCHECKED_CAST")
        return implObjects[rmiId] as T?
    }

    /**
     * @return the ID registered for the specified object, or INVALID_RMI if not found.
     */
    fun <T> getId(implObject: T): Int {
        return implObjects.getId(implObject)
    }

    fun <T> removeImplObject(rmiId: Int): T? {
        return implObjects.remove(rmiId) as T?
    }
}
