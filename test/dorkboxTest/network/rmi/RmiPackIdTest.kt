package dorkboxTest.network.rmi

import dorkbox.network.rmi.RmiUtils
import org.junit.Assert
import org.junit.Test

/**
 *
 */
class RmiPackIdTest {
    @Test
    fun rmiObjectIdNegative() {
        // these are SHORTS, so SHORT.MIN -> SHORT.MAX, excluding 0
        for (rmiObjectId in Short.MIN_VALUE..-1) {
            for (rmiId in 1..Short.MAX_VALUE) {
                val packed = RmiUtils.packShorts(rmiObjectId, rmiId)
                val rmiObjectId2 = RmiUtils.unpackLeft(packed)
                val rmiId2 = RmiUtils.unpackRight(packed)

                Assert.assertEquals(rmiObjectId, rmiObjectId2)
                Assert.assertEquals(rmiId, rmiId2)
            }
        }
    }

    @Test
    fun rmiIdNegative() {
        // these are SHORTS, so SHORT.MIN -> SHORT.MAX, excluding 0
        for (rmiId in Short.MIN_VALUE..-1) {
            for (rmiObjectId in 1..Short.MAX_VALUE) {
                val packed = RmiUtils.packShorts(rmiObjectId, rmiId)
                val rmiObjectId2 = RmiUtils.unpackLeft(packed)
                val rmiId2 = RmiUtils.unpackRight(packed)

                Assert.assertEquals(rmiObjectId, rmiObjectId2)
                Assert.assertEquals(rmiId, rmiId2)
            }
        }
    }
}
