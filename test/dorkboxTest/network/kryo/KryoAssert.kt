package dorkboxTest.network.kryo

import org.junit.Assert

/**
 * Junit Assert wrapper methods class
 */
object KryoAssert {
    fun assertDoubleEquals(expected: Double, actual: Double) {
        Assert.assertEquals(expected, actual, 0.0)
    }

    fun assertFloatEquals(expected: Float, actual: Float) {
        Assert.assertEquals(expected.toDouble(), actual.toDouble(), 0.0)
    }

    fun assertFloatEquals(expected: Float, actual: Double) {
        Assert.assertEquals(expected.toDouble(), actual, 0.0)
    }
}
