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


import static org.junit.Assert.fail;

import org.junit.Test;

import dorkbox.network.BaseTest;
import dorkbox.util.exceptions.SecurityException;


public
class PropertyStoreAccessTest extends BaseTest {

    @Test
    public
    void testAccess() throws SecurityException {
        PropertyStore store = new PropertyStore();
        store.init(null, null);

        System.out.println();
        System.out.println();
        System.out.println();
        System.out.println("security violations are expected");
        System.out.println();
        System.out.println();
        System.out.println();

        try {
            store.getPrivateKey(); // this should throw a security exception
            fail("Failed to pass the security test");
        } catch (SecurityException ignored) {
        }

        try {
            store.savePrivateKey(null); // this should throw a security exception
            fail("Failed to pass the security test");
        } catch (SecurityException ignored) {
        }

        try {
            store.getPublicKey(); // this should throw a security exception
            fail("Failed to pass the security test");
        } catch (SecurityException ignored) {
        }

        try {
            store.savePublicKey(null); // this should throw a security exception
            fail("Failed to pass the security test");
        } catch (SecurityException ignored) {
        }

        try {
            store.getRegisteredServerKey(null); // this should throw a security exception
            fail("Failed to pass the security test");
        } catch (SecurityException ignored) {
        }

        try {
            store.addRegisteredServerKey(null, null); // this should throw a security exception
            fail("Failed to pass the security test");
        } catch (SecurityException ignored) {
        }

        try {
            store.removeRegisteredServerKey(null); // this should throw a security exception
            fail("Failed to pass the security test");
        } catch (SecurityException ignored) {
        }

        store.getSalt(); // should not throw any errors
        store.close();
    }
}
