/* Copyright (c) 2008-2018, Nathan Sweet
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

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import dorkbox.network.serialization.AeronInput
import dorkbox.network.serialization.AeronOutput
import org.junit.Assert
import org.junit.Test
import java.io.IOException
import java.util.*

/** @author Nathan Sweet
 */
class InputOutputAeronTest : KryoTestCase() {
    @Test
    fun testAeronInputEnd() {
        val `in` = AeronInput(byteArrayOf(123, 0, 0, 0))
        Assert.assertFalse(`in`.end())
        `in`.setPosition(4)
        Assert.assertTrue(`in`.end())
    }

    @Test
    @Throws(IOException::class)
    fun testOutputBytes() {
        val output = AeronOutput(0)
        output.writeBytes(byteArrayOf(11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26))
        output.writeBytes(byteArrayOf(31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46))
        output.writeBytes(byteArrayOf(51, 52, 53, 54, 55, 56, 57, 58))
        output.writeBytes(byteArrayOf(61, 62, 63, 64, 65))
        output.flush()
        Assert.assertArrayEquals(
            byteArrayOf( //
                11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26,  //
                31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46,  //
                51, 52, 53, 54, 55, 56, 57, 58,  //
                61, 62, 63, 64, 65
            ), output.toBytes()
        )
    }

    @Test
    @Throws(IOException::class)
    fun testOutputBytesArray() {
        val buffer = byteArrayOf( //
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26,  //
            31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46,  //
            51, 52, 53, 54, 55, 56, 57, 58,  //
            61, 62, 63, 64, 65
        )
        val output = AeronOutput(buffer)
        output.setPosition(buffer.size)
        Assert.assertArrayEquals(
            byteArrayOf( //
                11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26,  //
                31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46,  //
                51, 52, 53, 54, 55, 56, 57, 58,  //
                61, 62, 63, 64, 65
            ), output.toBytes()
        )
    }

    @Test
    @Throws(IOException::class)
    fun testInputBytes() {
        val bytes = byteArrayOf( //
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26,  //
            31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46,  //
            51, 52, 53, 54, 55, 56, 57, 58,  //
            61, 62, 63, 64, 65
        )
        var input = AeronInput(bytes)
        val temp = ByteArray(1024)
        var count = input.read(temp, 512, bytes.size)
        Assert.assertEquals(bytes.size.toLong(), count.toLong())
        var temp2 = ByteArray(count)
        System.arraycopy(temp, 512, temp2, 0, count)
        Assert.assertArrayEquals(bytes, temp2)
        input = AeronInput(bytes)
        count = input.read(temp, 512, 512)
        Assert.assertEquals(bytes.size.toLong(), count.toLong())
        temp2 = ByteArray(count)
        System.arraycopy(temp, 512, temp2, 0, count)
        Assert.assertArrayEquals(bytes, temp2)
    }

    @Test
    @Throws(IOException::class)
    fun testWriteBytes() {
        val buffer = AeronOutput(512)
        buffer.writeBytes(byteArrayOf(11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26))
        buffer.writeBytes(byteArrayOf(31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46))
        buffer.writeByte(51)
        buffer.writeBytes(byteArrayOf(52, 53, 54, 55, 56, 57, 58))
        buffer.writeByte(61)
        buffer.writeByte(62)
        buffer.writeByte(63)
        buffer.writeByte(64)
        buffer.writeByte(65)
        buffer.flush()
        Assert.assertArrayEquals(
            byteArrayOf( //
                11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26,  //
                31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46,  //
                51, 52, 53, 54, 55, 56, 57, 58,  //
                61, 62, 63, 64, 65
            ), buffer.toBytes()
        )
    }

    @Test
    @Throws(IOException::class)
    fun testStrings() {
        runStringTest(AeronOutput(4096))
        runStringTest(AeronOutput(897))
        val write: Output = AeronOutput(21)
        val value = "abcdef\u00E1\u00E9\u00ED\u00F3\u00FA\u1234"
        write.writeString(value)
        var read: Input = AeronInput(write.toBytes())
        Assert.assertEquals(value, read.readString())
        write.reset()
        write.writeString(null)
        read = Input(write.toBytes())
        Assert.assertNull(read.readString())
        for (i in 0..258) runStringTest(i)
        runStringTest(1)
        runStringTest(2)
        runStringTest(127)
        runStringTest(256)
        runStringTest(1024 * 1023)
        runStringTest(1024 * 1024)
        runStringTest(1024 * 1025)
        runStringTest(1024 * 1026)
        runStringTest(1024 * 1024 * 2)
    }

    @Test
    fun testGrowingBufferForAscii() {
        // Initial size of 2.
        val output = AeronOutput(2)
        // Check that it is possible to write an ASCII string into the output buffer.
        output.writeString("node/read")
        val input: Input = AeronInput(output.toBytes(), 0, output.position())
        Assert.assertEquals("node/read", input.readString())
    }

    @Throws(IOException::class)
    private fun runStringTest(length: Int) {
        val write = Output(1024, -1)
        val buffer = StringBuilder()
        for (i in 0 until length) buffer.append(i.toChar())
        val value = buffer.toString()
        write.writeString(value)
        write.writeString(value)
        var read = AeronInput(write.toBytes())
        Assert.assertEquals(value, read.readString())
        Assert.assertEquals(value, read.readStringBuilder().toString())
        write.reset()
        write.writeString(buffer.toString())
        write.writeString(buffer.toString())
        read = AeronInput(write.toBytes())
        Assert.assertEquals(value, read.readStringBuilder().toString())
        Assert.assertEquals(value, read.readString())
        if (length <= 127) {
            write.reset()
            write.writeAscii(value)
            write.writeAscii(value)
            read = AeronInput(write.toBytes())
            Assert.assertEquals(value, read.readStringBuilder().toString())
            Assert.assertEquals(value, read.readString())
        }
    }

