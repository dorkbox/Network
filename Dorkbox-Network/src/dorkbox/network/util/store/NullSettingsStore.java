package dorkbox.network.util.store;


import java.security.SecureRandom;

import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;

import dorkbox.network.util.SecurityException;

public class NullSettingsStore extends SettingsStore {

    private byte[] serverSalt;

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
        if (this.serverSalt == null) {
            SecureRandom secureRandom = new SecureRandom();
            this.serverSalt = new byte[32];
            secureRandom.nextBytes(this.serverSalt);
        }

        return this.serverSalt;
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
