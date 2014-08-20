package dorkbox.network.util;

import java.security.SecureRandom;

public class RandomConnectionIdGenerator {

    private static final SecureRandom secureRandom = new SecureRandom();

    private static final ThreadLocal<Integer> uniqueNum =
            new ThreadLocal<Integer>() {
                @Override
                protected Integer initialValue() {
                    return secureRandom.nextInt();
                }
    };

    public static Integer getRandom() {
        return uniqueNum.get();
    }
}