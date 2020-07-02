/*
 * Copyright 2010 dorkbox, llc
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
package dorkbox.network.store

import dorkbox.network.connection.EndPoint
import dorkbox.network.serialization.NetworkSerializationManager
import dorkbox.util.bytes.ByteArrayWrapper
import dorkbox.util.exceptions.SecurityException
import dorkbox.util.storage.Storage
import org.agrona.collections.Int2ObjectHashMap
import java.security.SecureRandom
import java.util.*

/**
 * The property store is the DEFAULT type of store for the network stack.
 */
class PropertyStore : SettingsStore() {
    private lateinit var storage: Storage
    private lateinit var servers: Int2ObjectHashMap<DB_Server>

    /**
     * Method of preference for creating/getting this connection store.
     *
     * @param serializationManager this is the serialization used for saving objects into the storage database
     */
    override fun init(serializationManager: NetworkSerializationManager, storage: Storage) {
        // make sure our custom types are registered
        // only register if not ALREADY initialized, since we can initialize in the server and in the client. This creates problems if
        // running inside the same JVM (we don't permit it)
        if (!serializationManager.initialized()) {
            serializationManager.register(HashMap::class.java)
            serializationManager.register(ByteArrayWrapper::class.java)
            serializationManager.register(DB_Server::class.java)
        }

        this.storage = storage
        servers = this.storage.get(DB_Server.STORAGE_KEY, Int2ObjectHashMap())

        // this will always be null and is here to help people that copy/paste code
        var localServer = servers[DB_Server.IP_SELF]
        if (localServer == null) {
            localServer = DB_Server()
            servers[DB_Server.IP_SELF] = localServer

            // have to always specify what we are saving
            this.storage.put(DB_Server.STORAGE_KEY, servers)
        }
    }

    /**
     * Simple, property based method to getting the private key of the server
     */
    @Synchronized
    @Throws(SecurityException::class)
    override fun getPrivateKey(): ByteArray? {
        checkAccess(EndPoint::class.java)
        return servers[DB_Server.IP_SELF]!!.privateKey
    }

    /**
     * Simple, property based method for saving the private key of the server
     */
    @Synchronized
    @Throws(SecurityException::class)
    override fun savePrivateKey(serverPrivateKey: ByteArray) {
        checkAccess(EndPoint::class.java)
        servers[DB_Server.IP_SELF]!!.privateKey = serverPrivateKey

        // have to always specify what we are saving
        storage.put(DB_Server.STORAGE_KEY, servers)
    }

    /**
     * Simple, property based method to getting the public key of the server
     */
    @Synchronized
    @Throws(SecurityException::class)
    override fun getPublicKey(): ByteArray? {
        return servers[DB_Server.IP_SELF]!!.publicKey
    }

    /**
     * Simple, property based method for saving the public key of the server
     */
    @Synchronized
    @Throws(SecurityException::class)
    override fun savePublicKey(serverPublicKey: ByteArray) {
        checkAccess(EndPoint::class.java)
        servers[DB_Server.IP_SELF]!!.publicKey = serverPublicKey

        // have to always specify what we are saving
        storage.put(DB_Server.STORAGE_KEY, servers)
    }

    /**
     * Simple, property based method to getting the server salt
     */
    @Synchronized
    override fun getSalt(): ByteArray {
        val localServer = servers[DB_Server.IP_SELF]
        var salt = localServer!!.salt

        // we don't care who gets the server salt
        if (salt == null) {
            val secureRandom = SecureRandom()

            // server salt is used to salt usernames and other various connection handshake parameters
            val bytes = ByteArray(256)
            secureRandom.nextBytes(bytes)
            salt = bytes
            localServer.salt = bytes

            // have to always specify what we are saving
            storage.put(DB_Server.STORAGE_KEY, servers)
        }

        return salt
    }

    /**
     * Simple, property based method to getting a connected computer by host IP address
     */
    @Synchronized
    @Throws(SecurityException::class)
    override fun getRegisteredServerKey(hostAddress: Int): ByteArray? {
        return servers[hostAddress]?.publicKey
    }

    /**
     * Saves a connected computer by host IP address and public key
     */
    @Synchronized
    @Throws(SecurityException::class)
    override fun addRegisteredServerKey(hostAddress: Int, publicKey: ByteArray) {
        // checkAccess(RegistrationWrapper.class);
        var db_server = servers[hostAddress]
        if (db_server == null) {
            db_server = DB_Server()
        }

        db_server.publicKey = publicKey
        servers[hostAddress] = db_server

        // have to always specify what we are saving
        storage.put(DB_Server.STORAGE_KEY, servers)
    }

    /**
     * Deletes a registered computer by host IP address
     */
    @Synchronized
    @Throws(SecurityException::class)
    override fun removeRegisteredServerKey(hostAddress: Int): Boolean {
        // checkAccess(RegistrationWrapper.class);
        val db_server = servers.remove(hostAddress)

        // have to always specify what we are saving
        storage.put(DB_Server.STORAGE_KEY, servers)

        return db_server != null
    }

    override fun close() {
        storage.close()
    }
}
