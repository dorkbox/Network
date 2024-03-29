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
package dorkbox.network.serialization

import dorkbox.bytes.decodeBase58
import dorkbox.bytes.encodeToBase58String
import dorkbox.netUtil.IP
import dorkbox.netUtil.IPv4
import dorkbox.netUtil.IPv6
import dorkbox.network.connection.CryptoManagement
import dorkbox.storage.Storage
import dorkbox.storage.serializer.SerializerBytes
import org.slf4j.Logger
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * This class provides a way for the network stack to use a database of some sort.
 */
@Suppress("unused")
class SettingsStore(storageBuilder: Storage.Builder, val logger: Logger) : AutoCloseable {
    companion object {
        /**
         * Address 0.0.0.0 or ::0 may be used as a source address for this host on this network.
         *
         * Because we assigned BOTH to the same thing, it doesn't REALLY matter which one we use, so we use BOTH!
         */
        private val local4Buffer = IPv4.WILDCARD
        private val local6Buffer = IPv6.WILDCARD

        private const val saltKey = "_salt"
        private const val privateKey_ = "_private"
    }



    private val store: Storage

    init {
        store = storageBuilder.logger(logger).apply {
            if (isStringBased) {
                // have to load/save keys+values as strings
                onLoad { _, key, value, load ->
                    // key/value will ALWAYS be strings for a string based storage system
                    key as String
                    value as String

                    // we want the keys to be easy to read in case we are using string based storage
                    val xKey: Any? = when (key) {
                        saltKey, privateKey_ -> key
                        else -> {
                            IP.toAddress(key)
                        }
                    }

                    if (xKey == null) {
                        logger.error("Unable to parse onLoad key property [$key] $value")
                        return@onLoad
                    }

                    val xValue = value.decodeBase58()
                    load(xKey, xValue)
                }.onSave { _, key, value, save ->
                    // we want the keys to be easy to read in case we are using string based storage
                    val xKey =  when (key) {
                        saltKey, privateKey_, Storage.versionTag -> key
                        is InetAddress -> IP.toString(key)
                        else -> null
                    }

                    if (xKey == null) {
                        logger.error("Unable to parse onSave key property [$key] $value")
                        return@onSave
                    }

                    val xValue = when(value) {
                        is Long -> value.toString()
                        is ByteArray -> value.encodeToBase58String()
                        else -> null
                    }

                    if (xValue == null) {
                        logger.error("Unable to parse onSave value property [$key] $value")
                        return@onSave
                    }

                    // all values are stored as bytes
                    save(xKey, xValue)
                }
            } else {
                // everything is stored as bytes. We use a serializer instead to register types for easy serialization
                serializer(SerializerBytes {
                    register(ByteArray::class.java)
                    register(Inet4Address::class.java, Serialization.inet4AddressSerializer)
                    register(Inet6Address::class.java, Serialization.inet6AddressSerializer)
                })
           }
       }.build()


        // have to init salt
        val currentValue: ByteArray? = store[saltKey]
        if (currentValue == null) {
            // server salt is used to salt usernames and other various connection handshake parameters
            val bytes = ByteArray(32) // same size as our public/private key info
            CryptoManagement.secureRandom.nextBytes(bytes)

            // have to explicitly set it (so it will save)
            store[saltKey] = bytes
        }
    }

    /**
     * @return true if both the private and public keys are non-null
     */
    fun validKeys(): Boolean {
        val pubKey = store.get(local4Buffer) as ByteArray?
        val privKey = store.get(privateKey_) as ByteArray?
        return pubKey != null && privKey != null
    }

    /**
     * the private key of the server
     *
     * @throws SecurityException
     */
    var privateKey: ByteArray
        get() {
            checkAccess(CryptoManagement::class.java)
            return store[privateKey_]!!
        }
        set(value) {
            store[privateKey_] = value
        }

    /**
     * the public key of the server
     *
     * @throws SecurityException
     */
    var publicKey: ByteArray
        get() { return store[local4Buffer]!! }
        set(value) {
            store[local4Buffer] = value
            store[local6Buffer] = value
        }

    /**
     * @return the server salt
     */
    val salt: ByteArray
        get() {
        return store[saltKey]!!
    }

    /**
     * Gets a previously registered computer by host IP address
     */
    fun getRegisteredServerKey(hostAddress: InetAddress): ByteArray? {
        return store[hostAddress]
    }

    /**
     * Saves a registered computer by host IP address and public key
     */
    fun addRegisteredServerKey(hostAddress: InetAddress, publicKey: ByteArray) {
        store[hostAddress] = publicKey
    }

    /**
     * Deletes a registered computer by host IP address
     */
    fun removeRegisteredServerKey(hostAddress: InetAddress) {
        store[hostAddress] = null
    }

    /**
     * Take the proper steps to close the storage system.
     */
    override fun close() {
        logger.debug("Closing storage...")
        try {
            store.close()
        }
        catch (exception: Exception) {
            logger.error("Unable to close the storage!", exception)
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
    internal fun checkAccess(callingClass: Class<*>) {
        val callerClass = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk { s ->
            s.map(StackWalker.StackFrame::getDeclaringClass).skip(2).findFirst()
        }.get()

        // starts with will allow for anonymous inner classes.
        if (callerClass !== callingClass) {
            val message = "Security violation by: $callerClass"
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
    internal fun checkAccess(callingClass1: Class<*>, callingClass2: Class<*>) {
        val callerClass = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk { s ->
            s.map(StackWalker.StackFrame::getDeclaringClass)
                    .skip(2)
                    .findFirst()
        }.get()

        // starts with will allow for anonymous inner classes.
        val ok = callerClass === callingClass1 || callerClass === callingClass2
        if (!ok) {
            val message = "Security violation by: $callerClass"
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
    internal fun checkAccess(callingClass1: Class<*>, callingClass2: Class<*>, callingClass3: Class<*>) {
        val callerClass = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk { s ->
            s.map(StackWalker.StackFrame::getDeclaringClass)
                    .skip(2)
                    .findFirst()
        }.get()

        // starts with will allow for anonymous inner classes.
       val ok = callerClass === callingClass1 || callerClass === callingClass2 || callerClass === callingClass3
        if (!ok) {
            val message = "Security violation by: $callerClass"
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
    @Suppress("DuplicatedCode")
    @Throws(SecurityException::class)
    internal fun checkAccess(vararg callingClasses: Class<*>) {
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
    internal fun checkAccessNoExit(callingClass: Class<*>): Boolean {
        val callerClass = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk { s ->
            s.map(StackWalker.StackFrame::getDeclaringClass)
                    .skip(2)
                    .findFirst()
        }.get()

        // starts with will allow for anonymous inner classes.
        if (callerClass !== callingClass) {
            val message = "Security violation by: $callerClass"
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
    internal fun checkAccessNoExit(callingClass1: Class<*>, callingClass2: Class<*>): Boolean {
        val callerClass = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk { s ->
            s.map(StackWalker.StackFrame::getDeclaringClass)
                    .skip(2)
                    .findFirst()
        }.get()

        // starts with will allow for anonymous inner classes.
        val ok = callerClass === callingClass1 || callerClass === callingClass2
        if (!ok) {
            val message = "Security violation by: $callerClass"
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
    internal fun checkAccessNoExit(callingClass1: Class<*>, callingClass2: Class<*>, callingClass3: Class<*>): Boolean {
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
    @Suppress("DuplicatedCode")
    internal fun checkAccessNoExit(vararg callingClasses: Class<*>): Boolean {
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
            logger.error(message)
            return false
        }
        return true
    }


}