    @Throws(IOException::class)
    private fun runStringTest(write: AeronOutput) {
        val value1 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ\rabcdefghijklmnopqrstuvwxyz\n1234567890\t\"!`?'.,;:()[]{}<>|/@\\^$-%+=#_&~*"
        val value2 = "abcdef\u00E1\u00E9\u00ED\u00F3\u00FA\u1234"
        write.writeString("")
        write.writeString("1")
        write.writeString("22")
        write.writeString("uno")
        write.writeString("dos")
        write.writeString("tres")
        write.writeString(null)
        write.writeString(value1)
        write.writeString(value2)
        for (i in 0..126) write.writeString(i.toChar().toString())
        for (i in 0..126) write.writeString(i.toChar().toString() + "abc")
        val read = AeronInput(write.toBytes())
        Assert.assertEquals("", read.readString())
        Assert.assertEquals("1", read.readString())
        Assert.assertEquals("22", read.readString())
        Assert.assertEquals("uno", read.readString())
        Assert.assertEquals("dos", read.readString())
        Assert.assertEquals("tres", read.readString())
        Assert.assertNull(read.readString())
        Assert.assertEquals(value1, read.readString())
        Assert.assertEquals(value2, read.readString())
        for (i in 0..126) Assert.assertEquals(i.toChar().toString(), read.readString())
        for (i in 0..126) Assert.assertEquals(i.toChar().toString() + "abc", read.readString())

        // now test string builder
        read.reset()
        Assert.assertEquals("", read.readStringBuilder().toString())
        Assert.assertEquals("1", read.readStringBuilder().toString())
        Assert.assertEquals("22", read.readStringBuilder().toString())
        Assert.assertEquals("uno", read.readStringBuilder().toString())
        Assert.assertEquals("dos", read.readStringBuilder().toString())
        Assert.assertEquals("tres", read.readStringBuilder().toString())
        Assert.assertNull(read.readStringBuilder())
        Assert.assertEquals(value1, read.readStringBuilder().toString())
        Assert.assertEquals(value2, read.readStringBuilder().toString())
        for (i in 0..126) Assert.assertEquals(i.toChar().toString(), read.readStringBuilder().toString())
        for (i in 0..126) Assert.assertEquals(i.toChar().toString() + "abc", read.readStringBuilder().toString())
    }

    @Test
    @Throws(IOException::class)
    fun testCanReadInt() {
        val write = AeronOutput(32)
        var read = AeronInput(write.toBytes())
        Assert.assertFalse(read.canReadVarInt())
        write.writeVarInt(400, true)
        read = AeronInput(write.toBytes())
        Assert.assertTrue(read.canReadVarInt())
        read.setLimit(read.limit() - 1)
        Assert.assertFalse(read.canReadVarInt())
    }

    @Test
    @Throws(IOException::class)
    fun testVarIntFlagOutput() {
        val output = AeronOutput(4096)
        val input = AeronInput(output.internalBuffer)
        runVarIntFlagsTest(output, input)
    }

    @Test
    @Throws(IOException::class)
    fun testVarIntFlagAeronOutput() {
        val output = AeronOutput(4096)
        val input = AeronInput(output.internalBuffer)
        runVarIntFlagsTest(output, input)
    }

    @Throws(IOException::class)
    private fun runVarIntFlagsTest(output: Output, input: Input) {
        Assert.assertEquals(1, output.writeVarIntFlag(true, 63, true).toLong())
        Assert.assertEquals(2, output.writeVarIntFlag(true, 64, true).toLong())
        Assert.assertEquals(1, output.writeVarIntFlag(false, 63, true).toLong())
        Assert.assertEquals(2, output.writeVarIntFlag(false, 64, true).toLong())
        Assert.assertEquals(1, output.writeVarIntFlag(true, 31, false).toLong())
        Assert.assertEquals(2, output.writeVarIntFlag(true, 32, false).toLong())
        Assert.assertEquals(1, output.writeVarIntFlag(false, 31, false).toLong())
        Assert.assertEquals(2, output.writeVarIntFlag(false, 32, false).toLong())
        input.setPosition(0)
        input.setLimit(output.position())
        Assert.assertTrue(input.readVarIntFlag())
        Assert.assertEquals(63, input.readVarIntFlag(true).toLong())
        Assert.assertTrue(input.readVarIntFlag())
        Assert.assertEquals(64, input.readVarIntFlag(true).toLong())
        Assert.assertFalse(input.readVarIntFlag())
        Assert.assertEquals(63, input.readVarIntFlag(true).toLong())
        Assert.assertFalse(input.readVarIntFlag())
        Assert.assertEquals(64, input.readVarIntFlag(true).toLong())
        Assert.assertTrue(input.readVarIntFlag())
        Assert.assertEquals(31, input.readVarIntFlag(false).toLong())
        Assert.assertTrue(input.readVarIntFlag())
        Assert.assertEquals(32, input.readVarIntFlag(false).toLong())
        Assert.assertFalse(input.readVarIntFlag())
        Assert.assertEquals(31, input.readVarIntFlag(false).toLong())
        Assert.assertFalse(input.readVarIntFlag())
        Assert.assertEquals(32, input.readVarIntFlag(false).toLong())
    }

    @Test
    @Throws(IOException::class)
    fun testInts() {
        runIntTest(AeronOutput(4096))
        runIntTest(AeronOutput(32))
    }

