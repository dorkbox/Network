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

import java.io.DataInput;
import java.io.IOException;
import java.io.InputStream;

import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.util.Util;

import io.netty.buffer.ByteBuf;

/**
 * An {@link InputStream} which reads data from a {@link ByteBuf}.
 * <p/>
 * A read operation against this stream will occur at the {@code readerIndex}
 * of its underlying buffer and the {@code readerIndex} will increase during
 * the read operation.
 * <p/>
 * This stream implements {@link DataInput} for your convenience.
 * The endianness of the stream is not always big endian but depends on
 * the endianness of the underlying buffer.
 * <p/>
 * Utility methods are provided for efficiently reading primitive types and strings.
 * <p/>
 * <p/>
 * <p/>
 * <p/>
 * Modified from KRYO ByteBufferInput to use ByteBuf instead of ByteBuffer.
 */

public
class AeronInput extends Input {
    private DirectBuffer internalBuffer;

    /** Creates an uninitialized Input, {@link #setBuffer(DirectBuffer, int, int)} must be called before the Input is used. */
    public
    AeronInput() {
    }

    /** Creates a new Input for reading from a byteArray which is filled with the specified bytes. */
    public
    AeronInput(byte[] bytes) {
        this(bytes, 0, bytes.length);
    }

    /**
     * Creates a new Input for reading from a {@link DirectBuffer} which is filled with the specified bytes.
     * @see #setBuffer(byte[], int, int)
     */
    public
    AeronInput(byte[] bytes, int offset, int length) {
        this();
        setBuffer(bytes, offset, length);
    }

    /**
     *  Creates a new Input for reading from a {@link DirectBuffer} which is filled with the specified bytes.
     * @see #setBuffer(byte[], int, int)
     */
    public
    AeronInput(final DirectBuffer buffer) {
        this(buffer, 0, buffer.capacity());
    }

    /**
     *  Creates a new Input for reading from a {@link DirectBuffer} which is filled with the specified bytes.
     * @see #setBuffer(byte[], int, int)
     */
    public
    AeronInput(DirectBuffer buffer, int offset, int length) {
        this();
        setBuffer(buffer, offset, length);
    }

    /** Returns the buffer. The bytes between zero and {@link #position()} are the data that has been read. */
    public DirectBuffer getInternalBuffer() {
        return internalBuffer;
    }

    /**
     *  Throws {@link UnsupportedOperationException} because this input uses a DirectBuffer, not a byte[].
     * @deprecated
     */
    @Override
    @Deprecated
    public byte[] getBuffer () {
        throw new UnsupportedOperationException("This input does not use a byte[], see #getInternalBuffer().");
    }

    /**
     * Throws {@link UnsupportedOperationException} because this input uses a DirectBuffer, not a byte[].
     * @deprecated
     * @see #setBuffer(DirectBuffer, int, int)
     */
    @Override
    @Deprecated
    public void setBuffer (byte[] bytes) {
        setBuffer(bytes, 0, bytes.length);
    }

    /**
     * Throws {@link UnsupportedOperationException} because this input uses a DirectBuffer, not a byte[].
     */
    @Override
    public void setBuffer (byte[] bytes, int offset, int count) {
        internalBuffer = new UnsafeBuffer(bytes, offset, count);
        position = 0;
        limit = count;
        capacity = count;
    }

    @Override
    public void setInputStream (InputStream inputStream) {
        throw new UnsupportedOperationException("This input does not use a inputStream, see #setByteBuf().");
    }

    /**
     * Throws {@link UnsupportedOperationException} because this input uses a ByteBuffer, not a byte[].
     */
    public void setBuffer (DirectBuffer buffer, int offset, int length) {
        this.internalBuffer = buffer;
        this.position = offset;
        this.limit = length;
        this.capacity = buffer.capacity();
    }

    @Override
    public void reset () {
        super.reset();
    }

    @Override
    protected int require (int required) throws KryoException {
        return 0;
    }

    /**
     * Fills the buffer with at least the number of bytes specified, if possible.
     * @param optional Must be > 0.
     * @return the number of bytes remaining, always the value of optional, as this is not used for anything
     */
    @Override
    protected int optional (int optional) throws KryoException {
        return optional;
    }

    /**
     * Returns true if the {@link #limit()} has been reached and {@link #fill(byte[], int, int)} is unable to provide more bytes.
     */
    @Override
    public boolean end () {
        return position >= limit;
    }

    // InputStream:

    @Override
    public int available () throws IOException {
        return limit - position;
    }


