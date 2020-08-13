/*
 * Copyright 2010 dorkbox, llc
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
package dorkbox.network.pipeline;

import java.io.DataOutput;
import java.io.OutputStream;

import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Output;

/**
 * An {@link OutputStream} which writes data to a {@link MutableDirectBuffer}.
 * <p>
 * A write operation against this stream will occur at the {@code writerIndex}
 * of its underlying buffer and the {@code writerIndex} will increase during
 * the write operation.
 * <p>
 * This stream implements {@link DataOutput} for your convenience.
 * The endianness of the stream is not always big endian but depends on
 * the endianness of the underlying buffer.
 *
 * <p>
 * Utility methods are provided for efficiently reading primitive types and strings.
 *
 * Modified from KRYO to use ByteBuf.
 */
public class AeronOutput extends Output {

    // NOTE: capacity IS NOT USED!

    private MutableDirectBuffer internalBuffer;

    /** Creates a new Output for writing to a direct {@link MutableDirectBuffer}. */
    public
    AeronOutput() {
        this(32);
    }

    /**
     * Creates a new Output for writing to a direct {@link MutableDirectBuffer}.
     *
     * @param bufferSize The size of the buffer.
     */
    public
    AeronOutput(int bufferSize) {
        if (bufferSize < 0) {
            throw new IllegalArgumentException("bufferSize must be >= 0!");
        }
        // Minimum buffer size allowed is size = 2 (because it grows by 1.5x the current size)
        internalBuffer = new ExpandableDirectByteBuffer(Math.max(2, bufferSize));
    }

    /**
     * Creates a new Output for writing to a byte[].
     * @see #setBuffer(byte[])
     */
    public
    AeronOutput(final byte[] buffer) {
        internalBuffer = new UnsafeBuffer(buffer);
        position = 0;
        capacity = internalBuffer.capacity();
    }

    /**
     * Creates a new Output for writing to a DirectBuffer.
     */
    public
    AeronOutput(final MutableDirectBuffer buffer) {
        internalBuffer = buffer;
        position = 0;
        capacity = internalBuffer.capacity();
    }


    @Override
    public OutputStream getOutputStream () {
        throw new UnsupportedOperationException("This input does not use an OutputStream.");
    }

    /**
     * Throws {@link UnsupportedOperationException} because this output uses a ByteBuffer, not a byte[].
     * @deprecated
     * @see #getInternalBuffer() */
    @Override
    @Deprecated
    public byte[] getBuffer () {
        throw new UnsupportedOperationException("This buffer does not used a byte[], see #getInternaleBuffer().");
    }

    /**
     * Sets a new buffer to write to. The max size is the buffer's length.
     */
    @Override
    public void setBuffer (byte[] buffer) {
        internalBuffer = new UnsafeBuffer(buffer);
        position = 0;
        capacity = internalBuffer.capacity();
    }

    /**
     * Sets a new buffer to write to. The max size is the buffer's length.
     */
    @Override
    @Deprecated
    public void setBuffer (byte[] buffer, int maxBufferSize) {
        setBuffer(buffer);
    }

    /** Returns the buffer. The bytes between zero and {@link #position()} are the data that has been written. */
    public MutableDirectBuffer getInternalBuffer() {
        return internalBuffer;
    }

    @Override
    public byte[] toBytes () {
        byte[] newBuffer = new byte[position];
        internalBuffer.getBytes(0, newBuffer, 0, position);
        return newBuffer;
    }

    @Override
    public void setPosition (int position) {
        this.position = position;
    }

    @Override
    public void reset () {
        super.reset();
    }

    /**
     * Ensures the buffer is large enough to read the specified number of bytes.
     * @return true if the buffer has been resized.
     */
    @Override
    protected boolean require (int required) throws KryoException {
        return false;
    }

    // OutputStream:

    @Override
    public void flush () throws KryoException {
    }

    @Override
    public void close () throws KryoException {
        flush();
    }

    @Override
    public void write (int value) throws KryoException {
        internalBuffer.putByte(position++, (byte)value);
    }

    @Override
    public void write (byte[] bytes) throws KryoException {
        writeBytes(bytes, 0, bytes.length);
    }

    @Override
    public void write (byte[] bytes, int offset, int length) throws KryoException {
        writeBytes(bytes, offset, length);
    }

    // byte:

