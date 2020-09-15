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

import com.esotericsoftware.kryo.serializers.MapSerializer
import dorkbox.netUtil.IPv4
import dorkbox.netUtil.IPv6
import dorkbox.network.connection.CryptoManagement
import dorkbox.util.bytes.ByteArrayWrapper
import dorkbox.util.exceptions.SecurityException
import dorkbox.util.storage.Storage
import dorkbox.util.storage.StorageBuilder
import dorkbox.util.storage.StorageSystem
import org.agrona.collections.Object2NullableObjectHashMap
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.SecureRandom

/**
 * This class provides a way for the network stack to use the server's database, instead of a property file (which it uses when stand-alone)
 *
 * A static "create" method, with any number of parameters, is required to create this class (which is done via reflection)
 */
open class SettingsStore(internal val builder: StorageBuilder) : AutoCloseable {
    private lateinit var storage: Storage
    private lateinit var servers: Object2NullableObjectHashMap<InetAddress, DB_Server>

    /**
     * Address 0.0.0.0 or ::0 may be used as a source address for this host on this network.
     *
     * Because we assigned BOTH to the same thing, it doesn't matter which one we use
     */
    private val ipv4Host = IPv4.WILDCARD
    private val ipv6Host = IPv6.WILDCARD

    /**
     * Initialize using the provided serialization manager.
     */
    fun init() {
        if (builder is StorageSystem.DiskBuilder) {
            // NOTE: there are problems if our serializer is THE SAME serializer used by the network stack!
            // make sure our custom types are registered!
            builder.serializationManager.register(Object2NullableObjectHashMap::class.java, MapSerializer())
            builder.serializationManager.register(ByteArrayWrapper::class.java)
            builder.serializationManager.register(DB_Server::class.java)

            // NOTE: These only serialize the IP address, not the hostname!
            builder.serializationManager.register(Inet4Address::class.java, Inet4AddressIpSerializer())
            builder.serializationManager.register(Inet6Address::class.java, Inet6AddressIpSerializer())
        }

        this.storage = builder.build()

        servers = this.storage.get(DB_Server.STORAGE_KEY, Object2NullableObjectHashMap())

        // this will always be null and is here to help people that copy/paste code
        var localServer = servers[ipv4Host]
        if (localServer == null) {
            localServer = DB_Server()
            servers[ipv4Host] = localServer

            // have to always specify what we are saving
            this.storage.put(DB_Server.STORAGE_KEY, servers)
        }

        if (servers[ipv6Host] == null) {
            servers[ipv6Host] = localServer

            // have to always specify what we are saving
            this.storage.put(DB_Server.STORAGE_KEY, servers)
        }
    }

    /**
     * Used to register the different serialization registrations for this store
     */
    fun getSerializationRegistrations(): List<Class<out Any>> {
        // make sure our custom types are registered
        // only register if not ALREADY initialized, since we can initialize in the server and in the client. This creates problems if
        // running inside the same JVM
        return listOf(Object2NullableObjectHashMap::class.java,
                      ByteArrayWrapper::class.java,
                      DB_Server::class.java
        )
    }

    /**
     * @return the private key of the server
     */
    @Synchronized
    fun getPrivateKey(): ByteArray? {
        checkAccess(CryptoManagement::class.java)
        return servers[ipv4Host]!!.privateKey
    }

    /**
     * Saves the private key of the server
     */
    @Synchronized
    fun savePrivateKey(serverPrivateKey: ByteArray) {
        checkAccess(CryptoManagement::class.java)
        servers[ipv4Host]!!.privateKey = serverPrivateKey

        // have to always specify what we are saving
        storage.put(DB_Server.STORAGE_KEY, servers)
    }

    /**
     * @return the public key of the server
     */
    @Synchronized
    fun getPublicKey(): ByteArray? {
        return servers[ipv4Host]!!.publicKey
    }

