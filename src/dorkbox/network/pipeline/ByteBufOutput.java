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
import java.io.IOException;
import java.io.OutputStream;

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.Util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * An {@link OutputStream} which writes data to a {@link ByteBuf}.
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
public class ByteBufOutput extends Output {

    // NOTE: capacity IS NOT USED!

    private ByteBuf byteBuf;
    private int initialReaderIndex = 0;
    private int initialWriterIndex = 0;


    /** Creates an uninitialized Output, {@link #setBuffer(ByteBuf)} must be called before the Output is used. */
    public ByteBufOutput () {
    }

    /** Creates a new Output for writing to a direct {@link ByteBuf}.
     * @param bufferSize The size of the buffer. An exception is thrown if more bytes than this are written and {@link #flush()}
     *           does not empty the buffer. */
    public ByteBufOutput (int bufferSize) {
        this(bufferSize, bufferSize);
    }

    /** Creates a new Output for writing to a direct ByteBuffer.
     * @param bufferSize The initial size of the buffer.
     * @param maxBufferSize If {@link #flush()} does not empty the buffer, the buffer is doubled as needed until it exceeds
     *           maxBufferSize and an exception is thrown. Can be -1 for no maximum. */
    public ByteBufOutput (int bufferSize, int maxBufferSize) {
        if (maxBufferSize < -1) throw new IllegalArgumentException("maxBufferSize cannot be < -1: " + maxBufferSize);
        this.maxCapacity = maxBufferSize == -1 ? Util.maxArraySize : maxBufferSize;
        byteBuf = Unpooled.buffer(bufferSize);
    }

    /** Creates a new Output for writing to a ByteBuffer. */
    public ByteBufOutput (ByteBuf buffer) {
        setBuffer(buffer);
    }

    /** Creates a new Output for writing to a ByteBuffer.
     * @param maxBufferSize If {@link #flush()} does not empty the buffer, the buffer is doubled as needed until it exceeds
     *           maxBufferSize and an exception is thrown. Can be -1 for no maximum. */
    public ByteBufOutput (ByteBuf buffer, int maxBufferSize) {
        setBuffer(buffer, maxBufferSize);
    }

    /** @see Output#Output(OutputStream) */
    public ByteBufOutput (OutputStream outputStream) {
        this(4096, 4096);
        if (outputStream == null) throw new IllegalArgumentException("outputStream cannot be null.");
        this.outputStream = outputStream;
    }

    /** @see Output#Output(OutputStream, int) */
    public ByteBufOutput (OutputStream outputStream, int bufferSize) {
        this(bufferSize, bufferSize);
        if (outputStream == null) throw new IllegalArgumentException("outputStream cannot be null.");
        this.outputStream = outputStream;
    }

    public OutputStream getOutputStream () {
        return outputStream;
    }

    /** Throws {@link UnsupportedOperationException} because this output uses a ByteBuffer, not a byte[].
     * @deprecated
     * @see #getByteBuf() */
    public byte[] getBuffer () {
        throw new UnsupportedOperationException("This buffer does not used a byte[], see #getByteBuffer().");
    }

    /** Throws {@link UnsupportedOperationException} because this output uses a ByteBuffer, not a byte[].
     * @deprecated
     * @see #getByteBuf() */
    public void setBuffer (byte[] buffer) {
        throw new UnsupportedOperationException("This buffer does not used a byte[], see #setByteBuffer(ByteBuffer).");
    }

    /** Throws {@link UnsupportedOperationException} because this output uses a ByteBuffer, not a byte[].
     * @deprecated
     * @see #getByteBuf() */
    public void setBuffer (byte[] buffer, int maxBufferSize) {
        throw new UnsupportedOperationException("This buffer does not used a byte[], see #setByteBuffer(ByteBuffer).");
    }

    /** Allocates a new direct ByteBuffer with the specified bytes and sets it as the new buffer.
     * @see #setBuffer(ByteBuf) */
    public void setBuffer (byte[] bytes, int offset, int count) {
        setBuffer(Unpooled.wrappedBuffer(bytes, offset, count));
    }

    /** Sets a new buffer to write to. The max size is the buffer's length.
     * @see #setBuffer(ByteBuf, int) */
    public void setBuffer (ByteBuf buffer) {
        setBuffer(buffer, buffer.capacity());
    }

