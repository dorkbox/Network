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

import org.slf4j.Logger

/**
 * Cache for implementation and proxy objects.
 *
 * The impl/proxy objects CANNOT be stored in the same data structure, because their IDs are not tied to the same ID source (and there
 * would be conflicts in the data structure)
 */
open class RmiObjectCache(val logger: Logger) {

    private val implObjects = RemoteObjectStorage(logger)

    /**
     * This object will be saved again if we send the object "over the wire", automatically!
     *
     * So if we DELETE the object (on side A), and then later on side A sends the object to side B, then side A will save it again when it sends.
     *
     * @return the newly registered RMI ID for this object. [RemoteObjectStorage.INVALID_RMI] means it was invalid (an error log will be emitted)
     */
    internal fun saveImplObject(rmiObject: Any): Int {
        return implObjects.register(rmiObject)
    }

    /**
     * @return the true if it was a success saving this object. False means it was invalid (an error log will be emitted)
     */
    internal fun saveImplObject(rmiObject: Any, objectId: Int): Boolean {
        return implObjects.register(rmiObject, objectId)
    }

    /**
     * @return the implementation object from the specified ID
     */
    internal fun <T> getImplObject(rmiId: Int): T? {
        @Suppress("UNCHECKED_CAST")
        return implObjects[rmiId] as T?
    }

    /**
     * Removes the object using the registered ID.
     *
     * @return the object or null if not found
     */
    internal fun <T> removeImplObject(rmiId: Int): T? {
        return implObjects.remove(rmiId) as T?
    }

    /**
     * @return the ID registered for the specified object, or INVALID_RMI if not found.
     */
    internal fun <T: Any> getId(implObject: T): Int {
        return implObjects.getId(implObject)
    }


    /**
     * @return all the saved RMI implementation objects along with their RMI ID. This is used by session management in order to preserve RMI functionality.
     */
    internal fun getAllImplObjects(): List<Pair<Int, Any>> {
        return implObjects.getAll()
    }

    /**
     * all the saved RMI implementation objects along with their RMI ID. This is used by session management in order to preserve RMI functionality.
     */
    internal fun restoreImplObjects(implObjects: List<Pair<Int, Any>>) {
        this.implObjects.restoreAll(implObjects)
    }

    internal open fun clear() {
        this.implObjects.clear()
    }
}
