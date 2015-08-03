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
package dorkbox.network.util.store;

import dorkbox.util.OS;
import dorkbox.util.SerializationManager;
import dorkbox.util.exceptions.SecurityException;
import dorkbox.util.storage.Storage;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * This class provides a way for the network stack to use the server's database, instead of a property file (which it uses when stand-alone)
 * <p/>
 * A static "create" method, with any number of parameters, is required to create this class (which is done via reflection)
 */
@SuppressWarnings({"deprecation", "unused"})
public abstract
class SettingsStore {

    /**
     * Initialize the settingsStore with the provided serialization manager.
     */
    public abstract
    void init(SerializationManager serializationManager, Storage storage) throws IOException;


    private static String getCallingClass() {
        // java < 8, it is SIGNIFICANTLY faster to call sun.reflect.Reflection.getCallerClass
        // java >= 8, Thread.stackTrace was fixed, so it is the now preferred method
        if (OS.javaVersion < 8) {
            Class<?> callerClass = sun.reflect.Reflection.getCallerClass(3);

            if (callerClass == null) {
                return null;

            }
            return callerClass.getName();
        } else {
            StackTraceElement[] cause = Thread.currentThread().getStackTrace();
            if (cause == null || cause.length < 4) {
                return null;
            }

            StackTraceElement stackTraceElement = cause[4];
            if (stackTraceElement == null) {
                return null;
            }

            return stackTraceElement.getClassName();
        }
    }


    /**
     * the specified class (or AdminActions directly) MUST be the one that is calling our admin action
     * <p/>
     * (ie, not just any class can call certain admin actions.
     * <p/>
     * OPTIMIZED METHOD
     */
    protected static
    void checkAccess(Class<?> callingClass) throws SecurityException {
        String callerClass = getCallingClass();

        // starts with will allow for anonymous inner classes.
        if (callerClass == null || !callerClass
                                               .startsWith(callingClass.getName())) {
            String message = "Security violation by: " + (callerClass == null ? "???" : callerClass);
            Logger logger = LoggerFactory.getLogger(SettingsStore.class);
            logger.error(message);
            throw new SecurityException(message);
        }
    }



    /**
     * the specified class (or AdminActions directly) MUST be the one that is calling our admin action
     * <p/>
     * (ie, not just any class can call certain admin actions.
     * <p/>
     * OPTIMIZED METHOD
     */
    protected static
    void checkAccess(Class<?> callingClass1, Class<?> callingClass2) throws SecurityException {
        String callerClass = getCallingClass();

        boolean ok = false;
        // starts with will allow for anonymous inner classes.
        if (callerClass != null) {
            ok = callerClass.startsWith(callingClass1.getName()) || callerClass.startsWith(callingClass2.getName());
        }

        if (!ok) {
            String message = "Security violation by: " + (callerClass == null ? "???" : callerClass);
            Logger logger = LoggerFactory.getLogger(SettingsStore.class);
            logger.error(message);
            throw new SecurityException(message);
        }
    }

    /**
     * the specified class (or AdminActions directly) MUST be the one that is calling our admin action
     * <p/>
     * (ie, not just any class can call certain admin actions.
     * <p/>
     * OPTIMIZED METHOD
     */
    protected static
    void checkAccess(Class<?> callingClass1, Class<?> callingClass2, Class<?> callingClass3) throws SecurityException {
        String callerClass = getCallingClass();

        boolean ok = false;
        // starts with will allow for anonymous inner classes.
        if (callerClass != null) {
            ok = callerClass.startsWith(callingClass1.getName()) ||
                 callerClass.startsWith(callingClass2.getName()) ||
                 callerClass.startsWith(callingClass3.getName());
        }

        if (!ok) {
            String message = "Security violation by: " + (callerClass == null ? "???" : callerClass);
            Logger logger = LoggerFactory.getLogger(SettingsStore.class);
            logger.error(message);
            throw new SecurityException(message);
        }
    }



    /**
     * the specified class (or AdminActions directly) MUST be the one that is calling our admin action
     * <p/>
     * (ie, not just any class can call certain admin actions.
     */
    protected static
    void checkAccess(Class<?>... callingClasses) throws SecurityException {
        String callerClass = getCallingClass();

        boolean ok = false;
        // starts with will allow for anonymous inner classes.
        if (callerClass != null) {
            for (Class<?> clazz : callingClasses) {
                if (callerClass.startsWith(clazz.getName())) {
                    ok = true;
                    break;
                }
            }
        }

        if (!ok) {
            String message = "Security violation by: " + (callerClass == null ? "???" : callerClass);
            Logger logger = LoggerFactory.getLogger(SettingsStore.class);
            logger.error(message);
            throw new SecurityException(message);
        }
    }