    @Override
    public void writeByte (byte value) throws KryoException {
        internalBuffer.putByte(position++, value);
    }

    @Override
    public void writeByte (int value) throws KryoException {
        internalBuffer.putByte(position++, (byte)value);
    }

    @Override
    public void writeBytes (byte[] bytes) throws KryoException {
        writeBytes(bytes, 0, bytes.length);
    }

    @Override
    public void writeBytes (byte[] bytes, int offset, int length) throws KryoException {
        internalBuffer.putBytes(position, bytes, offset, length);
        position += length;
    }

    // int:

    @Override
    public void writeInt (int value) throws KryoException {
        internalBuffer.putInt(position, value);
        position += 4;
    }

    @Override
    public int writeVarInt (int value, boolean optimizePositive) throws KryoException {
        if (!optimizePositive) value = (value << 1) ^ (value >> 31);
        if (value >>> 7 == 0) {
            internalBuffer.putByte(position++, (byte)value);
            return 1;
        }
        if (value >>> 14 == 0) {
            internalBuffer.putByte(position++, (byte)((value & 0x7F) | 0x80));
            internalBuffer.putByte(position++, (byte)(value >>> 7));
            return 2;
        }
        if (value >>> 21 == 0) {
            MutableDirectBuffer byteBuf = this.internalBuffer;
            byteBuf.putByte(position++, (byte)((value & 0x7F) | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 7 | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 14));
            return 3;
        }
        if (value >>> 28 == 0) {
            MutableDirectBuffer byteBuf = this.internalBuffer;
            byteBuf.putByte(position++, (byte)((value & 0x7F) | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 7 | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 14 | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 21));
            return 4;
        }
        MutableDirectBuffer byteBuf = this.internalBuffer;
        byteBuf.putByte(position++, (byte)((value & 0x7F) | 0x80));
        byteBuf.putByte(position++, (byte)(value >>> 7 | 0x80));
        byteBuf.putByte(position++, (byte)(value >>> 14 | 0x80));
        byteBuf.putByte(position++, (byte)(value >>> 21 | 0x80));
        byteBuf.putByte(position++, (byte)(value >>> 28));
        return 5;
    }

    @Override
    public int writeVarIntFlag (boolean flag, int value, boolean optimizePositive) throws KryoException {
        if (!optimizePositive) value = (value << 1) ^ (value >> 31);
        int first = (value & 0x3F) | (flag ? 0x80 : 0); // Mask first 6 bits, bit 8 is the flag.
        if (value >>> 6 == 0) {
            internalBuffer.putByte(position++, (byte)first);
            return 1;
        }
        if (value >>> 13 == 0) {
            internalBuffer.putByte(position++, (byte)(first | 0x40)); // Set bit 7.
            internalBuffer.putByte(position++, (byte)(value >>> 6));
            return 2;
        }
        if (value >>> 20 == 0) {
            MutableDirectBuffer byteBuf = this.internalBuffer;
            byteBuf.putByte(position++, (byte)(first | 0x40)); // Set bit 7.
            byteBuf.putByte(position++, (byte)((value >>> 6) | 0x80)); // Set bit 8.
            byteBuf.putByte(position++, (byte)(value >>> 13));
            return 3;
        }
        if (value >>> 27 == 0) {
            MutableDirectBuffer byteBuf = this.internalBuffer;
            byteBuf.putByte(position++, (byte)(first | 0x40)); // Set bit 7.
            byteBuf.putByte(position++, (byte)((value >>> 6) | 0x80)); // Set bit 8.
            byteBuf.putByte(position++, (byte)((value >>> 13) | 0x80)); // Set bit 8.
            byteBuf.putByte(position++, (byte)(value >>> 20));
            return 4;
        }
        MutableDirectBuffer byteBuf = this.internalBuffer;
        byteBuf.putByte(position++, (byte)(first | 0x40)); // Set bit 7.
        byteBuf.putByte(position++, (byte)((value >>> 6) | 0x80)); // Set bit 8.
        byteBuf.putByte(position++, (byte)((value >>> 13) | 0x80)); // Set bit 8.
        byteBuf.putByte(position++, (byte)((value >>> 20) | 0x80)); // Set bit 8.
        byteBuf.putByte(position++, (byte)(value >>> 27));
        return 5;
    }

    // long:

