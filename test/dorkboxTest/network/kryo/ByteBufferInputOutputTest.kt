/* Copyright (c) 2008-2020, Nathan Sweet
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * - Neither the name of Esoteric Software nor the names of its contributors may be used to endorse or promote products derived
 * from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. */
package dorkboxTest.network.kryo

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import org.junit.Assert
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.Buffer
import java.nio.ByteBuffer

internal class ByteBufferInputOutputTest : KryoTestCase() {
    @Test
    fun testByteBufferInputEnd() {
        val `in` = ByteBufferInput(ByteArrayInputStream(byteArrayOf(123, 0, 0, 0)))

        Assert.assertFalse(`in`.end())
        `in`.setPosition(4)
        Assert.assertTrue(`in`.end())
    }

    @Test
    fun testByteBufferInputPosition() {
        val byteBuffer = ByteBuffer.allocateDirect(4096)
        val inputBuffer = ByteBufferInput(byteBuffer)


        Assert.assertEquals(0, inputBuffer.position().toLong())
        Assert.assertEquals(0, inputBuffer.byteBuffer.position().toLong())


        inputBuffer.setPosition(5)
        Assert.assertEquals(5, inputBuffer.position().toLong())
        Assert.assertEquals(5, inputBuffer.byteBuffer.position().toLong())
    }

    @Test
    fun testByteBufferInputLimit() {
        val byteBuffer = ByteBuffer.allocateDirect(4096)
        val inputBuffer = ByteBufferInput(byteBuffer)


        Assert.assertEquals(4096, inputBuffer.limit().toLong())
        Assert.assertEquals(4096, inputBuffer.byteBuffer.limit().toLong())


        inputBuffer.setLimit(1000)
        Assert.assertEquals(1000, inputBuffer.limit().toLong())
        Assert.assertEquals(1000, inputBuffer.byteBuffer.limit().toLong())
    }

    @Test
    fun testByteBufferInputSkip() {
        val buffer = ByteBuffer.allocateDirect(4096)
        val inputBuffer = ByteBufferInput(buffer)
        Assert.assertEquals(0, inputBuffer.byteBuffer.position().toLong())


        inputBuffer.skip(5)
        Assert.assertEquals(5, inputBuffer.byteBuffer.position().toLong())
    }

    @Test
    fun testByteBufferOutputPosition() {
        val outputBuffer = ByteBufferOutput(4096)
        Assert.assertEquals(0, outputBuffer.position().toLong())
        Assert.assertEquals(0, outputBuffer.byteBuffer.position().toLong())


        outputBuffer.setPosition(5)
        Assert.assertEquals(5, outputBuffer.position().toLong())


        outputBuffer.writeInt(10)
        val byteBuffer = outputBuffer.byteBuffer.duplicate()
        (byteBuffer as Buffer).flip()
        val inputBuffer = ByteBufferInput(byteBuffer)
        inputBuffer.skip(5)


        Assert.assertEquals(5, byteBuffer.position().toLong())
        Assert.assertEquals(10, inputBuffer.readInt().toLong())
        Assert.assertEquals(9, byteBuffer.position().toLong())
    }
}