    /** Sets a new buffer to write to. The bytes are not copied, the old buffer is discarded and the new buffer used in its place.
     * The position and capacity are set to match the specified buffer. The total is reset. The
     * {@link #setOutputStream(OutputStream) OutputStream} is set to null.
     * @param maxBufferSize If {@link #flush()} does not empty the buffer, the buffer is doubled as needed until it exceeds
     *           maxBufferSize and an exception is thrown. Can be -1 for no maximum. */
    public void setBuffer (ByteBuf buffer, int maxBufferSize) {
        if (buffer == null) throw new IllegalArgumentException("buffer cannot be null.");
        if (maxBufferSize < -1) throw new IllegalArgumentException("maxBufferSize cannot be < -1: " + maxBufferSize);

        initialReaderIndex = buffer.readerIndex();
        initialWriterIndex = buffer.writerIndex();

        this.byteBuf = buffer;
        this.maxCapacity = maxBufferSize == -1 ? Util.maxArraySize : maxBufferSize;
        position = initialWriterIndex;
        total = 0;
        outputStream = null;
    }

    /** Returns the buffer. The bytes between zero and {@link #position()} are the data that has been written. */
    public ByteBuf getByteBuf () {
        return byteBuf;
    }

    public byte[] toBytes () {
        byte[] newBuffer = new byte[position];
        byteBuf.readerIndex(initialReaderIndex);
        byteBuf.getBytes(initialReaderIndex, newBuffer, 0, position);
        return newBuffer;
    }

    public void setPosition (int position) {
        this.position = position;
        this.byteBuf.writerIndex(position);
    }

    public void reset () {
        super.reset();
        byteBuf.setIndex(initialReaderIndex, initialWriterIndex);
    }

    /**
     * Ensures the buffer is large enough to read the specified number of bytes.
     * @return true if the buffer has been resized.
     */
    protected boolean require (int required) throws KryoException {
        if (byteBuf.isWritable(required)) {
            return false;
        }

        int origCode = byteBuf.ensureWritable(required, true);

        if (origCode == 0) {
            // 0 if the buffer has enough writable bytes, and its capacity is unchanged.
            return false;
        }
        else if (origCode == 2) {
            // 2 if the buffer has enough writable bytes, and its capacity has been increased.
            return true;
        }
        else if (origCode == 3) {
            // 3 if the buffer does not have enough bytes, but its capacity has been increased to its maximum.
            return true;
        }
        else {
            // flush and try again.
            flush();
        }

        // only got here because we were unable to resize the buffer! So we flushed it first to try again!
        origCode = byteBuf.ensureWritable(required, true);

        if (origCode == 0) {
            // 0 if the buffer has enough writable bytes, and its capacity is unchanged.
            return false;
        } else if (origCode == 1) {
            // 1 if the buffer does not have enough bytes, and its capacity is unchanged.
            throw new KryoException("Buffer overflow. Max capacity: " + maxCapacity + ", required: " + required);
        }
        else if (origCode == 2) {
            // 2 if the buffer has enough writable bytes, and its capacity has been increased.
            return true;
        }
        else if (origCode == 3) {
            // 3 if the buffer does not have enough bytes, but its capacity has been increased to its maximum.
            return true;
        }
        else {
            throw new KryoException("Unknown buffer resize code: " + origCode);
        }
    }

    // OutputStream:

    public void flush () throws KryoException {
        if (outputStream == null) return;
        try {
            byte[] tmp = new byte[position];
            byteBuf.getBytes(initialReaderIndex, tmp);
            byteBuf.readerIndex(initialReaderIndex);
            outputStream.write(tmp, 0, position);
        } catch (IOException ex) {
            throw new KryoException(ex);
        }
        total += position;
        position = 0;
    }

    public void close () throws KryoException {
        flush();
        if (outputStream != null) {
            try {
                outputStream.close();
            } catch (IOException ignored) {
            }
        }
    }

    public void write (int value) throws KryoException {
        require(1);
        byteBuf.writeByte((byte)value);
        position++;
    }

    public void write (byte[] bytes) throws KryoException {
        if (bytes == null) throw new IllegalArgumentException("bytes cannot be null.");
        writeBytes(bytes, 0, bytes.length);
    }

    public void write (byte[] bytes, int offset, int length) throws KryoException {
        writeBytes(bytes, offset, length);
    }

    // byte:

    public void writeByte (byte value) throws KryoException {
        require(1);
        byteBuf.writeByte(value);
        position++;
    }

    public void writeByte (int value) throws KryoException {
        require(1);
        byteBuf.writeByte((byte)value);
        position++;
    }