    // InputStream:

    @Override
    public int read () throws KryoException {
        int result = internalBuffer.getByte(position) & 0xFF;
        position++;
        return result;
    }

    @Override
    public int read (byte[] bytes) throws KryoException {
        return read(bytes, 0, bytes.length);
    }

    @Override
    public int read (byte[] bytes, int offset, int count) throws KryoException {
        if (position + count > limit) {
            count = limit - position;
        }

        internalBuffer.getBytes(position, bytes, offset, count);
        position += count;
        return count;
    }

    @Override
    public void setPosition (int position) {
        this.position = position;
    }

    @Override
    public void setLimit (int limit) {
        this.limit = limit;
    }

    @Override
    public void skip (int count) throws KryoException {
        super.skip(count);
    }

    @Override
    public long skip (long count) throws KryoException {
        long remaining = count;
        while (remaining > 0) {
            int skip = (int)Math.min(Util.maxArraySize, remaining);
            skip(skip);
            remaining -= skip;
        }
        return count;
    }

    @Override
    public void close () throws KryoException {
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (IOException ignored) {
            }
        }
    }

    // byte:

    @Override
    public byte readByte () throws KryoException {
        byte result = internalBuffer.getByte(position);
        position++;
        return result;
    }

    @Override
    public int readByteUnsigned () throws KryoException {
        int result = internalBuffer.getByte(position) & 0xFF;
        position++;
        return result;
    }

    @Override
    public byte[] readBytes (int length) throws KryoException {
        byte[] bytes = new byte[length];
        readBytes(bytes, 0, length);
        return bytes;
    }

    @Override
    public void readBytes (byte[] bytes, int offset, int count) throws KryoException {
        internalBuffer.getBytes(position, bytes, offset, count);
        position += count;
    }

    // int:

    @Override
    public int readInt () throws KryoException {
        int result = internalBuffer.getInt(position);
        position += 4;
        return result;
    }

    @Override
    public int readVarInt (boolean optimizePositive) throws KryoException {
        int b = internalBuffer.getByte(position++);
        int result = b & 0x7F;

        if ((b & 0x80) != 0) {
            DirectBuffer byteBuf = this.internalBuffer;
            b = byteBuf.getByte(position++);
            result |= (b & 0x7F) << 7;
            if ((b & 0x80) != 0) {
                b = byteBuf.getByte(position++);
                result |= (b & 0x7F) << 14;
                if ((b & 0x80) != 0) {
                    b = byteBuf.getByte(position++);
                    result |= (b & 0x7F) << 21;
                    if ((b & 0x80) != 0) {
                        b = byteBuf.getByte(position++);
                        result |= (b & 0x7F) << 28;
                    }
                }
            }
        }
        return optimizePositive ? result : ((result >>> 1) ^ -(result & 1));
    }

    @Override
    public boolean canReadVarInt () throws KryoException {
        if (limit - position >= 5) return true;
        if (limit <= position) return false;

        int p = position, limit = this.limit;
        DirectBuffer byteBuf = this.internalBuffer;

        if ((byteBuf.getByte(p++) & 0x80) == 0) return true;
        if (p == limit) return false;
        if ((byteBuf.getByte(p++) & 0x80) == 0) return true;
        if (p == limit) return false;
        if ((byteBuf.getByte(p++) & 0x80) == 0) return true;
        if (p == limit) return false;
        if ((byteBuf.getByte(p++) & 0x80) == 0) return true;
        if (p == limit) return false;
        return true;
    }

    /** Reads the boolean part of a varint flag. The position is not advanced, {@link #readVarIntFlag(boolean)} should be used to
     * advance the position. */
    @Override
    public boolean readVarIntFlag () {
        return (internalBuffer.getByte(position) & 0x80) != 0;
    }

    /** Reads the 1-5 byte int part of a varint flag. The position is advanced so if the boolean part is needed it should be read
     * first with {@link #readVarIntFlag()}. */
    @Override
    public int readVarIntFlag (boolean optimizePositive) {
        int b = internalBuffer.getByte(position++);
        int result = b & 0x3F; // Mask first 6 bits.
        if ((b & 0x40) != 0) { // Bit 7 means another byte, bit 8 means UTF8.
            DirectBuffer byteBuf = this.internalBuffer;
            b = byteBuf.getByte(position++);
            result |= (b & 0x7F) << 6;
            if ((b & 0x80) != 0) {
                b = byteBuf.getByte(position++);
                result |= (b & 0x7F) << 13;
                if ((b & 0x80) != 0) {
                    b = byteBuf.getByte(position++);
                    result |= (b & 0x7F) << 20;
                    if ((b & 0x80) != 0) {
                        b = byteBuf.getByte(position++);
                        result |= (b & 0x7F) << 27;
                    }
                }
            }
        }
        return optimizePositive ? result : ((result >>> 1) ^ -(result & 1));
    }

    // long:

    @Override
    public long readLong () throws KryoException {
        long result = internalBuffer.getLong(position);
        position += 8;
        return result;
    }

    @Override
    public long readVarLong (boolean optimizePositive) throws KryoException {
        int b = internalBuffer.getByte(position++);
        long result = b & 0x7F;
        if ((b & 0x80) != 0) {
            DirectBuffer byteBuf = this.internalBuffer;
            b = byteBuf.getByte(position++);
            result |= (b & 0x7F) << 7;
            if ((b & 0x80) != 0) {
                b = byteBuf.getByte(position++);
                result |= (b & 0x7F) << 14;
                if ((b & 0x80) != 0) {
                    b = byteBuf.getByte(position++);
                    result |= (b & 0x7F) << 21;
                    if ((b & 0x80) != 0) {
                        b = byteBuf.getByte(position++);
                        result |= (long)(b & 0x7F) << 28;
                        if ((b & 0x80) != 0) {
                            b = byteBuf.getByte(position++);
                            result |= (long)(b & 0x7F) << 35;
                            if ((b & 0x80) != 0) {
                                b = byteBuf.getByte(position++);
                                result |= (long)(b & 0x7F) << 42;
                                if ((b & 0x80) != 0) {
                                    b = byteBuf.getByte(position++);
                                    result |= (long)(b & 0x7F) << 49;
                                    if ((b & 0x80) != 0) {
                                        b = byteBuf.getByte(position++);
                                        result |= (long)b << 56;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return optimizePositive ? result : ((result >>> 1) ^ -(result & 1));
    }

    @Override
    public boolean canReadVarLong () throws KryoException {
        if (limit - position >= 9) return true;
        int p = position, limit = this.limit;
        DirectBuffer byteBuf = this.internalBuffer;
        if ((byteBuf.getByte(p++) & 0x80) == 0) return true;
        if (p == limit) return false;
        if ((byteBuf.getByte(p++) & 0x80) == 0) return true;
        if (p == limit) return false;
        if ((byteBuf.getByte(p++) & 0x80) == 0) return true;
        if (p == limit) return false;
        if ((byteBuf.getByte(p++) & 0x80) == 0) return true;
        if (p == limit) return false;
        if ((byteBuf.getByte(p++) & 0x80) == 0) return true;
        if (p == limit) return false;
        if ((byteBuf.getByte(p++) & 0x80) == 0) return true;
        if (p == limit) return false;
        if ((byteBuf.getByte(p++) & 0x80) == 0) return true;
        if (p == limit) return false;
        if ((byteBuf.getByte(p++) & 0x80) == 0) return true;
        if (p == limit) return false;
        return true;
    }

    // float:

    @Override
    public float readFloat () throws KryoException {
        float result = internalBuffer.getFloat(position);
        position += 4;
        return result;
    }

    // double:

    @Override
    public double readDouble () throws KryoException {
        double result = internalBuffer.getDouble(position);
        position += 8;
        return result;
    }

    // boolean:

    @Override
    public boolean readBoolean () throws KryoException {
        return internalBuffer.getByte(position++) == 1;
    }

    // short:

    @Override
    public short readShort () throws KryoException {
        short result = internalBuffer.getShort(position);
        position += 2;
        return result;
    }

    @Override
    public int readShortUnsigned () throws KryoException {
        int result = internalBuffer.getShort(position) & 0xFF;
        position += 2;
        return result;
    }

    // char:

    @Override
    public char readChar () throws KryoException {
        char result = internalBuffer.getChar(position);
        position += 2;
        return result;
    }

    // String:

    @Override
    public String readString () {
        if (!readVarIntFlag()) return readAsciiString(); // ASCII.
        // Null, empty, or UTF8.
        int charCount = readVarIntFlag(true);
        switch (charCount) {
            case 0:
                return null;
            case 1:
                return "";
        }
        charCount--; // make count adjustment
        readUtf8Chars(charCount);
        return new String(chars, 0, charCount);
    }

    @Override
    public StringBuilder readStringBuilder () {
        if (!readVarIntFlag()) return new StringBuilder(readAsciiString()); // ASCII.
        // Null, empty, or UTF8.
        int charCount = readVarIntFlag(true);
        switch (charCount) {
            case 0:
                return null;
            case 1:
                return new StringBuilder("");
        }
        charCount--;
        readUtf8Chars(charCount);
        StringBuilder builder = new StringBuilder(charCount);
        builder.append(chars, 0, charCount);
        return builder;
    }

    private void readUtf8Chars (int charCount) {
        if (chars.length < charCount) chars = new char[charCount];

        char[] chars = this.chars;
        // Try to read 7 bit ASCII chars.
        DirectBuffer byteBuf = this.internalBuffer;
        int charIndex = 0;
        while (charIndex < charCount) {
            int b = byteBuf.getByte(position);
            if (b < 0) break;
            position++; // only increment read position if the char was 7bit
            chars[charIndex++] = (char)b;
        }

        // If buffer didn't hold all chars or any were not ASCII, use slow path for remainder.
        if (charIndex < charCount) readUtf8Chars_slow(charCount, charIndex);
    }

    private String readAsciiString () {
        char[] chars = this.chars;
        DirectBuffer byteBuf = this.internalBuffer;
        int charCount = 0;
        for (int n = Math.min(chars.length, limit - position); charCount < n; charCount++) {
            int b = byteBuf.getByte(position++);
            if ((b & 0x80) == 0x80) {
                chars[charCount] = (char)(b & 0x7F);
                return new String(chars, 0, charCount + 1);
            }
            chars[charCount] = (char)b;
        }
        return readAscii_slow(charCount);
    }

    private String readAscii_slow (int charCount) {
        char[] chars = this.chars;
        DirectBuffer byteBuf = this.internalBuffer;
        while (true) {
            int b = byteBuf.getByte(position++);
            if (charCount == chars.length) {
                char[] newChars = new char[charCount * 2];
                System.arraycopy(chars, 0, newChars, 0, charCount);
                chars = newChars;
                this.chars = newChars;
            }
            if ((b & 0x80) == 0x80) {
                chars[charCount] = (char)(b & 0x7F);
                return new String(chars, 0, charCount + 1);
            }
            chars[charCount++] = (char)b;
        }
    }

    private void readUtf8Chars_slow (int charCount, int charIndex) {
        DirectBuffer byteBuf = this.internalBuffer;
        char[] chars = this.chars;
        while (charIndex < charCount) {
            int b = byteBuf.getByte(position++) & 0xFF;
            switch (b >> 4) {
                case 0:
                case 1:
                case 2:
                case 3:
                case 4:
                case 5:
                case 6:
                case 7:
                    chars[charIndex] = (char)b;
                    break;
                case 12:
                case 13:
                    chars[charIndex] = (char)((b & 0x1F) << 6 | byteBuf.getByte(position++) & 0x3F);
                    break;
                case 14:
                    int b2 = byteBuf.getByte(position++);
                    int b3 = byteBuf.getByte(position++);
                    chars[charIndex] = (char)((b & 0x0F) << 12 | (b2 & 0x3F) << 6 | b3 & 0x3F);
                    break;
            }
            charIndex++;
        }
    }

    // Primitive arrays:

    @Override
    public int[] readInts (int length) throws KryoException {
        int[] array = new int[length];
        for (int i = 0; i < length; i++) {
            array[i] = readInt();
        }
        return array;
    }

    @Override
    public long[] readLongs (int length) throws KryoException {
        long[] array = new long[length];
        for (int i = 0; i < length; i++) {
            array[i] = readLong();
        }
        return array;
    }

    @Override
    public float[] readFloats (int length) throws KryoException {
        float[] array = new float[length];
        for (int i = 0; i < length; i++) {
            array[i] = readFloat();
        }
        return array;
    }

    @Override
    public double[] readDoubles (int length) throws KryoException {
        double[] array = new double[length];
        for (int i = 0; i < length; i++) {
            array[i] = readDouble();
        }
        return array;
    }

    @Override
    public short[] readShorts (int length) throws KryoException {
        short[] array = new short[length];
        for (int i = 0; i < length; i++) {
            array[i] = readShort();
        }
        return array;
    }

    @Override
    public char[] readChars (int length) throws KryoException {
        char[] array = new char[length];
        for (int i = 0; i < length; i++) {
            array[i] = readChar();
        }
        return array;
    }

    @Override
    public boolean[] readBooleans (int length) throws KryoException {
        boolean[] array = new boolean[length];
        for (int i = 0; i < length; i++) {
            array[i] = readBoolean();
        }
        return array;
    }
}
