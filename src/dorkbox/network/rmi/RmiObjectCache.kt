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

import mu.KLogger

/**
 * Cache for implementation and proxy objects.
 *
 * The impl/proxy objects CANNOT be stored in the same data structure, because their IDs are not tied to the same ID source (and there
 * would be conflicts in the data structure)
 */
internal open class RmiObjectCache(logger: KLogger) {

    private val implObjects = RemoteObjectStorage(logger)

    fun saveImplObject(rmiObject: Any): Int {
        return implObjects.register(rmiObject)
    }

    fun saveImplObject(rmiObject: Any, objectId: Int): Boolean {
        return implObjects.register(rmiObject, objectId)
    }

    fun <T> getImplObject(rmiId: Int): T? {
        @Suppress("UNCHECKED_CAST")
        return implObjects[rmiId] as T?
    }

    fun <T> removeImplObject(rmiId: Int): T? {
        return implObjects.remove(rmiId) as T?
    }
}