    /**
     * Saves the public key of the server
     */
    @Synchronized
    fun savePublicKey(serverPublicKey: ByteArray) {
        checkAccess(CryptoManagement::class.java)
        servers[ipv4Host]!!.publicKey = serverPublicKey

        // have to always specify what we are saving
        storage.put(DB_Server.STORAGE_KEY, servers)
    }

    /**
     * @return the server salt
     */
    @Synchronized
    fun getSalt(): ByteArray {
        val localServer = servers[ipv4Host]
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
     * Gets a previously registered computer by host IP address
     */
    @Synchronized
    fun getRegisteredServerKey(hostAddress: InetAddress): ByteArray? {
        return servers[hostAddress]?.publicKey
    }

    /**
     * Saves a connected computer by host IP address and public key
     */
    @Synchronized
    fun addRegisteredServerKey(hostAddress: InetAddress, publicKey: ByteArray) {
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
     *
     * @return true if successful, false if there were problems (or it didn't exist)
     */
    @Synchronized
    fun removeRegisteredServerKey(hostAddress: InetAddress): Boolean {
        // checkAccess(RegistrationWrapper.class);
        val db_server = servers.remove(hostAddress)

        // have to always specify what we are saving
        storage.put(DB_Server.STORAGE_KEY, servers)

        return db_server != null
    }


    /**
     * Take the proper steps to close the storage system.
     */
    override fun close() {
        storage.close()
    }


    /**
     * the specified class (or AdminActions directly) MUST be the one that is calling our admin action
     *
     *
     * (ie, not just any class can call certain admin actions.
     *
     *
     * OPTIMIZED METHOD
     */
    @Throws(SecurityException::class)
    protected fun checkAccess(callingClass: Class<*>) {
        val callerClass = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk { s ->
            s.map(StackWalker.StackFrame::getDeclaringClass).skip(2).findFirst()
        }.get()

        // starts with will allow for anonymous inner classes.
        if (callerClass !== callingClass) {
            val message = "Security violation by: $callerClass"
            val logger = LoggerFactory.getLogger(SettingsStore::class.java)
            logger.error(message)
            throw SecurityException(message)
        }
    }

    /**
     * the specified class (or AdminActions directly) MUST be the one that is calling our admin action
     *
     *
     * (ie, not just any class can call certain admin actions.
     *
     *
     * OPTIMIZED METHOD
     */
    @Throws(SecurityException::class)
    protected fun checkAccess(callingClass1: Class<*>, callingClass2: Class<*>) {
        val callerClass = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk { s ->
            s.map(StackWalker.StackFrame::getDeclaringClass)
                    .skip(2)
                    .findFirst()
        }.get()

        // starts with will allow for anonymous inner classes.
        val ok = callerClass === callingClass1 || callerClass === callingClass2
        if (!ok) {
            val message = "Security violation by: $callerClass"
            val logger = LoggerFactory.getLogger(SettingsStore::class.java)
            logger.error(message)
            throw SecurityException(message)
        }
    }

    /**
     * the specified class (or AdminActions directly) MUST be the one that is calling our admin action
     *
     *
     * (ie, not just any class can call certain admin actions.
     *
     *
     * OPTIMIZED METHOD
     */
    @Throws(SecurityException::class)
    protected fun checkAccess(callingClass1: Class<*>, callingClass2: Class<*>, callingClass3: Class<*>) {
        val callerClass = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk { s ->
            s.map(StackWalker.StackFrame::getDeclaringClass)
                    .skip(2)
                    .findFirst()
        }.get()

        // starts with will allow for anonymous inner classes.
       val ok = callerClass === callingClass1 || callerClass === callingClass2 || callerClass === callingClass3
        if (!ok) {
            val message = "Security violation by: $callerClass"
            val logger = LoggerFactory.getLogger(SettingsStore::class.java)
            logger.error(message)
            throw SecurityException(message)
        }
    }

    /**
     * the specified class (or AdminActions directly) MUST be the one that is calling our admin action
     *
     *
     * (ie, not just any class can call certain admin actions.
     */
    @Throws(SecurityException::class)
    protected fun checkAccess(vararg callingClasses: Class<*>) {
        val callerClass = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk { s ->
            s.map(StackWalker.StackFrame::getDeclaringClass)
                    .skip(2)
                    .findFirst()
        }.get()

        var ok = false
        // starts with will allow for anonymous inner classes.
        for (clazz in callingClasses) {
            if (callerClass === clazz) {
                ok = true
                break
            }
        }

        if (!ok) {
            val message = "Security violation by: $callerClass"
            val logger = LoggerFactory.getLogger(SettingsStore::class.java)
            logger.error(message)
            throw SecurityException(message)
        }
    }

    /**
     * the specified class (or AdminActions directly) MUST be the one that is calling our admin action
     *
     *
     * (ie, not just any class can call certain admin actions.
     *
     *
     * OPTIMIZED METHOD
     *
     * @return true if allowed access.
     */
    protected fun checkAccessNoExit(callingClass: Class<*>): Boolean {
        val callerClass = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk { s ->
            s.map(StackWalker.StackFrame::getDeclaringClass)
                    .skip(2)
                    .findFirst()
        }.get()

        // starts with will allow for anonymous inner classes.
        if (callerClass !== callingClass) {
            val message = "Security violation by: $callerClass"
            val logger = LoggerFactory.getLogger(SettingsStore::class.java)
            logger.error(message)
            return false
        }
        return true
    }

    /**
     * the specified class (or AdminActions directly) MUST be the one that is calling our admin action
     *
     *
     * (ie, not just any class can call certain admin actions.
     *
     *
     * OPTIMIZED METHOD
     *
     * @return true if allowed access.
     */
    protected fun checkAccessNoExit(callingClass1: Class<*>, callingClass2: Class<*>): Boolean {
        val callerClass = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk { s ->
            s.map(StackWalker.StackFrame::getDeclaringClass)
                    .skip(2)
                    .findFirst()
        }.get()

        // starts with will allow for anonymous inner classes.
        var ok = callerClass === callingClass1 || callerClass === callingClass2
        if (!ok) {
            val message = "Security violation by: $callerClass"
            val logger = LoggerFactory.getLogger(SettingsStore::class.java)
            logger.error(message)
            return false
        }
        return true
    }

    /**
     * the specified class (or AdminActions directly) MUST be the one that is calling our admin action
     *
     *
     * (ie, not just any class can call certain admin actions.
     *
     *
     * OPTIMIZED METHOD
     *
     * @return true if allowed access.
     */
    protected fun checkAccessNoExit(callingClass1: Class<*>, callingClass2: Class<*>, callingClass3: Class<*>): Boolean {
//        val callerClass = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).callerClass
        val callerClass = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk { s ->
            s.map(StackWalker.StackFrame::getDeclaringClass)
                    .skip(2)
                    .findFirst()
        }.get()

        // starts with will allow for anonymous inner classes.
        val ok = callerClass === callingClass1 || callerClass === callingClass2 || callerClass === callingClass3
        if (!ok) {
            val message = "Security violation by: $callerClass"
            val logger = LoggerFactory.getLogger(SettingsStore::class.java)
            logger.error(message)
            return false
        }
        return true
    }

    /**
     * the specified class (or AdminActions directly) MUST be the one that is calling our admin action
     *
     *
     * (ie, not just any class can call certain admin actions.
     *
     * @return true if allowed access.
     */
    protected fun checkAccessNoExit(vararg callingClasses: Class<*>): Boolean {
//        val callerClass = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).callerClass
        val callerClass = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk { s ->
            s.map(StackWalker.StackFrame::getDeclaringClass)
                    .skip(2)
                    .findFirst()
        }.get()

        var ok = false
        // starts with will allow for anonymous inner classes.
        for (clazz in callingClasses) {
            if (callerClass === clazz) {
                ok = true
                break
            }
        }

        if (!ok) {
            val message = "Security violation by: $callerClass"
            val logger = LoggerFactory.getLogger(SettingsStore::class.java)
            logger.error(message)
            return false
        }
        return true
    }
}
