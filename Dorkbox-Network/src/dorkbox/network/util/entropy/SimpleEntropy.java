package dorkbox.network.util.entropy;

import java.security.SecureRandom;

public class SimpleEntropy {

    public static Object create() {
        return new SimpleEntropy();
    }

    public byte[] get() {
        SecureRandom secureRandom = new SecureRandom();
        byte[] rand = new byte[256];
        secureRandom.nextBytes(rand);
        return rand;
    }
}
