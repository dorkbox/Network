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

import dorkbox.network.serialization.NetworkSerializationManager
import dorkbox.util.exceptions.SecurityException
import dorkbox.util.storage.Storage
import org.slf4j.LoggerFactory

/**
 * This class provides a way for the network stack to use the server's database, instead of a property file (which it uses when stand-alone)
 *
 *
 * A static "create" method, with any number of parameters, is required to create this class (which is done via reflection)
 */
abstract class SettingsStore : AutoCloseable {
    /**
     * Initialize the settingsStore with the provided serialization manager.
     */
    abstract fun init(serializationManager: NetworkSerializationManager, storage: Storage)

    /**
     * Simple, property based method for saving the private key of the server
     */
    @Throws(SecurityException::class)
    abstract fun getPrivateKey(): ByteArray?

    /**
     * Simple, property based method for saving the private key of the server
     */
    @Throws(SecurityException::class)
    abstract fun savePrivateKey(serverPrivateKey: ByteArray)

    /**
     * Simple, property based method to getting the public key of the server
     */
    @Throws(SecurityException::class)
    abstract fun getPublicKey(): ByteArray?

    /**
     * Simple, property based method for saving the public key of the server
     */
    @Throws(SecurityException::class)
    abstract fun savePublicKey(serverPublicKey: ByteArray)

    /**
     * @return the server salt
     */
    abstract fun getSalt(): ByteArray

    /**
     * Gets a previously registered computer by host IP address
     */
    @Throws(SecurityException::class)
    abstract fun getRegisteredServerKey(hostAddress: Int): ByteArray?

    /**
     * Saves a registered computer by host IP address and public key
     */
    @Throws(SecurityException::class)
    abstract fun addRegisteredServerKey(hostAddress: Int, publicKey: ByteArray)

    /**
     * Deletes a registered computer by host IP address
     *
     * @return true if successful, false if there were problems (or it didn't exist)
     */
    @Throws(SecurityException::class)
    abstract fun removeRegisteredServerKey(hostAddress: Int): Boolean

    /**
     * Take the proper steps to close the storage system.
     */
    abstract override fun close()


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
