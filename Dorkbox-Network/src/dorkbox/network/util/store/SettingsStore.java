package dorkbox.network.util.store;


import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dorkbox.network.util.SecurityException;

/**
 * This class provides a way for the network stack to use the server's database, instead of a property file (which it uses when stand-alone)
 * <p>
 * A static "create" method, with any number of parameters, is required to create this class (which is done via reflection)
 */
public abstract class SettingsStore {

    /**
     * the specified class (or AdminActions directly) MUST be the one that is calling our admin action
     *
     * (ie, not just any class can call certain admin actions.
     *
     * OPTIMIZED METHOD
     */
    protected static final void checkAccess(Class<?> callingClass) throws SecurityException {
        Class<?> callerClass = sun.reflect.Reflection.getCallerClass(3);

        // starts with will allow for anonymous inner classes.
        if (callerClass == null || !callerClass.getName().startsWith(callingClass.getName())) {
            String message = "Security violation by: " + (callerClass == null ? "???" : callerClass.getName());
            Logger logger = LoggerFactory.getLogger(SettingsStore.class);
            logger.error(message);
            throw new SecurityException(message);
        }
    }



    /**
     * the specified class (or AdminActions directly) MUST be the one that is calling our admin action
     *
     * (ie, not just any class can call certain admin actions.
     *
     * OPTIMIZED METHOD
     */
    protected static final void checkAccess(Class<?> callingClass1, Class<?> callingClass2) throws SecurityException {
        Class<?> callerClass = sun.reflect.Reflection.getCallerClass(3);

        boolean ok = false;
        // starts with will allow for anonymous inner classes.
        if (callerClass != null) {
            String callerClassName = callerClass.getName();
            ok = callerClassName.startsWith(callingClass1.getName()) || callerClassName.startsWith(callingClass2.getName());
            ok = true;
        }

        if (!ok) {
            String message = "Security violation by: " + (callerClass == null ? "???" : callerClass.getName());
            Logger logger = LoggerFactory.getLogger(SettingsStore.class);
            logger.error(message);
            throw new SecurityException(message);
        }
    }

    /**
     * the specified class (or AdminActions directly) MUST be the one that is calling our admin action
     *
     * (ie, not just any class can call certain admin actions.
     *
     * OPTIMIZED METHOD
     */
    protected static final void checkAccess(Class<?> callingClass1, Class<?> callingClass2, Class<?> callingClass3) throws SecurityException {
        Class<?> callerClass = sun.reflect.Reflection.getCallerClass(3);

        boolean ok = false;
        // starts with will allow for anonymous inner classes.
        if (callerClass != null) {
            String callerClassName = callerClass.getName();
            ok = callerClassName.startsWith(callingClass1.getName()) ||
                 callerClassName.startsWith(callingClass2.getName()) ||
                 callerClassName.startsWith(callingClass3.getName());
        }

        if (!ok) {
            String message = "Security violation by: " + (callerClass == null ? "???" : callerClass.getName());
            Logger logger = LoggerFactory.getLogger(SettingsStore.class);
            logger.error(message);
            throw new SecurityException(message);
        }
    }





    /**
     * the specified class (or AdminActions directly) MUST be the one that is calling our admin action
     *
     * (ie, not just any class can call certain admin actions.
     */
    protected static final void checkAccess(Class<?>... callingClasses) throws SecurityException {
        Class<?> callerClass = sun.reflect.Reflection.getCallerClass(3);

        boolean ok = false;
        // starts with will allow for anonymous inner classes.
        if (callerClass != null) {
            String callerClassName = callerClass.getName();
            for (Class<?> clazz : callingClasses) {
                if (callerClassName.startsWith(clazz.getName())) {
                    ok = true;
                    break;
                }
            }
        }


        if (!ok) {
            String message = "Security violation by: " + (callerClass == null ? "???" : callerClass.getName());
            Logger logger = LoggerFactory.getLogger(SettingsStore.class);
            logger.error(message);
            throw new SecurityException(message);
        }
    }




    /**
     * the specified class (or AdminActions directly) MUST be the one that is calling our admin action
     *
     * (ie, not just any class can call certain admin actions.
     *
     * OPTIMIZED METHOD
     *
     * @return true if allowed access.
     */
    protected static final boolean checkAccessNoExit(Class<?> callingClass) {
        Class<?> callerClass = sun.reflect.Reflection.getCallerClass(3);

        // starts with will allow for anonymous inner classes.
        if (callerClass == null || !callerClass.getName().startsWith(callingClass.getName())) {
            String message = "Security violation by: " + (callerClass == null ? "???" : callerClass.getName());
            Logger logger = LoggerFactory.getLogger(SettingsStore.class);
            logger.error(message);
            return false;
        }

        return true;
    }



