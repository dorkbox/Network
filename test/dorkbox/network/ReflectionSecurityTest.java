/*
 * Copyright 2011 dorkbox, llc
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
package dorkbox.network;


import dorkbox.network.util.store.SettingsStore;
import dorkbox.util.SerializationManager;
import dorkbox.util.exceptions.SecurityException;
import dorkbox.util.storage.Storage;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Method;


public
class ReflectionSecurityTest extends BaseTest {
    private static boolean RUN_TEST = false;

    @Test
    public
    void directInvocation() {
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
    public
    void reflectionInvocationA() throws Exception {
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
    public
    void reflectionInvocationB() throws Exception {
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
    public
    void correctInvocation() throws SecurityException {
        SettingsStore connectionStore = new ConnectionTestStore();
        connectionStore.getPrivateKey();
        // if it's NOT successful, the JVM will shutdown!
        System.out.println("  ConnectionSore test was successful!");
    }


    public static
    class ConnectionTestStore extends SettingsStore {
        @SuppressWarnings("unused")
        private static
        SettingsStore create() throws SecurityException {
            return new ConnectionTestStore();
        }

        @Override
        public
        void init(final SerializationManager serializationManager, final Storage storage) throws IOException {
        }

        @Override
        public
        ECPrivateKeyParameters getPrivateKey() throws SecurityException {
            return null;
        }

        @Override
        public
        void savePrivateKey(ECPrivateKeyParameters serverPrivateKey) throws SecurityException {
        }

        @Override
        public
        ECPublicKeyParameters getPublicKey() throws SecurityException {
            return null;
        }

        @Override
        public
        void savePublicKey(ECPublicKeyParameters serverPublicKey) throws SecurityException {
        }

        @Override
        public
        byte[] getSalt() {
            return null;
        }

        @Override
        public
        ECPublicKeyParameters getRegisteredServerKey(byte[] hostAddress) throws SecurityException {
            return null;
        }

        @Override
        public
        void addRegisteredServerKey(byte[] hostAddress, ECPublicKeyParameters publicKey) throws SecurityException {
        }

        @Override
        public
        boolean removeRegisteredServerKey(byte[] hostAddress) throws SecurityException {
            return true;
        }

        @Override
        public
        void shutdown() {
        }
    }
}
