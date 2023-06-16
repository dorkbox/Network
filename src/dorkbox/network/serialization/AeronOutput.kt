/*
 * Copyright 2023 dorkbox, llc
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
package dorkbox.network.serialization

import com.esotericsoftware.kryo.KryoException
import com.esotericsoftware.kryo.io.Output
import com.esotericsoftware.kryo.util.Util
import org.agrona.ExpandableDirectByteBuffer
import org.agrona.MutableDirectBuffer
import org.agrona.concurrent.UnsafeBuffer
import java.io.OutputStream

/**
 * An [OutputStream] which writes data to a [MutableDirectBuffer].
 *
 * A write operation against this stream will occur at the `writerIndex`
 * of its underlying buffer and the `writerIndex` will increase during
 * the write operation.
 *
 * The endianness of the stream is not always big endian but depends on
 * the endianness of the underlying buffer.
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
    @JvmOverloads
    constructor(bufferSize: Int = 32) {
        require(bufferSize >= 0) { "bufferSize must be >= 0!" }
        // Minimum buffer size allowed is size = 2 (because it grows by 1.5x the current size)
        internalBuffer = ExpandableDirectByteBuffer(2.coerceAtLeast(bufferSize))
    }

    /**
     * Creates a new Output for writing to a byte[].
     *
     * @see [setBuffer]
     */
    constructor(buffer: ByteArray) {
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
     *
     * @see [internalBuffer]
     */
    @Deprecated("This buffer does not used a byte[]")
    override fun getBuffer(): ByteArray {
        throw UnsupportedOperationException("This buffer does not use a byte[], see #getInternaleBuffer().")
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
    override fun setBuffer(buffer: ByteArray, maxBufferSize: Int) {
        setBuffer(buffer)
        maxCapacity = if (maxBufferSize == -1) Util.maxArraySize else maxBufferSize
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
        var newValue = value
        if (!optimizePositive) newValue = newValue shl 1 xor (newValue shr 31)
        if (newValue ushr 7 == 0) {
            internalBuffer.putByte(position++, newValue.toByte())
            return 1
        }
        if (newValue ushr 14 == 0) {
            internalBuffer.putByte(position++, (newValue and 0x7F or 0x80).toByte())
            internalBuffer.putByte(position++, (newValue ushr 7).toByte())
            return 2
        }
        if (newValue ushr 21 == 0) {
            val byteBuf = internalBuffer
            byteBuf.putByte(position++, (newValue and 0x7F or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 7 or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 14).toByte())
            return 3
        }
        if (newValue ushr 28 == 0) {
            val byteBuf = internalBuffer
            byteBuf.putByte(position++, (newValue and 0x7F or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 7 or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 14 or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 21).toByte())
            return 4
        }
        val byteBuf = internalBuffer
        byteBuf.putByte(position++, (newValue and 0x7F or 0x80).toByte())
        byteBuf.putByte(position++, (newValue ushr 7 or 0x80).toByte())
        byteBuf.putByte(position++, (newValue ushr 14 or 0x80).toByte())
        byteBuf.putByte(position++, (newValue ushr 21 or 0x80).toByte())
        byteBuf.putByte(position++, (newValue ushr 28).toByte())
        return 5
    }

    @Throws(KryoException::class)
    override fun writeVarIntFlag(flag: Boolean, value: Int, optimizePositive: Boolean): Int {
        var newValue = value

        if (!optimizePositive) newValue = newValue shl 1 xor (newValue shr 31)

        val first = newValue and 0x3F or if (flag) 0x80 else 0 // Mask first 6 bits, bit 8 is the flag.
        if (newValue ushr 6 == 0) {
            internalBuffer.putByte(position++, first.toByte())
            return 1
        }
        if (newValue ushr 13 == 0) {
            internalBuffer.putByte(position++, (first or 0x40).toByte()) // Set bit 7.
            internalBuffer.putByte(position++, (newValue ushr 6).toByte())
            return 2
        }
        if (newValue ushr 20 == 0) {
            val byteBuf = internalBuffer
            byteBuf.putByte(position++, (first or 0x40).toByte()) // Set bit 7.
            byteBuf.putByte(position++, (newValue ushr 6 or 0x80).toByte()) // Set bit 8.
            byteBuf.putByte(position++, (newValue ushr 13).toByte())
            return 3
        }
        if (newValue ushr 27 == 0) {
            val byteBuf = internalBuffer
            byteBuf.putByte(position++, (first or 0x40).toByte()) // Set bit 7.
            byteBuf.putByte(position++, (newValue ushr 6 or 0x80).toByte()) // Set bit 8.
            byteBuf.putByte(position++, (newValue ushr 13 or 0x80).toByte()) // Set bit 8.
            byteBuf.putByte(position++, (newValue ushr 20).toByte())
            return 4
        }
        val byteBuf = internalBuffer
        byteBuf.putByte(position++, (first or 0x40).toByte()) // Set bit 7.
        byteBuf.putByte(position++, (newValue ushr 6 or 0x80).toByte()) // Set bit 8.
        byteBuf.putByte(position++, (newValue ushr 13 or 0x80).toByte()) // Set bit 8.
        byteBuf.putByte(position++, (newValue ushr 20 or 0x80).toByte()) // Set bit 8.
        byteBuf.putByte(position++, (newValue ushr 27).toByte())
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
        var newValue = value

        if (!optimizePositive) newValue = newValue shl 1 xor (newValue shr 63)

        if (newValue ushr 7 == 0L) {
            internalBuffer.putByte(position++, newValue.toByte())
            return 1
        }
        if (newValue ushr 14 == 0L) {
            internalBuffer.putByte(position++, (newValue and 0x7F or 0x80).toByte())
            internalBuffer.putByte(position++, (newValue ushr 7).toByte())
            return 2
        }
        if (newValue ushr 21 == 0L) {
            val byteBuf = internalBuffer
            byteBuf.putByte(position++, (newValue and 0x7F or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 7 or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 14).toByte())
            return 3
        }
        if (newValue ushr 28 == 0L) {
            val byteBuf = internalBuffer
            byteBuf.putByte(position++, (newValue and 0x7F or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 7 or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 14 or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 21).toByte())
            return 4
        }
        if (newValue ushr 35 == 0L) {
            val byteBuf = internalBuffer
            byteBuf.putByte(position++, (newValue and 0x7F or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 7 or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 14 or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 21 or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 28).toByte())
            return 5
        }
        if (newValue ushr 42 == 0L) {
            val byteBuf = internalBuffer
            byteBuf.putByte(position++, (newValue and 0x7F or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 7 or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 14 or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 21 or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 28 or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 35).toByte())
            return 6
        }
        if (newValue ushr 49 == 0L) {
            val byteBuf = internalBuffer
            byteBuf.putByte(position++, (newValue and 0x7F or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 7 or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 14 or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 21 or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 28 or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 35 or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 42).toByte())
            return 7
        }
        if (newValue ushr 56 == 0L) {
            val byteBuf = internalBuffer
            byteBuf.putByte(position++, (newValue and 0x7F or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 7 or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 14 or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 21 or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 28 or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 35 or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 42 or 0x80).toByte())
            byteBuf.putByte(position++, (newValue ushr 49).toByte())
            return 8
        }
        val byteBuf = internalBuffer
        byteBuf.putByte(position++, (newValue and 0x7F or 0x80).toByte())
        byteBuf.putByte(position++, (newValue ushr 7 or 0x80).toByte())
        byteBuf.putByte(position++, (newValue ushr 14 or 0x80).toByte())
        byteBuf.putByte(position++, (newValue ushr 21 or 0x80).toByte())
        byteBuf.putByte(position++, (newValue ushr 28 or 0x80).toByte())
        byteBuf.putByte(position++, (newValue ushr 35 or 0x80).toByte())
        byteBuf.putByte(position++, (newValue ushr 42 or 0x80).toByte())
        byteBuf.putByte(position++, (newValue ushr 49 or 0x80).toByte())
        byteBuf.putByte(position++, (newValue ushr 56).toByte())
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
        var permitAscii = charCount in 2..32
        if (permitAscii) {
            for (i in 0 until charCount) {
                if (value[i].code > 127) {
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
            val c = value[charIndex].code
            if (c > 127) break
            byteBuf.putByte(position++, c.toByte())
            charIndex++
            if (charIndex == charCount) {
                return
            }
        }
        if (charIndex < charCount) writeUtf8Slow(value, charCount, charIndex)
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
            byteBuf.putByte(position++, value[i].code.toByte())
            ++i
        }
        byteBuf.putByte(position - 1, (byteBuf.getByte(position - 1).toInt() or 0x80).toByte()) // Bit 8 means end of ASCII.
    }

    private fun writeUtf8Slow(value: String, charCount: Int, charIndex: Int) {
        var index = charIndex
        while (index < charCount) {
            val c = value[index].code

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
            index++
        }
    }

    // Primitive arrays:
    @Throws(KryoException::class)
    override fun writeInts(array: IntArray, offset: Int, count: Int) {
        var newOffset = offset
        val n = newOffset + count
        while (newOffset < n) {
            val value = array[newOffset]
            writeInt(value)
            newOffset++
        }
    }

    @Throws(KryoException::class)
    override fun writeLongs(array: LongArray, offset: Int, count: Int) {
        var newOffset = offset
        val n = newOffset + count
        while (newOffset < n) {
            val value = array[newOffset]
            writeLong(value)
            newOffset++
        }
    }

    @Throws(KryoException::class)
    override fun writeFloats(array: FloatArray, offset: Int, count: Int) {
        var newOffset = offset
        val n = newOffset + count
        while (newOffset < n) {
            val value = java.lang.Float.floatToIntBits(array[newOffset])
            writeFloat(value.toFloat())
            newOffset++
        }
    }

    @Throws(KryoException::class)
    override fun writeDoubles(array: DoubleArray, offset: Int, count: Int) {
        var newOffset = offset
        val n = newOffset + count
        while (newOffset < n) {
            val value = java.lang.Double.doubleToLongBits(array[newOffset])
            writeDouble(value.toDouble())
            newOffset++
        }
    }

    @Throws(KryoException::class)
    override fun writeShorts(array: ShortArray, offset: Int, count: Int) {
        var newOffset = offset
        val n = newOffset + count
        while (newOffset < n) {
            val value = array[newOffset].toInt()
            writeShort(value)
            newOffset++
        }
    }

    @Throws(KryoException::class)
    override fun writeChars(array: CharArray, offset: Int, count: Int) {
        var newOffset = offset
        val n = newOffset + count
        while (newOffset < n) {
            val value = array[newOffset].code
            writeChar(value.toChar())
            newOffset++
        }
    }

    @Throws(KryoException::class)
    override fun writeBooleans(array: BooleanArray, offset: Int, count: Int) {
        var newOffset = offset
        val n = newOffset + count
        while (newOffset < n) {
            internalBuffer.putByte(position++, if (array[newOffset]) 1.toByte() else 0)
            newOffset++
        }
    }
}
