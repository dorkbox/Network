
package dorkbox.network;


import java.lang.reflect.Method;

import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.junit.Test;

import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.SecurityException;
import dorkbox.network.util.store.SettingsStore;



public class ReflectionSecurityTest extends BaseTest {
    private static boolean RUN_TEST = false;

    @Test
    public void directInvocation() {
        if (!RUN_TEST) {
            System.out.println("  Not running test -- Skipping DirectInvocation test.");
            // since we exit the JVM on failure, we only run the test in special test-cases, not every time.
            return;
        }

        ConnectionTestStore connectionTestStore = new ConnectionTestStore();
        connectionTestStore.getSalt();
        System.err.println("SHOULD FAIL TEST");
    }

    @Test
    public void reflectionInvocationA() throws Exception {
        if (!RUN_TEST) {
            // since we exit the JVM on failure, we only run the test in special test-cases, not every time.
            System.out.println("  Not running test -- Skipping ReflectionInvocationA test.");
            return;
        }

        // use reflection to create the store.
        Method createMethod = null;
        Method[] declaredMethods = ConnectionTestStore.class.getDeclaredMethods();
        for (Method m : declaredMethods) {
            String name = m.getName();
            if (name.equals("create")) {
                createMethod = m;
                break;
            }
        }

        if (createMethod != null) {
            createMethod.setAccessible(true);

            createMethod.invoke(null);
            // if it's NOT successful, the JVM will shutdown!
            System.err.println("SHOULD FAIL TEST");
        }
    }

    @Test
    public void reflectionInvocationB() throws Exception {
        if (!RUN_TEST) {
            // since we exit the JVM on failure, we only run the test in special test-cases, not every time.
            System.out.println("  Not running test -- Skipping ReflectionInvocationB test.");
            return;
        }

        Class<?> clazz = Class.forName(ConnectionTestStore.class.getName());
        Method createMethod = null;
        Method[] declaredMethods = clazz.getDeclaredMethods();
        for (Method m : declaredMethods) {
            String name = m.getName();
            if (name.equals("create")) {
                createMethod = m;
                break;
            }
        }

        if (createMethod != null) {
            createMethod.setAccessible(true);

            createMethod.invoke(null);
            // if it's NOT successful, the JVM will shutdown!
            System.err.println("SHOULD FAIL TEST");
        }

    }

    @Test
    public void correctInvocation() throws SecurityException, InitializationException {
        SettingsStore connectionStore = new ConnectionTestStore();
        connectionStore.getPrivateKey();
        // if it's NOT successful, the JVM will shutdown!
        System.out.println("  ConnectionSore test was successful!");
    }


    public static class ConnectionTestStore extends SettingsStore {
        @SuppressWarnings("unused")
        private static SettingsStore create() throws SecurityException {
            return new ConnectionTestStore();
        }

        @Override
        public ECPrivateKeyParameters getPrivateKey() throws SecurityException {
            return null;
        }

        @Override
        public void savePrivateKey(ECPrivateKeyParameters serverPrivateKey) throws SecurityException {
        }

        @Override
        public ECPublicKeyParameters getPublicKey() throws SecurityException {
            return null;
        }

        @Override
        public void savePublicKey(ECPublicKeyParameters serverPublicKey) throws SecurityException {
        }

        @Override
        public byte[] getSalt() {
            return null;
        }

        @Override
        public ECPublicKeyParameters getRegisteredServerKey(byte[] hostAddress) throws SecurityException {
            return null;
        }

        @Override
        public void addRegisteredServerKey(byte[] hostAddress, ECPublicKeyParameters publicKey) throws SecurityException {
        }

        @Override
        public boolean removeRegisteredServerKey(byte[] hostAddress) throws SecurityException {
            return true;
        }

        @Override
        public void shutdown() {
        }
    }
}
