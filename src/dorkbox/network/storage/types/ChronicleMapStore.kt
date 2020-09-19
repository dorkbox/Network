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
import net.openhft.chronicle.map.ChronicleMap
import java.io.File
import java.net.InetAddress


class ChronicleMapStore(val dbFile: File, val logger: KLogger): GenericStore {
    companion object {
        fun type(dbFile: String) : StorageType {
            return type(File(dbFile))
        }

        fun type(dbFile: File) = object : StorageType {
            override fun create(logger: KLogger): SettingsStore {
                return SettingsStore(logger, ChronicleMapStore(dbFile.absoluteFile, logger))
            }
        }
    }

    private val map = ChronicleMap.of(ByteArray::class.java, ByteArray::class.java)
        .name("machine-keys")
        .entries(1_000_000)
        .constantValueSizeBySample(ByteArray(32))
        .averageKeySize(16.0)
        .createPersistedTo(dbFile)

    // byte 0 is SALT
    private val saltBuffer = ByteArray(1) {0}

    // byte 1 is private key
    private val privateKeyBuffer = ByteArray(1) {1}

    init {
        logger.info("ChronicleMap storage initialized at: '$dbFile'")
    }

    private fun getBytes(key: Any): ByteArray {
        return when (key) {
            is InetAddress -> key.address
            SettingsStore.saltKey -> saltBuffer
            SettingsStore.privateKey -> privateKeyBuffer
            else -> throw IllegalArgumentException("Unable to manage property: $key")
        }
    }

    override fun get(key: Any): ByteArray? {
        return map[getBytes(key)]
    }

    /**
     * Setting to NULL removes it
     */
    @Suppress("DuplicatedCode")
    override fun set(key: Any, bytes: ByteArray?) {
        val keyBytes = getBytes(key)

        if (bytes == null) {
            map.remove(keyBytes)
        }
        else {
            map[keyBytes] = bytes
        }
    }

    override fun close() {
        map.close()
    }
}