    public void writeBytes (byte[] bytes) throws KryoException {
        if (bytes == null) throw new IllegalArgumentException("bytes cannot be null.");
        writeBytes(bytes, 0, bytes.length);
    }

    public void writeBytes (byte[] bytes, int offset, int count) throws KryoException {
        if (bytes == null) throw new IllegalArgumentException("bytes cannot be null.");

        require(count);
        byteBuf.writeBytes(bytes, offset, count);
        position += count;
    }

    // int:

    public void writeInt (int value) throws KryoException {
        require(4);
        position += 4;
        ByteBuf byteBuf = this.byteBuf;
        byteBuf.writeByte((byte)value);
        byteBuf.writeByte((byte)(value >> 8));
        byteBuf.writeByte((byte)(value >> 16));
        byteBuf.writeByte((byte)(value >> 24));
    }

    public int writeVarInt (int value, boolean optimizePositive) throws KryoException {
        if (!optimizePositive) value = (value << 1) ^ (value >> 31);
        if (value >>> 7 == 0) {
            require(1);
            position++;
            byteBuf.writeByte((byte)value);
            return 1;
        }
        if (value >>> 14 == 0) {
            require(2);
            position += 2;
            byteBuf.writeByte((byte)((value & 0x7F) | 0x80));
            byteBuf.writeByte((byte)(value >>> 7));
            return 2;
        }
        if (value >>> 21 == 0) {
            require(3);
            position += 3;
            ByteBuf byteBuf = this.byteBuf;
            byteBuf.writeByte((byte)((value & 0x7F) | 0x80));
            byteBuf.writeByte((byte)(value >>> 7 | 0x80));
            byteBuf.writeByte((byte)(value >>> 14));
            return 3;
        }
        if (value >>> 28 == 0) {
            require(4);
            position += 4;
            ByteBuf byteBuf = this.byteBuf;
            byteBuf.writeByte((byte)((value & 0x7F) | 0x80));
            byteBuf.writeByte((byte)(value >>> 7 | 0x80));
            byteBuf.writeByte((byte)(value >>> 14 | 0x80));
            byteBuf.writeByte((byte)(value >>> 21));
            return 4;
        }
        require(5);
        position += 5;
        ByteBuf byteBuf = this.byteBuf;
        byteBuf.writeByte((byte)((value & 0x7F) | 0x80));
        byteBuf.writeByte((byte)(value >>> 7 | 0x80));
        byteBuf.writeByte((byte)(value >>> 14 | 0x80));
        byteBuf.writeByte((byte)(value >>> 21 | 0x80));
        byteBuf.writeByte((byte)(value >>> 28));
        return 5;
    }

    public int writeVarIntFlag (boolean flag, int value, boolean optimizePositive) throws KryoException {
        if (!optimizePositive) value = (value << 1) ^ (value >> 31);
        int first = (value & 0x3F) | (flag ? 0x80 : 0); // Mask first 6 bits, bit 8 is the flag.
        if (value >>> 6 == 0) {
            require(1);
            byteBuf.writeByte((byte)first);
            position++;
            return 1;
        }
        if (value >>> 13 == 0) {
            require(2);
            position += 2;
            byteBuf.writeByte((byte)(first | 0x40)); // Set bit 7.
            byteBuf.writeByte((byte)(value >>> 6));
            return 2;
        }
        if (value >>> 20 == 0) {
            require(3);
            position += 3;
            ByteBuf byteBuf = this.byteBuf;
            byteBuf.writeByte((byte)(first | 0x40)); // Set bit 7.
            byteBuf.writeByte((byte)((value >>> 6) | 0x80)); // Set bit 8.
            byteBuf.writeByte((byte)(value >>> 13));
            return 3;
        }
        if (value >>> 27 == 0) {
            require(4);
            position += 4;
            ByteBuf byteBuf = this.byteBuf;
            byteBuf.writeByte((byte)(first | 0x40)); // Set bit 7.
            byteBuf.writeByte((byte)((value >>> 6) | 0x80)); // Set bit 8.
            byteBuf.writeByte((byte)((value >>> 13) | 0x80)); // Set bit 8.
            byteBuf.writeByte((byte)(value >>> 20));
            return 4;
        }
        require(5);
        position += 5;
        ByteBuf byteBuf = this.byteBuf;
        byteBuf.writeByte((byte)(first | 0x40)); // Set bit 7.
        byteBuf.writeByte((byte)((value >>> 6) | 0x80)); // Set bit 8.
        byteBuf.writeByte((byte)((value >>> 13) | 0x80)); // Set bit 8.
        byteBuf.writeByte((byte)((value >>> 20) | 0x80)); // Set bit 8.
        byteBuf.writeByte((byte)(value >>> 27));
        return 5;
    }

