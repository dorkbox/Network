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
 *
 * Copyright (c) 2008, Nathan Sweet
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
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package dorkbox.network.serialization

import com.esotericsoftware.kryo.KryoException
import com.esotericsoftware.kryo.io.Output
import org.agrona.ExpandableDirectByteBuffer
import org.agrona.MutableDirectBuffer
import org.agrona.concurrent.UnsafeBuffer
import java.io.OutputStream

/**
 * An [OutputStream] which writes data to a [MutableDirectBuffer].
 *
 *
 * A write operation against this stream will occur at the `writerIndex`
 * of its underlying buffer and the `writerIndex` will increase during
 * the write operation.
 *
 *
 * This stream implements [DataOutput] for your convenience.
 * The endianness of the stream is not always big endian but depends on
 * the endianness of the underlying buffer.
 *
 *
 *
 * Utility methods are provided for efficiently reading primitive types and strings.
 *
 * Modified from KRYO to use ByteBuf.
 */
class AeronOutput : Output {
    /** Returns the buffer. The bytes between zero and [.position] are the data that has been written.  */
    // NOTE: capacity IS NOT USED!
    var internalBuffer: MutableDirectBuffer
        private set
    /**
     * Creates a new Output for writing to a direct [MutableDirectBuffer].
     *
     * @param bufferSize The size of the buffer.
     */
    /** Creates a new Output for writing to a direct [MutableDirectBuffer].  */
    @JvmOverloads
    constructor(bufferSize: Int = 32) {
        require(bufferSize >= 0) { "bufferSize must be >= 0!" }
        // Minimum buffer size allowed is size = 2 (because it grows by 1.5x the current size)
        internalBuffer = ExpandableDirectByteBuffer(Math.max(2, bufferSize))
    }

    /**
     * Creates a new Output for writing to a byte[].
     * @see .setBuffer
     */
    constructor(buffer: ByteArray?) {
        internalBuffer = UnsafeBuffer(buffer)
        position = 0
        capacity = internalBuffer.capacity()
    }

    /**
     * Creates a new Output for writing to a DirectBuffer.
     */
    constructor(buffer: MutableDirectBuffer) {
        internalBuffer = buffer
        position = 0
        capacity = internalBuffer.capacity()
    }

    override fun getOutputStream(): OutputStream {
        throw UnsupportedOperationException("This input does not use an OutputStream.")
    }

    /**
     * Throws [UnsupportedOperationException] because this output uses a ByteBuffer, not a byte[].
     * @see .getInternalBuffer
     */
    @Deprecated(" ")
    override fun getBuffer(): ByteArray {
        throw UnsupportedOperationException("This buffer does not used a byte[], see #getInternaleBuffer().")
    }

    /**
     * Sets a new buffer to write to. The max size is the buffer's length.
     */
    fun setBuffer(buffer: MutableDirectBuffer) {
        internalBuffer = buffer
        position = 0
        capacity = buffer.capacity()
    }

    /**
     * Sets a new buffer to write to. The max size is the buffer's length.
     */
    override fun setBuffer(buffer: ByteArray) {
        internalBuffer = UnsafeBuffer(buffer)
        position = 0
        capacity = internalBuffer.capacity()
    }

    /**
     * Sets a new buffer to write to. The max size is the buffer's length.
     */
    @Deprecated("")
    override fun setBuffer(buffer: ByteArray, maxBufferSize: Int) {
        setBuffer(buffer)
    }

    override fun toBytes(): ByteArray {
        val newBuffer = ByteArray(position)
        internalBuffer.getBytes(0, newBuffer, 0, position)
        return newBuffer
    }

    override fun setPosition(position: Int) {
        this.position = position
    }

    override fun reset() {
        super.reset()
    }

    /**
     * Ensures the buffer is large enough to read the specified number of bytes.
     * @return true if the buffer has been resized.
     */
    @Throws(KryoException::class)
    override fun require(required: Int): Boolean {
        return false
    }

    // OutputStream:
    @Throws(KryoException::class)
    override fun flush() {
    }

    @Throws(KryoException::class)
    override fun close() {
        flush()
    }

    @Throws(KryoException::class)
    override fun write(value: Int) {
        internalBuffer.putByte(position++, value.toByte())
    }

    @Throws(KryoException::class)
    override fun write(bytes: ByteArray) {
        writeBytes(bytes, 0, bytes.size)
    }

