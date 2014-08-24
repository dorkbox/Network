package dorkbox.network.util.entropy;


import java.lang.reflect.Method;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dorkbox.network.util.exceptions.InitializationException;


public class Entropy {

    private static volatile Object provider = null;

    public static byte[] get() throws InitializationException {
        synchronized (Entropy.class) {
            boolean failed = false;
            // use reflection to get the entropy bytes.
            try {
                Method createMethod = null;
                Method[] declaredMethods = provider.getClass().getDeclaredMethods();
                for (Method m : declaredMethods) {
                    if (m.getName().equals("get")) {
                        createMethod = m;
                        break;
                    }
                }

                if (createMethod != null) {
                    createMethod.setAccessible(true);
                    return (byte[]) createMethod.invoke(provider);
                } else {
                    failed = true;
                }

            } catch (Exception e) {
                Logger logger = LoggerFactory.getLogger(Entropy.class);
                String error = "Unable to create get entropy bytes for " + provider.getClass();
                logger.error(error, e);
                throw new InitializationException(error);
            }

            if (failed) {
                Logger logger = LoggerFactory.getLogger(Entropy.class);
                String error = "Unable to create get entropy bytes for " + provider.getClass();
                logger.error(error);
                throw new InitializationException(error);
            }

            return null;
        }
    }

    /**
     * Will only set the Entropy provider if it has not ALREADY beed set!
     */
    public static Object init(Class<?> providerClass, Object... args) throws InitializationException {
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
                            provider = createMethod.invoke(null);
                            return provider;
                        } else {
                            provider = createMethod.invoke(null, args);
                            return provider;
                        }
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

            return provider;
        }
    }
}