    // long:

    public void writeLong (long value) throws KryoException {
        require(8);
        position += 8;
        ByteBuf byteBuf = this.byteBuf;
        byteBuf.writeByte((byte)value);
        byteBuf.writeByte((byte)(value >>> 8));
        byteBuf.writeByte((byte)(value >>> 16));
        byteBuf.writeByte((byte)(value >>> 24));
        byteBuf.writeByte((byte)(value >>> 32));
        byteBuf.writeByte((byte)(value >>> 40));
        byteBuf.writeByte((byte)(value >>> 48));
        byteBuf.writeByte((byte)(value >>> 56));
    }

    public int writeVarLong (long value, boolean optimizePositive) throws KryoException {
        if (!optimizePositive) value = (value << 1) ^ (value >> 63);
        if (value >>> 7 == 0) {
            require(1);
            position++;
            byteBuf.writeByte((byte)value);
            return 1;
        }
        if (value >>> 14 == 0) {
            require(2);
            position += 2;
            byteBuf.writeByte((byte)((value & 0x7F) | 0x80));
            byteBuf.writeByte((byte)(value >>> 7));
            return 2;
        }
        if (value >>> 21 == 0) {
            require(3);
            position += 3;
            ByteBuf byteBuf = this.byteBuf;
            byteBuf.writeByte((byte)((value & 0x7F) | 0x80));
            byteBuf.writeByte((byte)(value >>> 7 | 0x80));
            byteBuf.writeByte((byte)(value >>> 14));
            return 3;
        }
        if (value >>> 28 == 0) {
            require(4);
            position += 4;
            ByteBuf byteBuf = this.byteBuf;
            byteBuf.writeByte((byte)((value & 0x7F) | 0x80));
            byteBuf.writeByte((byte)(value >>> 7 | 0x80));
            byteBuf.writeByte((byte)(value >>> 14 | 0x80));
            byteBuf.writeByte((byte)(value >>> 21));
            return 4;
        }
        if (value >>> 35 == 0) {
            require(5);
            position += 5;
            ByteBuf byteBuf = this.byteBuf;
            byteBuf.writeByte((byte)((value & 0x7F) | 0x80));
            byteBuf.writeByte((byte)(value >>> 7 | 0x80));
            byteBuf.writeByte((byte)(value >>> 14 | 0x80));
            byteBuf.writeByte((byte)(value >>> 21 | 0x80));
            byteBuf.writeByte((byte)(value >>> 28));
            return 5;
        }
        if (value >>> 42 == 0) {
            require(6);
            position += 6;
            ByteBuf byteBuf = this.byteBuf;
            byteBuf.writeByte((byte)((value & 0x7F) | 0x80));
            byteBuf.writeByte((byte)(value >>> 7 | 0x80));
            byteBuf.writeByte((byte)(value >>> 14 | 0x80));
            byteBuf.writeByte((byte)(value >>> 21 | 0x80));
            byteBuf.writeByte((byte)(value >>> 28 | 0x80));
            byteBuf.writeByte((byte)(value >>> 35));
            return 6;
        }
        if (value >>> 49 == 0) {
            require(7);
            position += 7;
            ByteBuf byteBuf = this.byteBuf;
            byteBuf.writeByte((byte)((value & 0x7F) | 0x80));
            byteBuf.writeByte((byte)(value >>> 7 | 0x80));
            byteBuf.writeByte((byte)(value >>> 14 | 0x80));
            byteBuf.writeByte((byte)(value >>> 21 | 0x80));
            byteBuf.writeByte((byte)(value >>> 28 | 0x80));
            byteBuf.writeByte((byte)(value >>> 35 | 0x80));
            byteBuf.writeByte((byte)(value >>> 42));
            return 7;
        }
        if (value >>> 56 == 0) {
            require(8);
            position += 8;
            ByteBuf byteBuf = this.byteBuf;
            byteBuf.writeByte((byte)((value & 0x7F) | 0x80));
            byteBuf.writeByte((byte)(value >>> 7 | 0x80));
            byteBuf.writeByte((byte)(value >>> 14 | 0x80));
            byteBuf.writeByte((byte)(value >>> 21 | 0x80));
            byteBuf.writeByte((byte)(value >>> 28 | 0x80));
            byteBuf.writeByte((byte)(value >>> 35 | 0x80));
            byteBuf.writeByte((byte)(value >>> 42 | 0x80));
            byteBuf.writeByte((byte)(value >>> 49));
            return 8;
        }
        require(9);
        position += 9;
        ByteBuf byteBuf = this.byteBuf;
        byteBuf.writeByte((byte)((value & 0x7F) | 0x80));
        byteBuf.writeByte((byte)(value >>> 7 | 0x80));
        byteBuf.writeByte((byte)(value >>> 14 | 0x80));
        byteBuf.writeByte((byte)(value >>> 21 | 0x80));
        byteBuf.writeByte((byte)(value >>> 28 | 0x80));
        byteBuf.writeByte((byte)(value >>> 35 | 0x80));
        byteBuf.writeByte((byte)(value >>> 42 | 0x80));
        byteBuf.writeByte((byte)(value >>> 49 | 0x80));
        byteBuf.writeByte((byte)(value >>> 56));
        return 9;
    }

