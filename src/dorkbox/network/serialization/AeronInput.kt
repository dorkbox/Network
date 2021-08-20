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
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.util.Util
import org.agrona.DirectBuffer
import org.agrona.concurrent.UnsafeBuffer
import java.io.IOException
import java.io.InputStream

/**
 * An [InputStream] which reads data from a [DirectBuffer].
 *
 * A read operation against this stream will occur at the `readerIndex`
 * of its underlying buffer and the `readerIndex` will increase during
 * the read operation.
 *
 * The endianness of the stream is not always big endian but depends on
 * the endianness of the underlying buffer.
 *
 * Utility methods are provided for efficiently reading primitive types and strings.
 *
 * Modified from KRYO ByteBufferInput to use ByteBuf instead of ByteBuffer.
 */
class AeronInput
    /** Creates an uninitialized Input, [.setBuffer] must be called before the Input is used.  */
    () : Input() {


    /** the buffer. The bytes between zero and [position] are the data that has been read.  */
    var internalBuffer: DirectBuffer? = null
        private set

    /**
     * Creates a new Input for reading from a [DirectBuffer] which is filled with the specified bytes.
     *
     * @see [setBuffer]
     */
    constructor(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) : this() {
        setBuffer(bytes, offset, length)
    }

    /**
     * Creates a new Input for reading from a [DirectBuffer] which is filled with the specified bytes.
     *
     * @see [setBuffer]
     */
    constructor(buffer: DirectBuffer, offset: Int = 0, length: Int = buffer.capacity()) : this() {
        setBuffer(buffer, offset, length)
    }

    /**
     * Throws [UnsupportedOperationException] because this input uses a DirectBuffer, not a byte[].
     */
    @Deprecated("This input does not use a byte[]", ReplaceWith("internalBuffer"))
    override fun getBuffer(): ByteArray {
        throw UnsupportedOperationException("This input does not use a byte[], see #internalBuffer.")
    }

    /** Sets a new buffer. The offset is 0 and the count is the buffer's length.
     *
     * @see [setBuffer]
     */
    override fun setBuffer(bytes: ByteArray) {
        setBuffer(bytes, 0, bytes.size)
    }

    /**
     * Throws [UnsupportedOperationException] because this input uses a DirectBuffer, not a byte[].
     */
    override fun setBuffer(bytes: ByteArray, offset: Int, count: Int) {
        internalBuffer = UnsafeBuffer(bytes, offset, count)
        position = 0
        limit = count
        capacity = count
    }

    @Deprecated("This input does not use an inputStream", ReplaceWith("setByteBuf(buffer)"))
    override fun setInputStream(inputStream: InputStream) {
        throw UnsupportedOperationException("This input does not use an inputStream, see setByteBuf().")
    }

    /**
     * Sets the internal buffer (and properties based on that buffer)
     */
    fun setBuffer(buffer: DirectBuffer) {
        setBuffer(buffer, 0, buffer.capacity())
    }

    /**
     * Sets the internal buffer (and properties based on that buffer)
     */
    fun setBuffer(buffer: DirectBuffer, offset: Int, length: Int) {
        internalBuffer = buffer
        position = offset
        limit = length
        capacity = buffer.capacity()
    }

    override fun reset() {
        super.reset()
    }

    @Throws(KryoException::class)
    override fun require(required: Int): Int {
        return 0
    }

    /**
     * Fills the buffer with at least the number of bytes specified, if possible.
     * @param optional Must be > 0.
     * @return the number of bytes remaining, always the value of optional, as this is not used for anything
     */
    @Throws(KryoException::class)
    override fun optional(optional: Int): Int {
        return optional
    }

    /**
     * Returns true if the [.limit] has been reached and [.fill] is unable to provide more bytes.
     */
    override fun end(): Boolean {
        return position >= limit
    }

    // InputStream:
    @Throws(IOException::class)
    override fun available(): Int {
        return limit - position
    }

    // InputStream:
    @Throws(KryoException::class)
    override fun read(): Int {
        val result: Int = internalBuffer!!.getByte(position).toInt() and 0xFF
        position++
        return result
    }

    @Throws(KryoException::class)
    override fun read(bytes: ByteArray): Int {
        return read(bytes, 0, bytes.size)
    }

    @Throws(KryoException::class)
    override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
        var newCount = count
        if (position + newCount > limit) {
            newCount = limit - position
        }
        internalBuffer!!.getBytes(position, bytes, offset, newCount)
        position += newCount
        return newCount
    }

    override fun setPosition(position: Int) {
        this.position = position
    }

    override fun setLimit(limit: Int) {
        this.limit = limit
    }

    @Throws(KryoException::class)
    override fun skip(count: Int) {
        super.skip(count)
    }

    @Throws(KryoException::class)
    override fun skip(count: Long): Long {
        var remaining = count
        while (remaining > 0) {
            val skip = Math.min(Util.maxArraySize.toLong(), remaining).toInt()
            skip(skip)
            remaining -= skip.toLong()
        }
        return count
    }

    @Throws(KryoException::class)
    override fun close() {
        if (inputStream != null) {
            try {
                inputStream.close()
            } catch (ignored: IOException) {
            }
        }
    }

    // byte:
    @Throws(KryoException::class)
    override fun readByte(): Byte {
        val result = internalBuffer!!.getByte(position)
        position++
        return result
    }

    @Throws(KryoException::class)
    override fun readByteUnsigned(): Int {
        val result: Int = internalBuffer!!.getByte(position).toInt() and 0xFF
        position++
        return result
    }

    @Throws(KryoException::class)
    override fun readBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        readBytes(bytes, 0, length)
        return bytes
    }

    @Throws(KryoException::class)
    override fun readBytes(bytes: ByteArray, offset: Int, count: Int) {
        internalBuffer!!.getBytes(position, bytes, offset, count)
        position += count
    }

    // int:
    @Throws(KryoException::class)
    override fun readInt(): Int {
        val result = internalBuffer!!.getInt(position)
        position += 4
        return result
    }

    @Throws(KryoException::class)
    override fun readVarInt(optimizePositive: Boolean): Int {
        var b = internalBuffer!!.getByte(position++).toInt()
        var result = b and 0x7F
        if (b and 0x80 != 0) {
            val byteBuf = internalBuffer!!
            b = byteBuf.getByte(position++).toInt()
            result = result or (b and 0x7F shl 7)
            if (b and 0x80 != 0) {
                b = byteBuf.getByte(position++).toInt()
                result = result or (b and 0x7F shl 14)
                if (b and 0x80 != 0) {
                    b = byteBuf.getByte(position++).toInt()
                    result = result or (b and 0x7F shl 21)
                    if (b and 0x80 != 0) {
                        b = byteBuf.getByte(position++).toInt()
                        result = result or (b and 0x7F shl 28)
                    }
                }
            }
        }
        return if (optimizePositive) result else result ushr 1 xor -(result and 1)
    }

    @Throws(KryoException::class)
    override fun canReadVarInt(): Boolean {
        if (limit - position >= 5) return true
        if (limit <= position) return false
        var p = position
        val limit = limit
        val byteBuf = internalBuffer
        if (byteBuf!!.getByte(p++).toInt() and 0x80 == 0) return true
        if (p == limit) return false
        if (byteBuf.getByte(p++).toInt() and 0x80 == 0) return true
        if (p == limit) return false
        if (byteBuf.getByte(p++).toInt() and 0x80 == 0) return true
        if (p == limit) return false
        if (byteBuf.getByte(p++).toInt() and 0x80 == 0) return true
        return if (p == limit) false else true
    }

    /** Reads the boolean part of a varint flag. The position is not advanced, [.readVarIntFlag] should be used to
     * advance the position.  */
    override fun readVarIntFlag(): Boolean {
        return internalBuffer!!.getByte(position).toInt() and 0x80 != 0
    }

    /** Reads the 1-5 byte int part of a varint flag. The position is advanced so if the boolean part is needed it should be read
     * first with [.readVarIntFlag].  */
    override fun readVarIntFlag(optimizePositive: Boolean): Int {
        var b = internalBuffer!!.getByte(position++).toInt()
        var result = b and 0x3F // Mask first 6 bits.
        if (b and 0x40 != 0) { // Bit 7 means another byte, bit 8 means UTF8.
            val byteBuf = internalBuffer
            b = byteBuf!!.getByte(position++).toInt()
            result = result or (b and 0x7F shl 6)
            if (b and 0x80 != 0) {
                b = byteBuf.getByte(position++).toInt()
                result = result or (b and 0x7F shl 13)
                if (b and 0x80 != 0) {
                    b = byteBuf.getByte(position++).toInt()
                    result = result or (b and 0x7F shl 20)
                    if (b and 0x80 != 0) {
                        b = byteBuf.getByte(position++).toInt()
                        result = result or (b and 0x7F shl 27)
                    }
                }
            }
        }
        return if (optimizePositive) result else result ushr 1 xor -(result and 1)
    }

    // long:
    @Throws(KryoException::class)
    override fun readLong(): Long {
        val result = internalBuffer!!.getLong(position)
        position += 8
        return result
    }

    @Throws(KryoException::class)
    override fun readVarLong(optimizePositive: Boolean): Long {
        var b = internalBuffer!!.getByte(position++).toInt()
        var result = (b and 0x7F).toLong()
        if (b and 0x80 != 0) {
            val byteBuf = internalBuffer
            b = byteBuf!!.getByte(position++).toInt()
            result = result or (b and 0x7F shl 7).toLong()
            if (b and 0x80 != 0) {
                b = byteBuf.getByte(position++).toInt()
                result = result or (b and 0x7F shl 14).toLong()
                if (b and 0x80 != 0) {
                    b = byteBuf.getByte(position++).toInt()
                    result = result or (b and 0x7F shl 21).toLong()
                    if (b and 0x80 != 0) {
                        b = byteBuf.getByte(position++).toInt()
                        result = result or ((b and 0x7F).toLong() shl 28)
                        if (b and 0x80 != 0) {
                            b = byteBuf.getByte(position++).toInt()
                            result = result or ((b and 0x7F).toLong() shl 35)
                            if (b and 0x80 != 0) {
                                b = byteBuf.getByte(position++).toInt()
                                result = result or ((b and 0x7F).toLong() shl 42)
                                if (b and 0x80 != 0) {
                                    b = byteBuf.getByte(position++).toInt()
                                    result = result or ((b and 0x7F).toLong() shl 49)
                                    if (b and 0x80 != 0) {
                                        b = byteBuf.getByte(position++).toInt()
                                        result = result or (b.toLong() shl 56)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return if (optimizePositive) result else result ushr 1 xor -(result and 1)
    }

    @Throws(KryoException::class)
    override fun canReadVarLong(): Boolean {
        if (limit - position >= 9) return true
        var p = position
        val limit = limit
        val byteBuf = internalBuffer
        if (byteBuf!!.getByte(p++).toInt() and 0x80 == 0) return true
        if (p == limit) return false
        if (byteBuf.getByte(p++).toInt() and 0x80 == 0) return true
        if (p == limit) return false
        if (byteBuf.getByte(p++).toInt() and 0x80 == 0) return true
        if (p == limit) return false
        if (byteBuf.getByte(p++).toInt() and 0x80 == 0) return true
        if (p == limit) return false
        if (byteBuf.getByte(p++).toInt() and 0x80 == 0) return true
        if (p == limit) return false
        if (byteBuf.getByte(p++).toInt() and 0x80 == 0) return true
        if (p == limit) return false
        if (byteBuf.getByte(p++).toInt() and 0x80 == 0) return true
        if (p == limit) return false
        if (byteBuf.getByte(p++).toInt() and 0x80 == 0) return true
        return if (p == limit) false else true
    }

    // float:
    @Throws(KryoException::class)
    override fun readFloat(): Float {
        val result = internalBuffer!!.getFloat(position)
        position += 4
        return result
    }

    // double:
    @Throws(KryoException::class)
    override fun readDouble(): Double {
        val result = internalBuffer!!.getDouble(position)
        position += 8
        return result
    }

    // boolean:
    @Throws(KryoException::class)
    override fun readBoolean(): Boolean {
        return internalBuffer!!.getByte(position++).toInt() == 1
    }

    // short:
    @Throws(KryoException::class)
    override fun readShort(): Short {
        val result = internalBuffer!!.getShort(position)
        position += 2
        return result
    }

    @Throws(KryoException::class)
    override fun readShortUnsigned(): Int {
        val result: Int = internalBuffer!!.getShort(position).toInt() and 0xFF
        position += 2
        return result
    }

    // char:
    @Throws(KryoException::class)
    override fun readChar(): Char {
        val result = internalBuffer!!.getChar(position)
        position += 2
        return result
    }

    // String:
    override fun readString(): String? {
        if (!readVarIntFlag()) return readAsciiString() // ASCII.
        // Null, empty, or UTF8.
        var charCount = readVarIntFlag(true)
        when (charCount) {
            0 -> return null
            1 -> return ""
        }

        charCount-- // make count adjustment
        readUtf8Chars(charCount)
        return String(chars, 0, charCount)
    }

    override fun readStringBuilder(): StringBuilder? {
        if (!readVarIntFlag()) return StringBuilder(readAsciiString()) // ASCII.

        // Null, empty, or UTF8.
        var charCount = readVarIntFlag(true)
        when (charCount) {
            0 -> return null
            1 -> return StringBuilder("")
        }

        charCount--
        readUtf8Chars(charCount)
        val builder = StringBuilder(charCount)
        builder.append(chars, 0, charCount)
        return builder
    }

    private fun readUtf8Chars(charCount: Int) {
        if (chars.size < charCount) chars = CharArray(charCount)
        val chars = chars
        // Try to read 7 bit ASCII chars.
        val byteBuf = internalBuffer
        var charIndex = 0
        while (charIndex < charCount) {
            val b = byteBuf!!.getByte(position).toInt()
            if (b < 0) break
            position++ // only increment read position if the char was 7bit
            chars[charIndex++] = b.toChar()
        }

        // If buffer didn't hold all chars or any were not ASCII, use slow path for remainder.
        if (charIndex < charCount) readUtf8Chars_slow(charCount, charIndex)
    }

    private fun readAsciiString(): String {
        val chars = chars
        val byteBuf = internalBuffer
        var charCount = 0
        val n = chars.size.coerceAtMost(limit - position)

        while (charCount < n) {
            val b = byteBuf!!.getByte(position++).toInt()
            if (b and 0x80 == 0x80) {
                chars[charCount] = (b and 0x7F).toChar()
                return String(chars, 0, charCount + 1)
            }
            chars[charCount] = b.toChar()
            charCount++
        }

        return readAscii_slow(charCount)
    }

    private fun readAscii_slow(charCount: Int): String {
        var count = charCount
        var chars = chars
        val byteBuf = internalBuffer

        while (true) {
            val b = byteBuf!!.getByte(position++).toInt()
            if (count == chars.size) {
                val newChars = CharArray(count * 2)
                System.arraycopy(chars, 0, newChars, 0, count)
                chars = newChars
                this.chars = newChars
            }
            if (b and 0x80 == 0x80) {
                chars[count] = (b and 0x7F).toChar()
                return String(chars, 0, count + 1)
            }
            chars[count++] = b.toChar()
        }
    }

    private fun readUtf8Chars_slow(charCount: Int, charIndex: Int) {
        var index = charIndex
        val byteBuf = internalBuffer
        val chars = chars

        while (index < charCount) {
            val b: Int = byteBuf!!.getByte(position++).toInt() and 0xFF
            when (b shr 4) {
                0, 1, 2, 3, 4, 5, 6, 7 -> chars[index] = b.toChar()
                12, 13 -> chars[index] = (b and 0x1F shl 6 or (byteBuf.getByte(position++).toInt() and 0x3F)).toChar()
                14 -> {
                    val b2 = byteBuf.getByte(position++).toInt()
                    val b3 = byteBuf.getByte(position++).toInt()
                    chars[index] = (b and 0x0F shl 12 or (b2 and 0x3F shl 6) or (b3 and 0x3F)).toChar()
                }
            }
            index++
        }
    }

    // Primitive arrays:
    @Throws(KryoException::class)
    override fun readInts(length: Int): IntArray {
        val array = IntArray(length)
        for (i in 0 until length) {
            array[i] = readInt()
        }
        return array
    }

    @Throws(KryoException::class)
    override fun readLongs(length: Int): LongArray {
        val array = LongArray(length)
        for (i in 0 until length) {
            array[i] = readLong()
        }
        return array
    }

    @Throws(KryoException::class)
    override fun readFloats(length: Int): FloatArray {
        val array = FloatArray(length)
        for (i in 0 until length) {
            array[i] = readFloat()
        }
        return array
    }

    @Throws(KryoException::class)
    override fun readDoubles(length: Int): DoubleArray {
        val array = DoubleArray(length)
        for (i in 0 until length) {
            array[i] = readDouble()
        }
        return array
    }

    @Throws(KryoException::class)
    override fun readShorts(length: Int): ShortArray {
        val array = ShortArray(length)
        for (i in 0 until length) {
            array[i] = readShort()
        }
        return array
    }

    @Throws(KryoException::class)
    override fun readChars(length: Int): CharArray {
        val array = CharArray(length)
        for (i in 0 until length) {
            array[i] = readChar()
        }
        return array
    }

    @Throws(KryoException::class)
    override fun readBooleans(length: Int): BooleanArray {
        val array = BooleanArray(length)
        for (i in 0 until length) {
            array[i] = readBoolean()
        }
        return array
    }
}