    @Override
    public void writeLong (long value) throws KryoException {
        internalBuffer.putLong(position, value);
        position += 8;
    }

    @Override
    public int writeVarLong (long value, boolean optimizePositive) throws KryoException {
        if (!optimizePositive) value = (value << 1) ^ (value >> 63);
        if (value >>> 7 == 0) {
            internalBuffer.putByte(position++, (byte)value);
            return 1;
        }
        if (value >>> 14 == 0) {
            internalBuffer.putByte(position++, (byte)((value & 0x7F) | 0x80));
            internalBuffer.putByte(position++, (byte)(value >>> 7));
            return 2;
        }
        if (value >>> 21 == 0) {
            MutableDirectBuffer byteBuf = this.internalBuffer;
            byteBuf.putByte(position++, (byte)((value & 0x7F) | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 7 | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 14));
            return 3;
        }
        if (value >>> 28 == 0) {
            MutableDirectBuffer byteBuf = this.internalBuffer;
            byteBuf.putByte(position++, (byte)((value & 0x7F) | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 7 | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 14 | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 21));
            return 4;
        }
        if (value >>> 35 == 0) {
            MutableDirectBuffer byteBuf = this.internalBuffer;
            byteBuf.putByte(position++, (byte)((value & 0x7F) | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 7 | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 14 | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 21 | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 28));
            return 5;
        }
        if (value >>> 42 == 0) {
            MutableDirectBuffer byteBuf = this.internalBuffer;
            byteBuf.putByte(position++, (byte)((value & 0x7F) | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 7 | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 14 | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 21 | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 28 | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 35));
            return 6;
        }
        if (value >>> 49 == 0) {
            MutableDirectBuffer byteBuf = this.internalBuffer;
            byteBuf.putByte(position++, (byte)((value & 0x7F) | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 7 | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 14 | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 21 | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 28 | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 35 | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 42));
            return 7;
        }
        if (value >>> 56 == 0) {
            MutableDirectBuffer byteBuf = this.internalBuffer;
            byteBuf.putByte(position++, (byte)((value & 0x7F) | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 7 | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 14 | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 21 | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 28 | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 35 | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 42 | 0x80));
            byteBuf.putByte(position++, (byte)(value >>> 49));
            return 8;
        }
        MutableDirectBuffer byteBuf = this.internalBuffer;
        byteBuf.putByte(position++, (byte)((value & 0x7F) | 0x80));
        byteBuf.putByte(position++, (byte)(value >>> 7 | 0x80));
        byteBuf.putByte(position++, (byte)(value >>> 14 | 0x80));
        byteBuf.putByte(position++, (byte)(value >>> 21 | 0x80));
        byteBuf.putByte(position++, (byte)(value >>> 28 | 0x80));
        byteBuf.putByte(position++, (byte)(value >>> 35 | 0x80));
        byteBuf.putByte(position++, (byte)(value >>> 42 | 0x80));
        byteBuf.putByte(position++, (byte)(value >>> 49 | 0x80));
        byteBuf.putByte(position++, (byte)(value >>> 56));
        return 9;
    }

    // float:

    @Override
    public void writeFloat (float value) throws KryoException {
        internalBuffer.putFloat(position, value);
        position += 4;
    }

    // double:

    @Override
    public void writeDouble (double value) throws KryoException {
        internalBuffer.putDouble(position, value);
        position += 8;
    }

    // short:

    @Override
    public void writeShort (int value) throws KryoException {
        internalBuffer.putShort(position, (short) value);
        position += 2;
    }

    // char:

    @Override
    public void writeChar (char value) throws KryoException {
        internalBuffer.putChar(position, value);
        position += 2;
    }

    // boolean:

    @Override
    public void writeBoolean (boolean value) throws KryoException {
        internalBuffer.putByte(position++, (byte)(value ? 1 : 0));
    }

    // String:

    @Override
    public void writeString (String value) throws KryoException {
        if (value == null) {
            writeByte(0x80); // 0 means null, bit 8 means UTF8.
            return;
        }

        int charCount = value.length();
        if (charCount == 0) {
            writeByte(1 | 0x80); // 1 means empty string, bit 8 means UTF8.
            return;
        }

        // Detect ASCII, we only do this for small strings, but ONLY more than 1 char.
        // since 1 char is used for bit-masking if we use for 1 char string, reading the string will not work!
        boolean permitAscii = charCount > 1 && charCount <= 32;

        OUTER:
        if (permitAscii) {
            for (int i = 0; i < charCount; i++) {
                if (value.charAt(i) > 127) {
                    break OUTER; // not ascii
                }
            }

            // this is ascii
            internalBuffer.putStringWithoutLengthAscii(position, value);
            position += charCount;

            // mod the last written byte with 0x80 so we can use that when reading ascii bytes to see what the end of the string is
            byte b = (byte) (internalBuffer.getByte(position - 1) | 0x80);
            internalBuffer.putByte(position - 1, b);
            return;
        }

        // UTF8 (or ASCII with length 1 or length > 32

        writeVarIntFlag(true, charCount + 1, true);

        int charIndex = 0;
        // Try to write 7 bit chars.
        MutableDirectBuffer byteBuf = this.internalBuffer;
        while (true) {
            int c = value.charAt(charIndex);
            if (c > 127) break;
            byteBuf.putByte(position++, (byte)c);

            charIndex++;
            if (charIndex == charCount) {
                return;
            }
        }

        if (charIndex < charCount) writeUtf8_slow(value, charCount, charIndex);
    }

    @Override
    public void writeAscii (String value) throws KryoException {
        if (value == null) {
            writeByte(0x80); // 0 means null, bit 8 means UTF8.
            return;
        }
        int charCount = value.length();
        if (charCount == 0) {
            writeByte(1 | 0x80); // 1 means empty string, bit 8 means UTF8.
            return;
        }

        require(charCount); // must be able to write this number of chars

        MutableDirectBuffer byteBuf = this.internalBuffer;
        for (int i = 0, n = value.length(); i < n; ++i) {
            byteBuf.putByte(position++, (byte)value.charAt(i));
        }

        byteBuf.putByte(position - 1, (byte)(byteBuf.getByte(position - 1) | 0x80)); // Bit 8 means end of ASCII.
    }

    private void writeUtf8_slow (String value, int charCount, int charIndex) {
        for (; charIndex < charCount; charIndex++) {
            int c = value.charAt(charIndex);
            if (c <= 0x007F) {
                internalBuffer.putByte(position++, (byte)c);
            }
            else if (c > 0x07FF) {
                internalBuffer.putByte(position++, (byte)(0xE0 | c >> 12 & 0x0F));
                internalBuffer.putByte(position++, (byte)(0x80 | c >> 6 & 0x3F));
                internalBuffer.putByte(position++, (byte)(0x80 | c & 0x3F));
            } else {
                internalBuffer.putByte(position++, (byte)(0xC0 | c >> 6 & 0x1F));
                internalBuffer.putByte(position++, (byte)(0x80 | c & 0x3F));
            }
        }
    }

    // Primitive arrays:

    @Override
    public void writeInts (int[] array, int offset, int count) throws KryoException {
        for (int n = offset + count; offset < n; offset++) {
            int value = array[offset];
            writeInt(value);
        }
    }

    @Override
    public void writeLongs (long[] array, int offset, int count) throws KryoException {
        for (int n = offset + count; offset < n; offset++) {
            long value = array[offset];
            writeLong(value);
        }
    }

    @Override
    public void writeFloats (float[] array, int offset, int count) throws KryoException {
        for (int n = offset + count; offset < n; offset++) {
            int value = Float.floatToIntBits(array[offset]);
            writeFloat(value);
        }
    }

    @Override
    public void writeDoubles (double[] array, int offset, int count) throws KryoException {
        for (int n = offset + count; offset < n; offset++) {
            long value = Double.doubleToLongBits(array[offset]);
            writeDouble(value);
        }
    }

    @Override
    public void writeShorts (short[] array, int offset, int count) throws KryoException {
        for (int n = offset + count; offset < n; offset++) {
            int value = array[offset];
            writeShort(value);
        }
    }

    @Override
    public void writeChars (char[] array, int offset, int count) throws KryoException {
        for (int n = offset + count; offset < n; offset++) {
            int value = array[offset];
            writeChar((char) value);
        }
    }

    @Override
    public void writeBooleans (boolean[] array, int offset, int count) throws KryoException {
        for (int n = offset + count; offset < n; offset++) {
            internalBuffer.putByte(position++, array[offset] ? (byte)1 : 0);
        }
    }
}