    @Throws(IOException::class)
    private fun runIntTest(write: Output) {
        write.writeInt(0)
        write.writeInt(63)
        write.writeInt(64)
        write.writeInt(127)
        write.writeInt(128)
        write.writeInt(8192)
        write.writeInt(16384)
        write.writeInt(2097151)
        write.writeInt(1048575)
        write.writeInt(134217727)
        write.writeInt(268435455)
        write.writeInt(134217728)
        write.writeInt(268435456)
        write.writeInt(-2097151)
        write.writeInt(-1048575)
        write.writeInt(-134217727)
        write.writeInt(-268435455)
        write.writeInt(-134217728)
        write.writeInt(-268435456)
        Assert.assertEquals(1, write.writeVarInt(0, true).toLong())
        Assert.assertEquals(1, write.writeVarInt(0, false).toLong())
        Assert.assertEquals(1, write.writeVarInt(63, true).toLong())
        Assert.assertEquals(1, write.writeVarInt(63, false).toLong())
        Assert.assertEquals(1, write.writeVarInt(64, true).toLong())
        Assert.assertEquals(2, write.writeVarInt(64, false).toLong())
        Assert.assertEquals(1, write.writeVarInt(127, true).toLong())
        Assert.assertEquals(2, write.writeVarInt(127, false).toLong())
        Assert.assertEquals(2, write.writeVarInt(128, true).toLong())
        Assert.assertEquals(2, write.writeVarInt(128, false).toLong())
        Assert.assertEquals(2, write.writeVarInt(8191, true).toLong())
        Assert.assertEquals(2, write.writeVarInt(8191, false).toLong())
        Assert.assertEquals(2, write.writeVarInt(8192, true).toLong())
        Assert.assertEquals(3, write.writeVarInt(8192, false).toLong())
        Assert.assertEquals(2, write.writeVarInt(16383, true).toLong())
        Assert.assertEquals(3, write.writeVarInt(16383, false).toLong())
        Assert.assertEquals(3, write.writeVarInt(16384, true).toLong())
        Assert.assertEquals(3, write.writeVarInt(16384, false).toLong())
        Assert.assertEquals(3, write.writeVarInt(2097151, true).toLong())
        Assert.assertEquals(4, write.writeVarInt(2097151, false).toLong())
        Assert.assertEquals(3, write.writeVarInt(1048575, true).toLong())
        Assert.assertEquals(3, write.writeVarInt(1048575, false).toLong())
        Assert.assertEquals(4, write.writeVarInt(134217727, true).toLong())
        Assert.assertEquals(4, write.writeVarInt(134217727, false).toLong())
        Assert.assertEquals(4, write.writeVarInt(268435455, true).toLong())
        Assert.assertEquals(5, write.writeVarInt(268435455, false).toLong())
        Assert.assertEquals(4, write.writeVarInt(134217728, true).toLong())
        Assert.assertEquals(5, write.writeVarInt(134217728, false).toLong())
        Assert.assertEquals(5, write.writeVarInt(268435456, true).toLong())
        Assert.assertEquals(5, write.writeVarInt(268435456, false).toLong())
        Assert.assertEquals(1, write.writeVarInt(-64, false).toLong())
        Assert.assertEquals(5, write.writeVarInt(-64, true).toLong())
        Assert.assertEquals(2, write.writeVarInt(-65, false).toLong())
        Assert.assertEquals(5, write.writeVarInt(-65, true).toLong())
        Assert.assertEquals(2, write.writeVarInt(-8192, false).toLong())
        Assert.assertEquals(5, write.writeVarInt(-8192, true).toLong())
        Assert.assertEquals(3, write.writeVarInt(-1048576, false).toLong())
        Assert.assertEquals(5, write.writeVarInt(-1048576, true).toLong())
        Assert.assertEquals(4, write.writeVarInt(-134217728, false).toLong())
        Assert.assertEquals(5, write.writeVarInt(-134217728, true).toLong())
        Assert.assertEquals(5, write.writeVarInt(-134217729, false).toLong())
        Assert.assertEquals(5, write.writeVarInt(-134217729, true).toLong())
        Assert.assertEquals(5, write.writeVarInt(1000000000, false).toLong())
        Assert.assertEquals(5, write.writeVarInt(1000000000, true).toLong())
        Assert.assertEquals(5, write.writeVarInt(Int.MAX_VALUE - 1, false).toLong())
        Assert.assertEquals(5, write.writeVarInt(Int.MAX_VALUE - 1, true).toLong())
        Assert.assertEquals(5, write.writeVarInt(Int.MAX_VALUE, false).toLong())
        Assert.assertEquals(5, write.writeVarInt(Int.MAX_VALUE, true).toLong())
        val read = AeronInput(write.toBytes())
        Assert.assertEquals(0, read.readInt().toLong())
        Assert.assertEquals(63, read.readInt().toLong())
        Assert.assertEquals(64, read.readInt().toLong())
        Assert.assertEquals(127, read.readInt().toLong())
        Assert.assertEquals(128, read.readInt().toLong())
        Assert.assertEquals(8192, read.readInt().toLong())
        Assert.assertEquals(16384, read.readInt().toLong())
        Assert.assertEquals(2097151, read.readInt().toLong())
        Assert.assertEquals(1048575, read.readInt().toLong())
        Assert.assertEquals(134217727, read.readInt().toLong())
        Assert.assertEquals(268435455, read.readInt().toLong())
        Assert.assertEquals(134217728, read.readInt().toLong())
        Assert.assertEquals(268435456, read.readInt().toLong())
        Assert.assertEquals(-2097151, read.readInt().toLong())
        Assert.assertEquals(-1048575, read.readInt().toLong())
        Assert.assertEquals(-134217727, read.readInt().toLong())
        Assert.assertEquals(-268435455, read.readInt().toLong())
        Assert.assertEquals(-134217728, read.readInt().toLong())
        Assert.assertEquals(-268435456, read.readInt().toLong())
        Assert.assertTrue(read.canReadVarInt())
        Assert.assertTrue(read.canReadVarInt())
        Assert.assertTrue(read.canReadVarInt())
        Assert.assertEquals(0, read.readVarInt(true).toLong())
        Assert.assertEquals(0, read.readVarInt(false).toLong())
        Assert.assertEquals(63, read.readVarInt(true).toLong())
        Assert.assertEquals(63, read.readVarInt(false).toLong())
        Assert.assertEquals(64, read.readVarInt(true).toLong())
        Assert.assertEquals(64, read.readVarInt(false).toLong())
        Assert.assertEquals(127, read.readVarInt(true).toLong())
        Assert.assertEquals(127, read.readVarInt(false).toLong())
        Assert.assertEquals(128, read.readVarInt(true).toLong())
        Assert.assertEquals(128, read.readVarInt(false).toLong())
        Assert.assertEquals(8191, read.readVarInt(true).toLong())
        Assert.assertEquals(8191, read.readVarInt(false).toLong())
        Assert.assertEquals(8192, read.readVarInt(true).toLong())
        Assert.assertEquals(8192, read.readVarInt(false).toLong())
        Assert.assertEquals(16383, read.readVarInt(true).toLong())
        Assert.assertEquals(16383, read.readVarInt(false).toLong())
        Assert.assertEquals(16384, read.readVarInt(true).toLong())
        Assert.assertEquals(16384, read.readVarInt(false).toLong())
        Assert.assertEquals(2097151, read.readVarInt(true).toLong())
        Assert.assertEquals(2097151, read.readVarInt(false).toLong())
        Assert.assertEquals(1048575, read.readVarInt(true).toLong())
        Assert.assertEquals(1048575, read.readVarInt(false).toLong())
        Assert.assertEquals(134217727, read.readVarInt(true).toLong())
        Assert.assertEquals(134217727, read.readVarInt(false).toLong())
        Assert.assertEquals(268435455, read.readVarInt(true).toLong())
        Assert.assertEquals(268435455, read.readVarInt(false).toLong())
        Assert.assertEquals(134217728, read.readVarInt(true).toLong())
        Assert.assertEquals(134217728, read.readVarInt(false).toLong())
        Assert.assertEquals(268435456, read.readVarInt(true).toLong())
        Assert.assertEquals(268435456, read.readVarInt(false).toLong())
        Assert.assertEquals(-64, read.readVarInt(false).toLong())
        Assert.assertEquals(-64, read.readVarInt(true).toLong())
        Assert.assertEquals(-65, read.readVarInt(false).toLong())
        Assert.assertEquals(-65, read.readVarInt(true).toLong())
        Assert.assertEquals(-8192, read.readVarInt(false).toLong())
        Assert.assertEquals(-8192, read.readVarInt(true).toLong())
        Assert.assertEquals(-1048576, read.readVarInt(false).toLong())
        Assert.assertEquals(-1048576, read.readVarInt(true).toLong())
        Assert.assertEquals(-134217728, read.readVarInt(false).toLong())
        Assert.assertEquals(-134217728, read.readVarInt(true).toLong())
        Assert.assertEquals(-134217729, read.readVarInt(false).toLong())
        Assert.assertEquals(-134217729, read.readVarInt(true).toLong())
        Assert.assertEquals(1000000000, read.readVarInt(false).toLong())
        Assert.assertEquals(1000000000, read.readVarInt(true).toLong())
        Assert.assertEquals((Int.MAX_VALUE - 1).toLong(), read.readVarInt(false).toLong())
        Assert.assertEquals((Int.MAX_VALUE - 1).toLong(), read.readVarInt(true).toLong())
        Assert.assertEquals(Int.MAX_VALUE.toLong(), read.readVarInt(false).toLong())
        Assert.assertEquals(Int.MAX_VALUE.toLong(), read.readVarInt(true).toLong())
        Assert.assertFalse(read.canReadVarInt())
        val random = Random()
        for (i in 0..9999) {
            val value = random.nextInt()
            write.reset()
            write.writeInt(value)
            write.writeVarInt(value, true)
            write.writeVarInt(value, false)
            read.buffer = write.toBytes()
            Assert.assertEquals(value.toLong(), read.readInt().toLong())
            Assert.assertEquals(value.toLong(), read.readVarInt(true).toLong())
            Assert.assertEquals(value.toLong(), read.readVarInt(false).toLong())
        }
    }

