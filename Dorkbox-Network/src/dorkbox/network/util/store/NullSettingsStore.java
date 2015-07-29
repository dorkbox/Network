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

import dorkbox.util.SerializationManager;
import dorkbox.util.exceptions.SecurityException;
import dorkbox.util.storage.Storage;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;

import java.io.IOException;
import java.security.SecureRandom;

public
class NullSettingsStore extends SettingsStore {

    private byte[] serverSalt;

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
        if (this.serverSalt == null) {
            SecureRandom secureRandom = new SecureRandom();
            this.serverSalt = new byte[32];
            secureRandom.nextBytes(this.serverSalt);
        }

        return this.serverSalt;
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
