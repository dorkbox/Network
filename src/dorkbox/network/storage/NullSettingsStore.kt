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
package dorkbox.network.storage

import dorkbox.network.serialization.Serialization
import dorkbox.util.exceptions.SecurityException
import dorkbox.util.storage.Storage
import java.net.InetAddress
import java.security.SecureRandom

class NullSettingsStore : SettingsStore() {
    private var serverSalt: ByteArray? = null

    override fun init(serializationManager: Serialization, storage: Storage) {}

    @Throws(SecurityException::class)
    override fun getPrivateKey(): ByteArray {
        TODO("not impl")
    }

    @Throws(SecurityException::class)
    override fun savePrivateKey(serverPrivateKey: ByteArray) {
    }

    @Throws(SecurityException::class)
    override fun getPublicKey(): ByteArray {
        TODO("not impl")
    }

    @Throws(SecurityException::class)
    override fun savePublicKey(serverPublicKey: ByteArray) {
    }

    override fun getSalt(): ByteArray {
        if (serverSalt == null) {
            val secureRandom = SecureRandom()
            serverSalt = ByteArray(32)
            secureRandom.nextBytes(serverSalt)
        }

        return serverSalt!!
    }

    @Throws(SecurityException::class)
    override fun getRegisteredServerKey(hostAddress: InetAddress): ByteArray {
        TODO("not impl")
    }

    @Throws(SecurityException::class)
    override fun addRegisteredServerKey(hostAddress: InetAddress, publicKey: ByteArray) {
        TODO("not impl")
    }

    @Throws(SecurityException::class)
    override fun removeRegisteredServerKey(hostAddress: InetAddress): Boolean {
        return true
    }

    override fun close() {}
}
