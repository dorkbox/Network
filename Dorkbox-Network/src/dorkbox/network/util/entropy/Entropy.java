package dorkbox.network.util.entropy;


import java.lang.reflect.Method;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dorkbox.network.util.exceptions.InitializationException;


public class Entropy {

    private static volatile EntropyProvider provider = null;

    /** Starts the process, and gets, the next amount of entropy bytes */
    public static byte[] get(String messageForUser) throws InitializationException {
        synchronized (Entropy.class) {
            try {
                if (provider == null) {
                    Entropy.init(SimpleEntropy.class);
                }

                return provider.get(messageForUser);
            } catch (Exception e) {
                Logger logger = LoggerFactory.getLogger(Entropy.class);
                String error = "Unable to get entropy bytes for " + provider.getClass();
                logger.error(error, e);
                throw new InitializationException(error);
            }
        }
    }

    /**
     * Will only set the Entropy provider if it has not ALREADY been set!
     */
    public static void init(Class<? extends EntropyProvider> providerClass, Object... args) throws InitializationException {
        synchronized (Entropy.class) {
            if (provider == null) {
                Exception exception = null;

                // use reflection to create the provider.
                try {
                    Method createMethod = null;
                    Method[] declaredMethods = providerClass.getDeclaredMethods();
                    for (Method m : declaredMethods) {
                        if (m.getName().equals("create")) {
                            createMethod = m;
                            break;
                        }
                    }

                    if (createMethod != null) {
                        createMethod.setAccessible(true);

                        if (args.length == 0) {
                            provider = (EntropyProvider) createMethod.invoke(null);
                        } else {
                            provider = (EntropyProvider) createMethod.invoke(null, args);
                        }
                        return;
                    }
                } catch (Exception e) {
                    exception = e;
                }

                Logger logger = LoggerFactory.getLogger(Entropy.class);
                String error = "Unable to create entropy provider for " + providerClass + " with " + args.length + " args";
                if (exception != null) {
                    logger.error(error, exception);
                } else {
                    logger.error(error);
                }

                throw new InitializationException(error);
            }
        }
    }
}
