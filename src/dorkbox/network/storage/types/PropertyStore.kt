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

import dorkbox.netUtil.IP
import dorkbox.network.storage.GenericStore
import dorkbox.network.storage.SettingsStore
import dorkbox.network.storage.StorageType
import dorkbox.util.Sys
import dorkbox.util.properties.SortedProperties
import mu.KLogger
import org.agrona.collections.Object2ObjectHashMap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.*

/**
 * Java property files
 */
class PropertyStore(val dbFile: File, val logger: KLogger): GenericStore {
    companion object {
        fun type(dbFile: String) : StorageType {
            return LmdbStore.type(File(dbFile))
        }

        fun type(dbFile: File) = object : StorageType {
            override fun create(logger: KLogger): SettingsStore {
                return SettingsStore(logger, PropertyStore (dbFile, logger))
            }
        }
    }

    @Volatile
    private var lastModifiedTime = 0L
    private val loadedProps = Object2ObjectHashMap<Any, ByteArray>()

    init {
        load()
        logger.info("Property file storage initialized at: '$dbFile'")
    }


    private fun load() {
        // if we cannot load, then we create a properties file.
        if (!dbFile.canRead() && !dbFile.createNewFile()) {
            throw IOException("Cannot create file")
        }

        val input = FileInputStream(dbFile)

        try {
            val properties = Properties()
            properties.load(input)
            lastModifiedTime = dbFile.lastModified()

            properties.entries.forEach {
                val key = it.key as String
                val value = it.value  as String

                when (key) {
                    SettingsStore.saltKey -> loadedProps[SettingsStore.saltKey] = Sys.hexToBytes(value)
                    SettingsStore.privateKey -> loadedProps[SettingsStore.privateKey] = Sys.hexToBytes(value)
                    else -> {
                        val address: InetAddress? = IP.fromString(key)
                        if (address != null) {
                            loadedProps[address] = Sys.hexToBytes(value)
                        } else {
                            logger.error("Unable to parse property file: $dbFile $key $value")
                        }
                    }
                }
            }
            properties.clear()
        } catch (e: IOException) {
            logger.error("Cannot load properties!", e)
            e.printStackTrace()
        } finally {
            input.close()
        }
    }

    override operator fun get(key: Any): ByteArray? {
        // we want to check the last modified time when getting, because if we edit the on-disk file, we want to those changes
        val lastModifiedTime = dbFile.lastModified()
        if (this.lastModifiedTime != lastModifiedTime) {
            // we want to reload the info
            load()
        }


        val any = loadedProps[key]
        if (any != null) {
            return any
        }

        return null
    }

    /**
     * Setting to NULL removes it
     */
    override operator fun set(key: Any, bytes: ByteArray?) {
        val hasChanged = if (bytes == null) {
            loadedProps.remove(key) != null
        } else {
            val prev = loadedProps.put(key, bytes)
            !prev.contentEquals(bytes)
        }

        // every time we set info, we want to save it to disk (so the file on disk will ALWAYS be current, and so we can modify it as we choose)
        if (hasChanged) {
            save()
        }
    }

    fun save() {
        var fos: FileOutputStream? = null
        try {
            fos = FileOutputStream(dbFile, false)

            val properties = SortedProperties()

            loadedProps.forEach { (key, value) ->
                when (key) {
                    "_salt" -> properties[key] = Sys.bytesToHex(value)
                    "_private" -> properties[key] = Sys.bytesToHex(value)
                    is InetAddress -> properties[IP.toString(key)] = Sys.bytesToHex(value)
                    else -> logger.error("Unable to parse property [$key] $value")
                }
            }

            properties.store(fos, "Server salt, public/private keys, and remote computer public Keys")
            fos.flush()
            properties.clear()
            lastModifiedTime = dbFile.lastModified()
        } catch (e: IOException) {
            logger.error("Properties cannot save to: $dbFile", e)
        } finally {
            if (fos != null) {
                try {
                    fos.close()
                } catch (ignored: IOException) {
                }
            }
        }
    }

    override fun close() {
        save()
    }
}


