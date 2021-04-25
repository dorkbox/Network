package dorkboxTest.network.kryo;

import static org.junit.Assert.assertEquals;

/**
 * Junit Assert wrapper methods class
 */
public class KryoAssert {

    private KryoAssert() {

    }

    public static void assertDoubleEquals(double expected, double actual) {
        assertEquals(expected, actual, 0.0);
    }

    public static void assertFloatEquals(float expected, double actual) {
        assertEquals(expected, actual, 0.0);
    }
}