    @Test
    @Throws(IOException::class)
    fun testLongs() {
        runLongTest(AeronOutput(4096))
        runLongTest(AeronOutput(32))
    }

    @Throws(IOException::class)
    private fun runLongTest(write: Output) {
        write.writeLong(0)
        write.writeLong(63)
        write.writeLong(64)
        write.writeLong(127)
        write.writeLong(128)
        write.writeLong(8192)
        write.writeLong(16384)
        write.writeLong(2097151)
        write.writeLong(1048575)
        write.writeLong(134217727)
        write.writeLong(268435455)
        write.writeLong(134217728)
        write.writeLong(268435456)
        write.writeLong(-2097151)
        write.writeLong(-1048575)
        write.writeLong(-134217727)
        write.writeLong(-268435455)
        write.writeLong(-134217728)
        write.writeLong(-268435456)
        Assert.assertEquals(1, write.writeVarLong(0, true).toLong())
        Assert.assertEquals(1, write.writeVarLong(0, false).toLong())
        Assert.assertEquals(1, write.writeVarLong(63, true).toLong())
        Assert.assertEquals(1, write.writeVarLong(63, false).toLong())
        Assert.assertEquals(1, write.writeVarLong(64, true).toLong())
        Assert.assertEquals(2, write.writeVarLong(64, false).toLong())
        Assert.assertEquals(1, write.writeVarLong(127, true).toLong())
        Assert.assertEquals(2, write.writeVarLong(127, false).toLong())
        Assert.assertEquals(2, write.writeVarLong(128, true).toLong())
        Assert.assertEquals(2, write.writeVarLong(128, false).toLong())
        Assert.assertEquals(2, write.writeVarLong(8191, true).toLong())
        Assert.assertEquals(2, write.writeVarLong(8191, false).toLong())
        Assert.assertEquals(2, write.writeVarLong(8192, true).toLong())
        Assert.assertEquals(3, write.writeVarLong(8192, false).toLong())
        Assert.assertEquals(2, write.writeVarLong(16383, true).toLong())
        Assert.assertEquals(3, write.writeVarLong(16383, false).toLong())
        Assert.assertEquals(3, write.writeVarLong(16384, true).toLong())
        Assert.assertEquals(3, write.writeVarLong(16384, false).toLong())
        Assert.assertEquals(3, write.writeVarLong(2097151, true).toLong())
        Assert.assertEquals(4, write.writeVarLong(2097151, false).toLong())
        Assert.assertEquals(3, write.writeVarLong(1048575, true).toLong())
        Assert.assertEquals(3, write.writeVarLong(1048575, false).toLong())
        Assert.assertEquals(4, write.writeVarLong(134217727, true).toLong())
        Assert.assertEquals(4, write.writeVarLong(134217727, false).toLong())
        Assert.assertEquals(4, write.writeVarLong(268435455L, true).toLong())
        Assert.assertEquals(5, write.writeVarLong(268435455L, false).toLong())
        Assert.assertEquals(4, write.writeVarLong(134217728L, true).toLong())
        Assert.assertEquals(5, write.writeVarLong(134217728L, false).toLong())
        Assert.assertEquals(5, write.writeVarLong(268435456L, true).toLong())
        Assert.assertEquals(5, write.writeVarLong(268435456L, false).toLong())
        Assert.assertEquals(1, write.writeVarLong(-64, false).toLong())
        Assert.assertEquals(9, write.writeVarLong(-64, true).toLong())
        Assert.assertEquals(2, write.writeVarLong(-65, false).toLong())
        Assert.assertEquals(9, write.writeVarLong(-65, true).toLong())
        Assert.assertEquals(2, write.writeVarLong(-8192, false).toLong())
        Assert.assertEquals(9, write.writeVarLong(-8192, true).toLong())
        Assert.assertEquals(3, write.writeVarLong(-1048576, false).toLong())
        Assert.assertEquals(9, write.writeVarLong(-1048576, true).toLong())
        Assert.assertEquals(4, write.writeVarLong(-134217728, false).toLong())
        Assert.assertEquals(9, write.writeVarLong(-134217728, true).toLong())
        Assert.assertEquals(5, write.writeVarLong(-134217729, false).toLong())
        Assert.assertEquals(9, write.writeVarLong(-134217729, true).toLong())
        val read = AeronInput(write.toBytes())
        Assert.assertEquals(0, read.readLong())
        Assert.assertEquals(63, read.readLong())
        Assert.assertEquals(64, read.readLong())
        Assert.assertEquals(127, read.readLong())
        Assert.assertEquals(128, read.readLong())
        Assert.assertEquals(8192, read.readLong())
        Assert.assertEquals(16384, read.readLong())
        Assert.assertEquals(2097151, read.readLong())
        Assert.assertEquals(1048575, read.readLong())
        Assert.assertEquals(134217727, read.readLong())
        Assert.assertEquals(268435455, read.readLong())
        Assert.assertEquals(134217728, read.readLong())
        Assert.assertEquals(268435456, read.readLong())
        Assert.assertEquals(-2097151, read.readLong())
        Assert.assertEquals(-1048575, read.readLong())
        Assert.assertEquals(-134217727, read.readLong())
        Assert.assertEquals(-268435455, read.readLong())
        Assert.assertEquals(-134217728, read.readLong())
        Assert.assertEquals(-268435456, read.readLong())
        Assert.assertEquals(0, read.readVarLong(true))
        Assert.assertEquals(0, read.readVarLong(false))
        Assert.assertEquals(63, read.readVarLong(true))
        Assert.assertEquals(63, read.readVarLong(false))
        Assert.assertEquals(64, read.readVarLong(true))
        Assert.assertEquals(64, read.readVarLong(false))
        Assert.assertEquals(127, read.readVarLong(true))
        Assert.assertEquals(127, read.readVarLong(false))
        Assert.assertEquals(128, read.readVarLong(true))
        Assert.assertEquals(128, read.readVarLong(false))
        Assert.assertEquals(8191, read.readVarLong(true))
        Assert.assertEquals(8191, read.readVarLong(false))
        Assert.assertEquals(8192, read.readVarLong(true))
        Assert.assertEquals(8192, read.readVarLong(false))
        Assert.assertEquals(16383, read.readVarLong(true))
        Assert.assertEquals(16383, read.readVarLong(false))
        Assert.assertEquals(16384, read.readVarLong(true))
        Assert.assertEquals(16384, read.readVarLong(false))
        Assert.assertEquals(2097151, read.readVarLong(true))
        Assert.assertEquals(2097151, read.readVarLong(false))
        Assert.assertEquals(1048575, read.readVarLong(true))
        Assert.assertEquals(1048575, read.readVarLong(false))
        Assert.assertEquals(134217727, read.readVarLong(true))
        Assert.assertEquals(134217727, read.readVarLong(false))
        Assert.assertEquals(268435455, read.readVarLong(true))
        Assert.assertEquals(268435455, read.readVarLong(false))
        Assert.assertEquals(134217728, read.readVarLong(true))
        Assert.assertEquals(134217728, read.readVarLong(false))
        Assert.assertEquals(268435456, read.readVarLong(true))
        Assert.assertEquals(268435456, read.readVarLong(false))
        Assert.assertEquals(-64, read.readVarLong(false))
        Assert.assertEquals(-64, read.readVarLong(true))
        Assert.assertEquals(-65, read.readVarLong(false))
        Assert.assertEquals(-65, read.readVarLong(true))
        Assert.assertEquals(-8192, read.readVarLong(false))
        Assert.assertEquals(-8192, read.readVarLong(true))
        Assert.assertEquals(-1048576, read.readVarLong(false))
        Assert.assertEquals(-1048576, read.readVarLong(true))
        Assert.assertEquals(-134217728, read.readVarLong(false))
        Assert.assertEquals(-134217728, read.readVarLong(true))
        Assert.assertEquals(-134217729, read.readVarLong(false))
        Assert.assertEquals(-134217729, read.readVarLong(true))
        val random = Random()
        for (i in 0..9999) {
            val value = random.nextLong()
            write.reset()
            write.writeLong(value)
            write.writeVarLong(value, true)
            write.writeVarLong(value, false)
            read.buffer = write.toBytes()
            Assert.assertEquals(value, read.readLong())
            Assert.assertEquals(value, read.readVarLong(true))
            Assert.assertEquals(value, read.readVarLong(false))
        }
    }

