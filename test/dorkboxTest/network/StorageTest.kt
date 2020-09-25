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
package dorkboxTest.network

import dorkbox.network.Client
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.network.storage.SettingsStore
import dorkbox.network.storage.types.ChronicleMapStore
import dorkbox.network.storage.types.LmdbStore
import dorkbox.network.storage.types.MemoryStore
import dorkbox.network.storage.types.PropertyStore
import kotlinx.coroutines.runBlocking
import mu.KLogger
import org.junit.Assert
import org.junit.Test
import java.io.File

class StorageTest : BaseTest() {
    @Test
    fun sharedStoreTest() {
        // we want the server + client to have the SAME info
        val store = MemoryStore.type().create()

        val server = object : Server<Connection>(serverConfig()) {
            override fun createSettingsStore(logger: KLogger): SettingsStore {
                return store
            }
        }

        val client = object: Client<Connection>(clientConfig()) {
            override fun createSettingsStore(logger: KLogger): SettingsStore {
                return store
            }
        }

        server.bind()

        runBlocking {
            client.connect("localhost")
            server.close()
        }
    }


    @Test
    fun memoryTest() {
        val salt1 = MemoryStore.type().create().use { it.getSalt() }

        val salt2 = Server<Connection>(serverConfig().apply { settingsStore = MemoryStore.type() }).use { it.settingsStore.getSalt() }
        val salt3 = Server<Connection>(serverConfig().apply { settingsStore = MemoryStore.type() }).use { it.settingsStore.getSalt() }

        Assert.assertFalse(salt1.contentEquals(salt2))
        Assert.assertFalse(salt1.contentEquals(salt3))
        Assert.assertFalse(salt2.contentEquals(salt3))
    }

    @Test
    fun lmdbTest() {
        val file = File("test.db").absoluteFile
        val fileLock = File("test.db-lock").absoluteFile

        val salt1 = LmdbStore.type(file).create().use { it.getSalt() }
        val salt2 = LmdbStore.type(file).create().use { it.getSalt() }

        Assert.assertArrayEquals(salt1, salt2)
        file.delete()
        fileLock.delete()

        val salt3 = Server<Connection>(serverConfig().apply { settingsStore = LmdbStore.type(file) }).use { it.settingsStore.getSalt() }
        val salt4 = Server<Connection>(serverConfig().apply { settingsStore = LmdbStore.type(file) }).use { it.settingsStore.getSalt() }

        Assert.assertArrayEquals(salt3, salt4)
        Assert.assertFalse(salt1.contentEquals(salt4))
        file.delete()
        fileLock.delete()
    }

    @Test
    fun propFileTest() {
        val file = File("test.db").absoluteFile

        val salt1 = PropertyStore.type(file).create().use { it.getSalt() }
        val salt2 = PropertyStore.type(file).create().use { it.getSalt() }

        Assert.assertArrayEquals(salt1, salt2)
        file.delete()

        val salt3 = Server<Connection>(serverConfig().apply { settingsStore = PropertyStore.type(file) }).use { it.settingsStore.getSalt() }
        val salt4 = Server<Connection>(serverConfig().apply { settingsStore = PropertyStore.type(file) }).use { it.settingsStore.getSalt() }

        Assert.assertArrayEquals(salt3, salt4)
        Assert.assertFalse(salt1.contentEquals(salt4))
        file.delete()
    }

    @Test
    fun chronicleMapTest() {
        val file = File("test.db").absoluteFile

        val salt1 = ChronicleMapStore.type(file).create().use { it.getSalt() }
        val salt2 = ChronicleMapStore.type(file).create().use { it.getSalt() }

        Assert.assertArrayEquals(salt1, salt2)
        file.delete()

        val salt3 = Server<Connection>(serverConfig().apply { settingsStore = ChronicleMapStore.type(file) }).use { it.settingsStore.getSalt() }
        val salt4 = Server<Connection>(serverConfig().apply { settingsStore = ChronicleMapStore.type(file) }).use { it.settingsStore.getSalt() }

        Assert.assertArrayEquals(salt3, salt4)
        Assert.assertFalse(salt1.contentEquals(salt4))
        file.delete()
    }
}
