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
package dorkbox.network.storage.types

import dorkbox.network.storage.GenericStore
import dorkbox.network.storage.SettingsStore
import dorkbox.network.storage.StorageType
import mu.KLogger
import org.agrona.collections.Object2ObjectHashMap

/**
 * In-Memory store
 */
object MemoryStore {
    fun type() = object : StorageType {
        override fun create(logger: KLogger): SettingsStore {
            return SettingsStore(logger, MemoryAccess(logger))
        }
    }
}


class MemoryAccess(val logger: KLogger): GenericStore {
    private val map = Object2ObjectHashMap<Any, ByteArray>()

    init {
        logger.info("Memory storage initialized")
    }

    override fun get(key: Any): ByteArray? {
        return map[key]
    }

    override fun set(key: Any, bytes: ByteArray?) {
        map[key] = bytes
    }

    override fun close() {
    }
}

