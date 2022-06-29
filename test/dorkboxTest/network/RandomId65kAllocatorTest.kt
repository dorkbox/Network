package dorkboxTest.network

import dorkbox.network.handshake.RandomId65kAllocator
import org.junit.Assert
import org.junit.Test

class RandomId65kAllocatorTest : BaseTest() {
    @Test
    fun reassignment() {
        val allocator = RandomId65kAllocator(1, 7)

        repeat(6) {
            allocator.allocate()
        }

        allocator.free(1)
        allocator.free(2)
        allocator.free(5)

        Assert.assertEquals(1, allocator.allocate())
        Assert.assertEquals(2, allocator.allocate())
        Assert.assertEquals(5, allocator.allocate())
    }

    @Test
    fun overAllocate() {
        val allocator = RandomId65kAllocator(1, 7)

        repeat(6) {
            allocator.allocate()
        }
        try {
            allocator.allocate()
            Assert.fail("Allocate should have no more left!")
        } catch (ignored: Exception) {
        }
    }

    @Test
    fun unequalAllocate() {
        val allocator = RandomId65kAllocator(1, 3)

        repeat(2) {
            allocator.allocate()
        }

        allocator.free(1)
        allocator.free(2)

        try {
            allocator.free(5)
            Assert.fail("Free should have failed!")
        } catch (ignored: Exception) {
        }
    }
}