    /**
     * the specified class (or AdminActions directly) MUST be the one that is calling our admin action
     * <p/>
     * (ie, not just any class can call certain admin actions.
     * <p/>
     * OPTIMIZED METHOD
     *
     * @return true if allowed access.
     */
    protected static
    boolean checkAccessNoExit(Class<?> callingClass) {
        String callerClass = getCallingClass();

        // starts with will allow for anonymous inner classes.
        if (callerClass == null || !callerClass
                                               .startsWith(callingClass.getName())) {
            String message = "Security violation by: " + (callerClass == null ? "???" : callerClass);
            Logger logger = LoggerFactory.getLogger(SettingsStore.class);
            logger.error(message);
            return false;
        }

        return true;
    }



    /**
     * the specified class (or AdminActions directly) MUST be the one that is calling our admin action
     * <p/>
     * (ie, not just any class can call certain admin actions.
     * <p/>
     * OPTIMIZED METHOD
     *
     * @return true if allowed access.
     */
    protected static
    boolean checkAccessNoExit(Class<?> callingClass1, Class<?> callingClass2) {
        String callerClass = getCallingClass();

        boolean ok = false;
        // starts with will allow for anonymous inner classes.
        if (callerClass != null) {
            ok = callerClass.startsWith(callingClass1.getName()) || callerClass.startsWith(callingClass2.getName());
        }

        if (!ok) {
            String message = "Security violation by: " + (callerClass == null ? "???" : callerClass);
            Logger logger = LoggerFactory.getLogger(SettingsStore.class);
            logger.error(message);
            return false;
        }

        return true;
    }



    /**
     * the specified class (or AdminActions directly) MUST be the one that is calling our admin action
     * <p/>
     * (ie, not just any class can call certain admin actions.
     * <p/>
     * OPTIMIZED METHOD
     *
     * @return true if allowed access.
     */
    protected static
    boolean checkAccessNoExit(Class<?> callingClass1, Class<?> callingClass2, Class<?> callingClass3) {
        String callerClass = getCallingClass();

        boolean ok = false;
        // starts with will allow for anonymous inner classes.
        if (callerClass != null) {
            ok = callerClass.startsWith(callingClass1.getName()) ||
                 callerClass.startsWith(callingClass2.getName()) ||
                 callerClass.startsWith(callingClass3.getName());
        }

        if (!ok) {
            String message = "Security violation by: " + (callerClass == null ? "???" : callerClass);
            Logger logger = LoggerFactory.getLogger(SettingsStore.class);
            logger.error(message);
            return false;
        }

        return true;
    }

    /**
     * the specified class (or AdminActions directly) MUST be the one that is calling our admin action
     * <p/>
     * (ie, not just any class can call certain admin actions.
     *
     * @return true if allowed access.
     */
    protected static
    boolean checkAccessNoExit(Class<?>... callingClasses) {
        String callerClass = getCallingClass();

        boolean ok = false;
        // starts with will allow for anonymous inner classes.
        if (callerClass != null) {
            for (Class<?> clazz : callingClasses) {
                if (callerClass.startsWith(clazz.getName())) {
                    ok = true;
                    break;
                }
            }
        }

        if (!ok) {
            String message = "Security violation by: " + (callerClass == null ? "???" : callerClass);
            Logger logger = LoggerFactory.getLogger(SettingsStore.class);
            logger.error(message);
            return false;
        }

        return true;
    }

    /**
     * Simple, property based method for saving the private key of the server
     */
    public abstract
    ECPrivateKeyParameters getPrivateKey() throws SecurityException;

    /**
     * Simple, property based method for saving the private key of the server
     */
    public abstract
    void savePrivateKey(ECPrivateKeyParameters serverPrivateKey) throws SecurityException;



    /**
     * Simple, property based method to getting the public key of the server
     */
    public abstract
    ECPublicKeyParameters getPublicKey() throws SecurityException;

    /**
     * Simple, property based method for saving the public key of the server
     */
    public abstract
    void savePublicKey(ECPublicKeyParameters serverPublicKey) throws SecurityException;



    /**
     * @return the server salt
     */
    public abstract
    byte[] getSalt();


    /**
     * Gets a previously registered computer by host IP address
     */
    public abstract
    ECPublicKeyParameters getRegisteredServerKey(byte[] hostAddress) throws SecurityException;

    /**
     * Saves a registered computer by host IP address and public key
     */
    public abstract
    void addRegisteredServerKey(byte[] hostAddress, ECPublicKeyParameters publicKey) throws SecurityException;

    /**
     * Deletes a registered computer by host IP address
     *
     * @return true if successful, false if there were problems (or it didn't exist)
     */
    public abstract
    boolean removeRegisteredServerKey(byte[] hostAddress) throws SecurityException;

    /**
     * Take the proper steps to shutdown the storage system.
     */
    public abstract
    void shutdown();
}