    // float:

    public void writeFloat (float value) throws KryoException {
        require(4);
        ByteBuf byteBuf = this.byteBuf;
        position += 4;
        int intValue = Float.floatToIntBits(value);
        byteBuf.writeByte((byte)intValue);
        byteBuf.writeByte((byte)(intValue >> 8));
        byteBuf.writeByte((byte)(intValue >> 16));
        byteBuf.writeByte((byte)(intValue >> 24));
    }

    // double:

    public void writeDouble (double value) throws KryoException {
        require(8);
        position += 8;
        ByteBuf byteBuf = this.byteBuf;
        long longValue = Double.doubleToLongBits(value);
        byteBuf.writeByte((byte)longValue);
        byteBuf.writeByte((byte)(longValue >>> 8));
        byteBuf.writeByte((byte)(longValue >>> 16));
        byteBuf.writeByte((byte)(longValue >>> 24));
        byteBuf.writeByte((byte)(longValue >>> 32));
        byteBuf.writeByte((byte)(longValue >>> 40));
        byteBuf.writeByte((byte)(longValue >>> 48));
        byteBuf.writeByte((byte)(longValue >>> 56));
    }

    // short:

    public void writeShort (int value) throws KryoException {
        require(2);
        position += 2;
        byteBuf.writeByte((byte)value);
        byteBuf.writeByte((byte)(value >>> 8));
    }

    // char:

    public void writeChar (char value) throws KryoException {
        require(2);
        position += 2;
        byteBuf.writeByte((byte)value);
        byteBuf.writeByte((byte)(value >>> 8));
    }

    // boolean:

    public void writeBoolean (boolean value) throws KryoException {
        require(1);
        byteBuf.writeByte((byte)(value ? 1 : 0));
        position++;
    }

    // String:

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

        require(charCount); // must be able to write this number of chars

        // Detect ASCII, we only do this for small strings
        boolean isAscii = charCount <= 32;

        if (isAscii) {
            for (int i = 0; i < charCount; i++) {
                if (value.charAt(i) > 127) {
                    isAscii = false;
                    break; // not ascii
                }
            }
        }