    /**
     * the specified class (or AdminActions directly) MUST be the one that is calling our admin action
     *
     * (ie, not just any class can call certain admin actions.
     *
     * OPTIMIZED METHOD
     *
     * @return true if allowed access.
     */
    protected static final boolean checkAccessNoExit(Class<?> callingClass1, Class<?> callingClass2) {
        Class<?> callerClass = sun.reflect.Reflection.getCallerClass(3);

        boolean ok = false;
        // starts with will allow for anonymous inner classes.
        if (callerClass != null) {
            String callerClassName = callerClass.getName();
            ok = callerClassName.startsWith(callingClass1.getName()) ||
                 callerClassName.startsWith(callingClass2.getName());
        }

        if (!ok) {
            String message = "Security violation by: " + (callerClass == null ? "???" : callerClass.getName());
            Logger logger = LoggerFactory.getLogger(SettingsStore.class);
            logger.error(message);
            return false;
        }

        return true;
    }



    /**
     * the specified class (or AdminActions directly) MUST be the one that is calling our admin action
     *
     * (ie, not just any class can call certain admin actions.
     *
     * OPTIMIZED METHOD
     *
     * @return true if allowed access.
     */
    protected static final boolean checkAccessNoExit(Class<?> callingClass1, Class<?> callingClass2, Class<?> callingClass3) {
        Class<?> callerClass = sun.reflect.Reflection.getCallerClass(3);

        boolean ok = false;
        // starts with will allow for anonymous inner classes.
        if (callerClass != null) {
            String callerClassName = callerClass.getName();
            ok = callerClassName.startsWith(callingClass1.getName()) ||
                 callerClassName.startsWith(callingClass2.getName()) ||
                 callerClassName.startsWith(callingClass3.getName());
        }

        if (!ok) {
            String message = "Security violation by: " + (callerClass == null ? "???" : callerClass.getName());
            Logger logger = LoggerFactory.getLogger(SettingsStore.class);
            logger.error(message);
            return false;
        }

        return true;
    }

    /**
     * the specified class (or AdminActions directly) MUST be the one that is calling our admin action
     *
     * (ie, not just any class can call certain admin actions.
     *
     * @return true if allowed access.
     */
    protected static final boolean checkAccessNoExit(Class<?>... callingClasses) {
        Class<?> callerClass = sun.reflect.Reflection.getCallerClass(3);

        boolean ok = false;
        // starts with will allow for anonymous inner classes.
        if (callerClass != null) {
            String callerClassName = callerClass.getName();
            for (Class<?> clazz : callingClasses) {
                if (callerClassName.startsWith(clazz.getName())) {
                    ok = true;
                    break;
                }
            }
        }

        if (!ok) {
            String message = "Security violation by: " + (callerClass == null ? "???" : callerClass.getName());
            Logger logger = LoggerFactory.getLogger(SettingsStore.class);
            logger.error(message);
            return false;
        }

        return true;
    }

    /**
     * Simple, property based method for saving the private key of the server
     */
    public abstract ECPrivateKeyParameters getPrivateKey() throws SecurityException;

    /**
     * Simple, property based method for saving the private key of the server
     */
    public abstract void savePrivateKey(ECPrivateKeyParameters serverPrivateKey) throws SecurityException ;



    /**
     * Simple, property based method to getting the public key of the server
     */
    public abstract ECPublicKeyParameters getPublicKey() throws SecurityException ;

    /**
     * Simple, property based method for saving the public key of the server
     */
    public abstract void savePublicKey(ECPublicKeyParameters serverPublicKey) throws SecurityException ;



    /**
     * @return the server salt
     */
    public abstract byte[] getSalt();


    /**
     * Gets a previously registered computer by host IP address
     */
    public abstract ECPublicKeyParameters getRegisteredServerKey(byte[] hostAddress) throws SecurityException;

    /**
     * Saves a registered computer by host IP address and public key
     */
    public abstract void addRegisteredServerKey(byte[] hostAddress, ECPublicKeyParameters publicKey) throws SecurityException;

    /**
     * Deletes a registered computer by host IP address
     * @return true if successful, false if there were problems (or it didn't exist)
     */
    public abstract boolean removeRegisteredServerKey(byte[] hostAddress) throws SecurityException;

    /**
     * Take the proper steps to shutdown the connection store, if necessary.
     */
    public abstract void shutdown();
}
