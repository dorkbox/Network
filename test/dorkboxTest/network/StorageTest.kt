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
package dorkboxTest.network

import dorkbox.network.Client
import dorkbox.network.Server
import dorkbox.network.connection.Connection
import dorkbox.network.serialization.SettingsStore
import dorkbox.storage.Storage
import org.junit.Assert
import org.junit.Test
import org.slf4j.LoggerFactory
import java.io.File

class StorageTest : BaseTest() {
    @Test
    fun sharedStoreTest() {
        // we want the server + client to have the SAME info
        val sharedStore = Storage.Memory().shared()

        val serverConfig = serverConfig {
            settingsStore = sharedStore
        }
        val config = clientConfig {
            settingsStore = sharedStore
        }

        val serverSalt = Server<Connection>(serverConfig).use { it.storage.salt }
        val clientSalt = Client<Connection>(config).use { it.storage.salt }

        Assert.assertTrue(serverSalt.contentEquals(clientSalt))
    }


    @Test
    fun memoryTest() {
        val salt1 = SettingsStore(Storage.Memory(), LoggerFactory.getLogger("test1")).use { it.salt }

        val salt2 = Server<Connection>(serverConfig().apply { settingsStore = Storage.Memory() }).use { it.storage.salt }
        val salt3 = Server<Connection>(serverConfig().apply { settingsStore = Storage.Memory() }).use { it.storage.salt }

        Assert.assertFalse(salt1.contentEquals(salt2))
        Assert.assertFalse(salt1.contentEquals(salt3))
        Assert.assertFalse(salt2.contentEquals(salt3))
    }

//    @Test
//    fun lmdbTest() {
//        val file = File("test.db").absoluteFile
//        val fileLock = File("test.db-lock").absoluteFile
//
//        val salt1 = LmdbStore.type(file).create().use { it.getSalt() }
//        val salt2 = LmdbStore.type(file).create().use { it.getSalt() }
//
//        Assert.assertArrayEquals(salt1, salt2)
//        file.delete()
//        fileLock.delete()
//
//        val salt3 = Server<Connection>(serverConfig().apply { settingsStore = LmdbStore.type(file) }).use { it.storage.getSalt() }
//        val salt4 = Server<Connection>(serverConfig().apply { settingsStore = LmdbStore.type(file) }).use { it.storage.getSalt() }
//
//        Assert.assertArrayEquals(salt3, salt4)
//        Assert.assertFalse(salt1.contentEquals(salt4))
//        file.delete()
//        fileLock.delete()
//    }

    @Test
    fun propFileTest() {
        val file = File("test.db").absoluteFile

        val salt1 = SettingsStore(Storage.Property(), LoggerFactory.getLogger("test1")).use { it.salt }
        val salt2 = SettingsStore(Storage.Property(), LoggerFactory.getLogger("test2")).use { it.salt }

        Assert.assertArrayEquals(salt1, salt2)
        file.delete()

        val salt3 = Server<Connection>(serverConfig().apply { settingsStore = Storage.Property().file(file) }).use { it.storage.salt }
        val salt4 = Server<Connection>(serverConfig().apply { settingsStore = Storage.Property().file(file) }).use { it.storage.salt }

        Assert.assertArrayEquals(salt3, salt4)
        Assert.assertFalse(salt1.contentEquals(salt4))
        file.delete()
    }

//    @Test
//    fun chronicleMapTest() {
//        val file = File("test.db").absoluteFile
//
//        val salt1 = ChronicleMapStore.type(file).create().use { it.getSalt() }
//        val salt2 = ChronicleMapStore.type(file).create().use { it.getSalt() }
//
//        Assert.assertArrayEquals(salt1, salt2)
//        file.delete()
//
//        val salt3 = Server<Connection>(serverConfig().apply { settingsStore = ChronicleMapStore.type(file) }).use { it.storage.getSalt() }
//        val salt4 = Server<Connection>(serverConfig().apply { settingsStore = ChronicleMapStore.type(file) }).use { it.storage.getSalt() }
//
//        Assert.assertArrayEquals(salt3, salt4)
//        Assert.assertFalse(salt1.contentEquals(salt4))
//        file.delete()
//    }
}