        if (isAscii) {
            // this is ascii
            for (int i = 0, n = value.length(); i < n; ++i) {
                byteBuf.writeByte((byte)value.charAt(i));
            }
            position += charCount;

            byteBuf.setByte(position - 1, (byte)(byteBuf.getByte(position - 1) | 0x80));
        } else {
            writeVarIntFlag(true, charCount + 1, true);

            int charIndex = 0;
            // Try to write 7 bit chars.
            ByteBuf byteBuf = this.byteBuf;
            while (true) {
                int c = value.charAt(charIndex);
                if (c > 127) break;
                byteBuf.writeByte((byte)c);
                charIndex++;
                if (charIndex == charCount) {
                    position = byteBuf.writerIndex();
                    return;
                }
            }
            position = byteBuf.writerIndex();

            if (charIndex < charCount) writeUtf8_slow(value, charCount, charIndex);
        }
    }

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

        ByteBuf byteBuf = this.byteBuf;
        for (int i = 0, n = value.length(); i < n; ++i) {
            byteBuf.writeByte((byte)value.charAt(i));
        }

        position += charCount;
        byteBuf.setByte(position - 1, (byte)(byteBuf.getByte(position - 1) | 0x80)); // Bit 8 means end of ASCII.
    }

    private void writeUtf8_slow (String value, int charCount, int charIndex) {
        for (; charIndex < charCount; charIndex++) {
            int c = value.charAt(charIndex);
            if (c <= 0x007F) {
                writeByte((byte)c);
            }
            else if (c > 0x07FF) {
                require(3);
                byteBuf.writeByte((byte)(0xE0 | c >> 12 & 0x0F));
                byteBuf.writeByte((byte)(0x80 | c >> 6 & 0x3F));
                byteBuf.writeByte((byte)(0x80 | c & 0x3F));
                position += 3;
            } else {
                require(2);
                byteBuf.writeByte((byte)(0xC0 | c >> 6 & 0x1F));
                byteBuf.writeByte((byte)(0x80 | c & 0x3F));
                position += 2;
            }
        }
    }

    // Primitive arrays:

    public void writeInts (int[] array, int offset, int count) throws KryoException {
        require(count << 2);

        ByteBuf byteBuf = this.byteBuf;
        for (int n = offset + count; offset < n; offset++) {
            int value = array[offset];
            byteBuf.writeByte((byte)value);
            byteBuf.writeByte((byte)(value >> 8));
            byteBuf.writeByte((byte)(value >> 16));
            byteBuf.writeByte((byte)(value >> 24));
        }
        position = byteBuf.writerIndex();
    }

    public void writeLongs (long[] array, int offset, int count) throws KryoException {
        require(count << 3);

        ByteBuf byteBuf = this.byteBuf;
        for (int n = offset + count; offset < n; offset++) {
            long value = array[offset];
            byteBuf.writeByte((byte)value);
            byteBuf.writeByte((byte)(value >>> 8));
            byteBuf.writeByte((byte)(value >>> 16));
            byteBuf.writeByte((byte)(value >>> 24));
            byteBuf.writeByte((byte)(value >>> 32));
            byteBuf.writeByte((byte)(value >>> 40));
            byteBuf.writeByte((byte)(value >>> 48));
            byteBuf.writeByte((byte)(value >>> 56));
        }
        position = byteBuf.writerIndex();
    }

    public void writeFloats (float[] array, int offset, int count) throws KryoException {
        require(count << 2);

        ByteBuf byteBuf = this.byteBuf;
        for (int n = offset + count; offset < n; offset++) {
            int value = Float.floatToIntBits(array[offset]);
            byteBuf.writeByte((byte)value);
            byteBuf.writeByte((byte)(value >> 8));
            byteBuf.writeByte((byte)(value >> 16));
            byteBuf.writeByte((byte)(value >> 24));
        }
        position = byteBuf.writerIndex();
    }

    public void writeDoubles (double[] array, int offset, int count) throws KryoException {
        require(count << 3);

        ByteBuf byteBuf = this.byteBuf;
        for (int n = offset + count; offset < n; offset++) {
            long value = Double.doubleToLongBits(array[offset]);
            byteBuf.writeByte((byte)value);
            byteBuf.writeByte((byte)(value >>> 8));
            byteBuf.writeByte((byte)(value >>> 16));
            byteBuf.writeByte((byte)(value >>> 24));
            byteBuf.writeByte((byte)(value >>> 32));
            byteBuf.writeByte((byte)(value >>> 40));
            byteBuf.writeByte((byte)(value >>> 48));
            byteBuf.writeByte((byte)(value >>> 56));
        }
        position = byteBuf.writerIndex();
    }

    public void writeShorts (short[] array, int offset, int count) throws KryoException {
        require(count << 1);

        for (int n = offset + count; offset < n; offset++) {
            int value = array[offset];
            byteBuf.writeByte((byte)value);
            byteBuf.writeByte((byte)(value >>> 8));
        }
        position = byteBuf.writerIndex();
    }

    public void writeChars (char[] array, int offset, int count) throws KryoException {
        require(count << 1);

        for (int n = offset + count; offset < n; offset++) {
            int value = array[offset];
            byteBuf.writeByte((byte)value);
            byteBuf.writeByte((byte)(value >>> 8));
        }
        position = byteBuf.writerIndex();
    }

    public void writeBooleans (boolean[] array, int offset, int count) throws KryoException {
        require(count);

        for (int n = offset + count; offset < n; offset++)
            byteBuf.writeByte(array[offset] ? (byte)1 : 0);
        position = byteBuf.writerIndex();
    }
}