    @Test
    @Throws(IOException::class)
    fun testShorts() {
        runShortTest(AeronOutput(4096))
        runShortTest(AeronOutput(32))
    }

    @Throws(IOException::class)
    private fun runShortTest(write: Output) {
        write.writeShort(0)
        write.writeShort(63)
        write.writeShort(64)
        write.writeShort(127)
        write.writeShort(128)
        write.writeShort(8192)
        write.writeShort(16384)
        write.writeShort(32767)
        write.writeShort(-63)
        write.writeShort(-64)
        write.writeShort(-127)
        write.writeShort(-128)
        write.writeShort(-8192)
        write.writeShort(-16384)
        write.writeShort(-32768)
        val read = AeronInput(write.toBytes())
        Assert.assertEquals(0, read.readShort().toLong())
        Assert.assertEquals(63, read.readShort().toLong())
        Assert.assertEquals(64, read.readShort().toLong())
        Assert.assertEquals(127, read.readShort().toLong())
        Assert.assertEquals(128, read.readShort().toLong())
        Assert.assertEquals(8192, read.readShort().toLong())
        Assert.assertEquals(16384, read.readShort().toLong())
        Assert.assertEquals(32767, read.readShort().toLong())
        Assert.assertEquals(-63, read.readShort().toLong())
        Assert.assertEquals(-64, read.readShort().toLong())
        Assert.assertEquals(-127, read.readShort().toLong())
        Assert.assertEquals(-128, read.readShort().toLong())
        Assert.assertEquals(-8192, read.readShort().toLong())
        Assert.assertEquals(-16384, read.readShort().toLong())
        Assert.assertEquals(-32768, read.readShort().toLong())
    }