    @Throws(KryoException::class)
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        writeBytes(bytes, offset, length)
    }

    // byte:
    @Throws(KryoException::class)
    override fun writeByte(value: Byte) {
        internalBuffer.putByte(position++, value)
    }

    @Throws(KryoException::class)
    override fun writeByte(value: Int) {
        internalBuffer.putByte(position++, value.toByte())
    }

    @Throws(KryoException::class)
    override fun writeBytes(bytes: ByteArray) {
        writeBytes(bytes, 0, bytes.size)
    }

    @Throws(KryoException::class)
    override fun writeBytes(bytes: ByteArray, offset: Int, length: Int) {
        internalBuffer.putBytes(position, bytes, offset, length)
        position += length
    }

    // int:
    @Throws(KryoException::class)
    override fun writeInt(value: Int) {
        internalBuffer.putInt(position, value)
        position += 4
    }

    @Throws(KryoException::class)
    override fun writeVarInt(value: Int, optimizePositive: Boolean): Int {
        var value = value
        if (!optimizePositive) value = value shl 1 xor (value shr 31)
        if (value ushr 7 == 0) {
            internalBuffer.putByte(position++, value.toByte())
            return 1
        }
        if (value ushr 14 == 0) {
            internalBuffer.putByte(position++, (value and 0x7F or 0x80).toByte())
            internalBuffer.putByte(position++, (value ushr 7).toByte())
            return 2
        }
        if (value ushr 21 == 0) {
            val byteBuf = internalBuffer
            byteBuf.putByte(position++, (value and 0x7F or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 7 or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 14).toByte())
            return 3
        }
        if (value ushr 28 == 0) {
            val byteBuf = internalBuffer
            byteBuf.putByte(position++, (value and 0x7F or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 7 or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 14 or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 21).toByte())
            return 4
        }
        val byteBuf = internalBuffer
        byteBuf.putByte(position++, (value and 0x7F or 0x80).toByte())
        byteBuf.putByte(position++, (value ushr 7 or 0x80).toByte())
        byteBuf.putByte(position++, (value ushr 14 or 0x80).toByte())
        byteBuf.putByte(position++, (value ushr 21 or 0x80).toByte())
        byteBuf.putByte(position++, (value ushr 28).toByte())
        return 5
    }

    @Throws(KryoException::class)
    override fun writeVarIntFlag(flag: Boolean, value: Int, optimizePositive: Boolean): Int {
        var value = value
        if (!optimizePositive) value = value shl 1 xor (value shr 31)
        val first = value and 0x3F or if (flag) 0x80 else 0 // Mask first 6 bits, bit 8 is the flag.
        if (value ushr 6 == 0) {
            internalBuffer.putByte(position++, first.toByte())
            return 1
        }
        if (value ushr 13 == 0) {
            internalBuffer.putByte(position++, (first or 0x40).toByte()) // Set bit 7.
            internalBuffer.putByte(position++, (value ushr 6).toByte())
            return 2
        }
        if (value ushr 20 == 0) {
            val byteBuf = internalBuffer
            byteBuf.putByte(position++, (first or 0x40).toByte()) // Set bit 7.
            byteBuf.putByte(position++, (value ushr 6 or 0x80).toByte()) // Set bit 8.
            byteBuf.putByte(position++, (value ushr 13).toByte())
            return 3
        }
        if (value ushr 27 == 0) {
            val byteBuf = internalBuffer
            byteBuf.putByte(position++, (first or 0x40).toByte()) // Set bit 7.
            byteBuf.putByte(position++, (value ushr 6 or 0x80).toByte()) // Set bit 8.
            byteBuf.putByte(position++, (value ushr 13 or 0x80).toByte()) // Set bit 8.
            byteBuf.putByte(position++, (value ushr 20).toByte())
            return 4
        }
        val byteBuf = internalBuffer
        byteBuf.putByte(position++, (first or 0x40).toByte()) // Set bit 7.
        byteBuf.putByte(position++, (value ushr 6 or 0x80).toByte()) // Set bit 8.
        byteBuf.putByte(position++, (value ushr 13 or 0x80).toByte()) // Set bit 8.
        byteBuf.putByte(position++, (value ushr 20 or 0x80).toByte()) // Set bit 8.
        byteBuf.putByte(position++, (value ushr 27).toByte())
        return 5
    }

    // long:
    @Throws(KryoException::class)
    override fun writeLong(value: Long) {
        internalBuffer.putLong(position, value)
        position += 8
    }

    @Throws(KryoException::class)
    override fun writeVarLong(value: Long, optimizePositive: Boolean): Int {
        var value = value
        if (!optimizePositive) value = value shl 1 xor (value shr 63)
        if (value ushr 7 == 0L) {
            internalBuffer.putByte(position++, value.toByte())
            return 1
        }
        if (value ushr 14 == 0L) {
            internalBuffer.putByte(position++, (value and 0x7F or 0x80).toByte())
            internalBuffer.putByte(position++, (value ushr 7).toByte())
            return 2
        }
        if (value ushr 21 == 0L) {
            val byteBuf = internalBuffer
            byteBuf.putByte(position++, (value and 0x7F or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 7 or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 14).toByte())
            return 3
        }
        if (value ushr 28 == 0L) {
            val byteBuf = internalBuffer
            byteBuf.putByte(position++, (value and 0x7F or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 7 or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 14 or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 21).toByte())
            return 4
        }
        if (value ushr 35 == 0L) {
            val byteBuf = internalBuffer
            byteBuf.putByte(position++, (value and 0x7F or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 7 or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 14 or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 21 or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 28).toByte())
            return 5
        }
        if (value ushr 42 == 0L) {
            val byteBuf = internalBuffer
            byteBuf.putByte(position++, (value and 0x7F or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 7 or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 14 or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 21 or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 28 or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 35).toByte())
            return 6
        }
        if (value ushr 49 == 0L) {
            val byteBuf = internalBuffer
            byteBuf.putByte(position++, (value and 0x7F or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 7 or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 14 or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 21 or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 28 or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 35 or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 42).toByte())
            return 7
        }
        if (value ushr 56 == 0L) {
            val byteBuf = internalBuffer
            byteBuf.putByte(position++, (value and 0x7F or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 7 or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 14 or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 21 or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 28 or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 35 or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 42 or 0x80).toByte())
            byteBuf.putByte(position++, (value ushr 49).toByte())
            return 8
        }
        val byteBuf = internalBuffer
        byteBuf.putByte(position++, (value and 0x7F or 0x80).toByte())
        byteBuf.putByte(position++, (value ushr 7 or 0x80).toByte())
        byteBuf.putByte(position++, (value ushr 14 or 0x80).toByte())
        byteBuf.putByte(position++, (value ushr 21 or 0x80).toByte())
        byteBuf.putByte(position++, (value ushr 28 or 0x80).toByte())
        byteBuf.putByte(position++, (value ushr 35 or 0x80).toByte())
        byteBuf.putByte(position++, (value ushr 42 or 0x80).toByte())
        byteBuf.putByte(position++, (value ushr 49 or 0x80).toByte())
        byteBuf.putByte(position++, (value ushr 56).toByte())
        return 9
    }

    // float:
    @Throws(KryoException::class)
    override fun writeFloat(value: Float) {
        internalBuffer.putFloat(position, value)
        position += 4
    }

    // double:
    @Throws(KryoException::class)
    override fun writeDouble(value: Double) {
        internalBuffer.putDouble(position, value)
        position += 8
    }

    // short:
    @Throws(KryoException::class)
    override fun writeShort(value: Int) {
        internalBuffer.putShort(position, value.toShort())
        position += 2
    }

    // char:
    @Throws(KryoException::class)
    override fun writeChar(value: Char) {
        internalBuffer.putChar(position, value)
        position += 2
    }

    // boolean:
    @Throws(KryoException::class)
    override fun writeBoolean(value: Boolean) {
        internalBuffer.putByte(position++, (if (value) 1 else 0).toByte())
    }

    // String:
    @Throws(KryoException::class)
    override fun writeString(value: String?) {
        if (value == null) {
            writeByte(0x80) // 0 means null, bit 8 means UTF8.
            return
        }
        val charCount = value.length
        if (charCount == 0) {
            writeByte(1 or 0x80) // 1 means empty string, bit 8 means UTF8.
            return
        }

        // Detect ASCII, we only do this for small strings, but ONLY more than 1 char.
        // since 1 char is used for bit-masking if we use for 1 char string, reading the string will not work!
        var permitAscii = charCount > 1 && charCount <= 32
        if (permitAscii) {
            for (i in 0 until charCount) {
                if (value[i].toInt() > 127) {
                    permitAscii = false
                    break  // not ascii
                }
            }

            if (permitAscii) {
                // this is ascii
                internalBuffer.putStringWithoutLengthAscii(position, value)
                position += charCount

                // mod the last written byte with 0x80 so we can use that when reading ascii bytes to see what the end of the string is
                val b = (internalBuffer.getByte(position - 1).toInt() or 0x80).toByte()
                internalBuffer.putByte(position - 1, b)
                return
            }
        }

        // UTF8 (or ASCII with length 1 or length > 32
        writeVarIntFlag(true, charCount + 1, true)
        var charIndex = 0
        // Try to write 7 bit chars.
        val byteBuf = internalBuffer
        while (true) {
            val c = value[charIndex].toInt()
            if (c > 127) break
            byteBuf.putByte(position++, c.toByte())
            charIndex++
            if (charIndex == charCount) {
                return
            }
        }
        if (charIndex < charCount) writeUtf8_slow(value, charCount, charIndex)
    }

    @Throws(KryoException::class)
    override fun writeAscii(value: String?) {
        if (value == null) {
            writeByte(0x80) // 0 means null, bit 8 means UTF8.
            return
        }
        val charCount = value.length
        if (charCount == 0) {
            writeByte(1 or 0x80) // 1 means empty string, bit 8 means UTF8.
            return
        }
        require(charCount) // must be able to write this number of chars
        val byteBuf = internalBuffer
        var i = 0
        val n = value.length
        while (i < n) {
            byteBuf.putByte(position++, value[i].toByte())
            ++i
        }
        byteBuf.putByte(position - 1, (byteBuf.getByte(position - 1).toInt() or 0x80) as Byte) // Bit 8 means end of ASCII.
    }

    private fun writeUtf8_slow(value: String, charCount: Int, charIndex: Int) {
        var charIndex = charIndex
        while (charIndex < charCount) {
            val c = value[charIndex].toInt()
            if (c <= 0x007F) {
                internalBuffer.putByte(position++, c.toByte())
            } else if (c > 0x07FF) {
                internalBuffer.putByte(position++, (0xE0 or (c shr 12 and 0x0F)).toByte())
                internalBuffer.putByte(position++, (0x80 or (c shr 6 and 0x3F)).toByte())
                internalBuffer.putByte(position++, (0x80 or (c and 0x3F)).toByte())
            } else {
                internalBuffer.putByte(position++, (0xC0 or (c shr 6 and 0x1F)).toByte())
                internalBuffer.putByte(position++, (0x80 or (c and 0x3F)).toByte())
            }
            charIndex++
        }
    }

    // Primitive arrays:
    @Throws(KryoException::class)
    override fun writeInts(array: IntArray, offset: Int, count: Int) {
        var offset = offset
        val n = offset + count
        while (offset < n) {
            val value = array[offset]
            writeInt(value)
            offset++
        }
    }

    @Throws(KryoException::class)
    override fun writeLongs(array: LongArray, offset: Int, count: Int) {
        var offset = offset
        val n = offset + count
        while (offset < n) {
            val value = array[offset]
            writeLong(value)
            offset++
        }
    }

    @Throws(KryoException::class)
    override fun writeFloats(array: FloatArray, offset: Int, count: Int) {
        var offset = offset
        val n = offset + count
        while (offset < n) {
            val value = java.lang.Float.floatToIntBits(array[offset])
            writeFloat(value.toFloat())
            offset++
        }
    }

    @Throws(KryoException::class)
    override fun writeDoubles(array: DoubleArray, offset: Int, count: Int) {
        var offset = offset
        val n = offset + count
        while (offset < n) {
            val value = java.lang.Double.doubleToLongBits(array[offset])
            writeDouble(value.toDouble())
            offset++
        }
    }

    @Throws(KryoException::class)
    override fun writeShorts(array: ShortArray, offset: Int, count: Int) {
        var offset = offset
        val n = offset + count
        while (offset < n) {
            val value = array[offset].toInt()
            writeShort(value)
            offset++
        }
    }

    @Throws(KryoException::class)
    override fun writeChars(array: CharArray, offset: Int, count: Int) {
        var offset = offset
        val n = offset + count
        while (offset < n) {
            val value = array[offset].toInt()
            writeChar(value.toChar())
            offset++
        }
    }

    @Throws(KryoException::class)
    override fun writeBooleans(array: BooleanArray, offset: Int, count: Int) {
        var offset = offset
        val n = offset + count
        while (offset < n) {
            internalBuffer.putByte(position++, if (array[offset]) 1.toByte() else 0)
            offset++
        }
    }
}
