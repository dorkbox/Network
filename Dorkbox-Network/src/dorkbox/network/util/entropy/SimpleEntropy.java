package dorkbox.network.util.entropy;

import java.security.SecureRandom;

public class SimpleEntropy implements EntropyProvider {

    public static Object create() {
        return new SimpleEntropy();
    }

    @Override
    public byte[] get(String ignored) throws Exception {
        System.err.println("Using simple entropy (SecureRandom) without input mixing.");
        SecureRandom secureRandom = new SecureRandom();
        byte[] rand = new byte[256];
        secureRandom.nextBytes(rand);
        return rand;
    }
}
