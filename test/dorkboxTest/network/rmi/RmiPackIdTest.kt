/*
 * Copyright 2020 dorkbox, llc
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