    @Test
    @Throws(IOException::class)
    fun testFloats() {
        runFloatTest(AeronOutput(4096))
        runFloatTest(AeronOutput(32))
    }

    @Throws(IOException::class)
    private fun runFloatTest(write: Output) {
        write.writeFloat(0f)
        write.writeFloat(63f)
        write.writeFloat(64f)
        write.writeFloat(127f)
        write.writeFloat(128f)
        write.writeFloat(8192f)
        write.writeFloat(16384f)
        write.writeFloat(32767f)
        write.writeFloat(-63f)
        write.writeFloat(-64f)
        write.writeFloat(-127f)
        write.writeFloat(-128f)
        write.writeFloat(-8192f)
        write.writeFloat(-16384f)
        write.writeFloat(-32768f)
        Assert.assertEquals(1, write.writeVarFloat(0f, 1000f, true).toLong())
        Assert.assertEquals(1, write.writeVarFloat(0f, 1000f, false).toLong())
        Assert.assertEquals(3, write.writeVarFloat(63f, 1000f, true).toLong())
        Assert.assertEquals(3, write.writeVarFloat(63f, 1000f, false).toLong())
        Assert.assertEquals(3, write.writeVarFloat(64f, 1000f, true).toLong())
        Assert.assertEquals(3, write.writeVarFloat(64f, 1000f, false).toLong())
        Assert.assertEquals(3, write.writeVarFloat(127f, 1000f, true).toLong())
        Assert.assertEquals(3, write.writeVarFloat(127f, 1000f, false).toLong())
        Assert.assertEquals(3, write.writeVarFloat(128f, 1000f, true).toLong())
        Assert.assertEquals(3, write.writeVarFloat(128f, 1000f, false).toLong())
        Assert.assertEquals(4, write.writeVarFloat(8191f, 1000f, true).toLong())
        Assert.assertEquals(4, write.writeVarFloat(8191f, 1000f, false).toLong())
        Assert.assertEquals(4, write.writeVarFloat(8192f, 1000f, true).toLong())
        Assert.assertEquals(4, write.writeVarFloat(8192f, 1000f, false).toLong())
        Assert.assertEquals(4, write.writeVarFloat(16383f, 1000f, true).toLong())
        Assert.assertEquals(4, write.writeVarFloat(16383f, 1000f, false).toLong())
        Assert.assertEquals(4, write.writeVarFloat(16384f, 1000f, true).toLong())
        Assert.assertEquals(4, write.writeVarFloat(16384f, 1000f, false).toLong())
        Assert.assertEquals(4, write.writeVarFloat(32767f, 1000f, true).toLong())
        Assert.assertEquals(4, write.writeVarFloat(32767f, 1000f, false).toLong())
        Assert.assertEquals(3, write.writeVarFloat(-64f, 1000f, false).toLong())
        Assert.assertEquals(5, write.writeVarFloat(-64f, 1000f, true).toLong())
        Assert.assertEquals(3, write.writeVarFloat(-65f, 1000f, false).toLong())
        Assert.assertEquals(5, write.writeVarFloat(-65f, 1000f, true).toLong())
        Assert.assertEquals(4, write.writeVarFloat(-8192f, 1000f, false).toLong())
        Assert.assertEquals(5, write.writeVarFloat(-8192f, 1000f, true).toLong())
        val read = AeronInput(write.toBytes())
        KryoAssert.assertFloatEquals(read.readFloat(), 0.0)
        KryoAssert.assertFloatEquals(read.readFloat(), 63.0)
        KryoAssert.assertFloatEquals(read.readFloat(), 64.0)
        KryoAssert.assertFloatEquals(read.readFloat(), 127.0)
        KryoAssert.assertFloatEquals(read.readFloat(), 128.0)
        KryoAssert.assertFloatEquals(read.readFloat(), 8192.0)
        KryoAssert.assertFloatEquals(read.readFloat(), 16384.0)
        KryoAssert.assertFloatEquals(read.readFloat(), 32767.0)
        KryoAssert.assertFloatEquals(read.readFloat(), -63.0)
        KryoAssert.assertFloatEquals(read.readFloat(), -64.0)
        KryoAssert.assertFloatEquals(read.readFloat(), -127.0)
        KryoAssert.assertFloatEquals(read.readFloat(), -128.0)
        KryoAssert.assertFloatEquals(read.readFloat(), -8192.0)
        KryoAssert.assertFloatEquals(read.readFloat(), -16384.0)
        KryoAssert.assertFloatEquals(read.readFloat(), -32768.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, true), 0.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, false), 0.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, true), 63.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, false), 63.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, true), 64.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, false), 64.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, true), 127.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, false), 127.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, true), 128.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, false), 128.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, true), 8191.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, false), 8191.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, true), 8192.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, false), 8192.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, true), 16383.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, false), 16383.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, true), 16384.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, false), 16384.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, true), 32767.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, false), 32767.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, false), -64.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, true), -64.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, false), -65.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, true), -65.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, false), -8192.0)
        KryoAssert.assertFloatEquals(read.readVarFloat(1000f, true), -8192.0)
    }

    @Test
    @Throws(IOException::class)
    fun testDoubles() {
        runDoubleTest(AeronOutput(4096))
        runDoubleTest(AeronOutput(32))
    }

    @Throws(IOException::class)
    private fun runDoubleTest(write: Output) {
        write.writeDouble(0.0)
        write.writeDouble(63.0)
        write.writeDouble(64.0)
        write.writeDouble(127.0)
        write.writeDouble(128.0)
        write.writeDouble(8192.0)
        write.writeDouble(16384.0)
        write.writeDouble(32767.0)
        write.writeDouble(-63.0)
        write.writeDouble(-64.0)
        write.writeDouble(-127.0)
        write.writeDouble(-128.0)
        write.writeDouble(-8192.0)
        write.writeDouble(-16384.0)
        write.writeDouble(-32768.0)
        Assert.assertEquals(1, write.writeVarDouble(0.0, 1000.0, true).toLong())
        Assert.assertEquals(1, write.writeVarDouble(0.0, 1000.0, false).toLong())
        Assert.assertEquals(3, write.writeVarDouble(63.0, 1000.0, true).toLong())
        Assert.assertEquals(3, write.writeVarDouble(63.0, 1000.0, false).toLong())
        Assert.assertEquals(3, write.writeVarDouble(64.0, 1000.0, true).toLong())
        Assert.assertEquals(3, write.writeVarDouble(64.0, 1000.0, false).toLong())
        Assert.assertEquals(3, write.writeVarDouble(127.0, 1000.0, true).toLong())
        Assert.assertEquals(3, write.writeVarDouble(127.0, 1000.0, false).toLong())
        Assert.assertEquals(3, write.writeVarDouble(128.0, 1000.0, true).toLong())
        Assert.assertEquals(3, write.writeVarDouble(128.0, 1000.0, false).toLong())
        Assert.assertEquals(4, write.writeVarDouble(8191.0, 1000.0, true).toLong())
        Assert.assertEquals(4, write.writeVarDouble(8191.0, 1000.0, false).toLong())
        Assert.assertEquals(4, write.writeVarDouble(8192.0, 1000.0, true).toLong())
        Assert.assertEquals(4, write.writeVarDouble(8192.0, 1000.0, false).toLong())
        Assert.assertEquals(4, write.writeVarDouble(16383.0, 1000.0, true).toLong())
        Assert.assertEquals(4, write.writeVarDouble(16383.0, 1000.0, false).toLong())
        Assert.assertEquals(4, write.writeVarDouble(16384.0, 1000.0, true).toLong())
        Assert.assertEquals(4, write.writeVarDouble(16384.0, 1000.0, false).toLong())
        Assert.assertEquals(4, write.writeVarDouble(32767.0, 1000.0, true).toLong())
        Assert.assertEquals(4, write.writeVarDouble(32767.0, 1000.0, false).toLong())
        Assert.assertEquals(3, write.writeVarDouble(-64.0, 1000.0, false).toLong())
        Assert.assertEquals(9, write.writeVarDouble(-64.0, 1000.0, true).toLong())
        Assert.assertEquals(3, write.writeVarDouble(-65.0, 1000.0, false).toLong())
        Assert.assertEquals(9, write.writeVarDouble(-65.0, 1000.0, true).toLong())
        Assert.assertEquals(4, write.writeVarDouble(-8192.0, 1000.0, false).toLong())
        Assert.assertEquals(9, write.writeVarDouble(-8192.0, 1000.0, true).toLong())
        write.writeDouble(1.23456)
        val read = AeronInput(write.toBytes())
        KryoAssert.assertDoubleEquals(read.readDouble(), 0.0)
        KryoAssert.assertDoubleEquals(read.readDouble(), 63.0)
        KryoAssert.assertDoubleEquals(read.readDouble(), 64.0)
        KryoAssert.assertDoubleEquals(read.readDouble(), 127.0)
        KryoAssert.assertDoubleEquals(read.readDouble(), 128.0)
        KryoAssert.assertDoubleEquals(read.readDouble(), 8192.0)
        KryoAssert.assertDoubleEquals(read.readDouble(), 16384.0)
        KryoAssert.assertDoubleEquals(read.readDouble(), 32767.0)
        KryoAssert.assertDoubleEquals(read.readDouble(), -63.0)
        KryoAssert.assertDoubleEquals(read.readDouble(), -64.0)
        KryoAssert.assertDoubleEquals(read.readDouble(), -127.0)
        KryoAssert.assertDoubleEquals(read.readDouble(), -128.0)
        KryoAssert.assertDoubleEquals(read.readDouble(), -8192.0)
        KryoAssert.assertDoubleEquals(read.readDouble(), -16384.0)
        KryoAssert.assertDoubleEquals(read.readDouble(), -32768.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, true), 0.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, false), 0.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, true), 63.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, false), 63.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, true), 64.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, false), 64.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, true), 127.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, false), 127.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, true), 128.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, false), 128.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, true), 8191.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, false), 8191.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, true), 8192.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, false), 8192.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, true), 16383.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, false), 16383.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, true), 16384.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, false), 16384.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, true), 32767.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, false), 32767.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, false), -64.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, true), -64.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, false), -65.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, true), -65.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, false), -8192.0)
        KryoAssert.assertDoubleEquals(read.readVarDouble(1000.0, true), -8192.0)
        KryoAssert.assertDoubleEquals(1.23456, read.readDouble())
    }

    @Test
    @Throws(IOException::class)
    fun testBooleans() {
        runBooleanTest(AeronOutput(4096))
        runBooleanTest(AeronOutput(32))
    }

    @Throws(IOException::class)
    private fun runBooleanTest(write: Output) {
        for (i in 0..99) {
            write.writeBoolean(true)
            write.writeBoolean(false)
        }
        val read = AeronInput(write.toBytes())
        for (i in 0..99) {
            Assert.assertTrue(read.readBoolean())
            Assert.assertFalse(read.readBoolean())
        }
    }

    @Test
    @Throws(IOException::class)
    fun testChars() {
        runCharTest(AeronOutput(4096))
        runCharTest(AeronOutput(32))
    }

    @Throws(IOException::class)
    private fun runCharTest(write: Output) {
        write.writeChar(0.toChar())
        write.writeChar(63.toChar())
        write.writeChar(64.toChar())
        write.writeChar(127.toChar())
        write.writeChar(128.toChar())
        write.writeChar(8192.toChar())
        write.writeChar(16384.toChar())
        write.writeChar(32767.toChar())
        write.writeChar(65535.toChar())
        val read = AeronInput(write.toBytes())
        Assert.assertEquals(0, read.readChar().code)
        Assert.assertEquals(63, read.readChar().code)
        Assert.assertEquals(64, read.readChar().code)
        Assert.assertEquals(127, read.readChar().code)
        Assert.assertEquals(128, read.readChar().code)
        Assert.assertEquals(8192, read.readChar().code)
        Assert.assertEquals(16384, read.readChar().code)
        Assert.assertEquals(32767, read.readChar().code)
        Assert.assertEquals(65535, read.readChar().code)
    }

    @Test
    @Throws(Exception::class)
    fun testInputWithOffset() {
        val buf = ByteArray(30)
        val `in` = AeronInput(buf, 10, 10)
        Assert.assertEquals(10, `in`.available().toLong())
    }

    @Test
    @Throws(Exception::class)
    fun testSmallBuffers() {
        val testOutput = AeronOutput(32)
        testOutput.writeBytes(ByteArray(512))
        testOutput.writeBytes(ByteArray(512))
        testOutput.flush()
        val input = AeronInput(testOutput.internalBuffer)
        val toRead = ByteArray(512)
        input.readBytes(toRead)
        input.readBytes(toRead)
    }

    @Test
    @Throws(Exception::class)
    fun testVerySmallBuffers() {
        val out1 = Output(4, -1)
        val out2: Output = AeronOutput(4)
        for (i in 0..15) {
            out1.writeVarInt(92, false)
        }
        for (i in 0..15) {
            out2.writeVarInt(92, false)
        }
        Assert.assertArrayEquals(out1.toBytes(), out2.toBytes())
    }

    @Test
    @Throws(Exception::class)
    fun testZeroLengthOutputs() {
        val output = Output(0, 10000)
        kryo!!.writeClassAndObject(output, "Test string")
        val aeronOutput = AeronOutput(0)
        kryo!!.writeClassAndObject(aeronOutput, "Test string")
    }

    @Test
    @Throws(Exception::class)
    fun testFlushRoundTrip() {
        val kryo = Kryo()
        val s1 = "12345"
        val output = AeronOutput(32)
        kryo.writeClass(output, s1.javaClass)
        kryo.writeObject(output, s1)
        val input = AeronInput(output.internalBuffer)
        val r = kryo.readClass(input)
        val s2 = kryo.readObject(input, r.type) as String
        Assert.assertEquals(s1, s2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testSetOutputBufferMinusOne() {
        val bufferSize = 2
        val output = AeronOutput(-1)
        Assert.assertNotNull(output)
        output.buffer = ByteArray(bufferSize)
    }

    @Test
    fun testNewOutputMaxBufferSizeIsMinusOne() {
        val bufferSize = 2
        val maxBufferSize = -1
        val output = Output(bufferSize, maxBufferSize)
        Assert.assertNotNull(output)
        // This test should pass as long as no exception thrown
    }

    @Test
    fun testSetOutputMaxBufferSizeIsMinusOne() {
        val bufferSize = 2
        val maxBufferSize = -1
        val output = AeronOutput(bufferSize)
        Assert.assertNotNull(output)
        output.setBuffer(ByteArray(bufferSize), maxBufferSize)
        // This test should pass as long as no exception thrown
    }
}
