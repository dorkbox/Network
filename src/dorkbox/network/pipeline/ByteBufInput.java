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
import java.io.InputStream;

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

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
 * Modified from KRYO to use ByteBuf.
 */

public
class ByteBufInput extends Input {
    private char[] inputChars = new char[32];
    private ByteBuf byteBuf;
    private int startIndex;

    /**
     * Creates an uninitialized Input. {@link #setBuffer(ByteBuf)} must be called before the Input is used.
     */
    public
    ByteBufInput() {
    }

    public
    ByteBufInput(ByteBuf buffer) {
        setBuffer(buffer);
    }

    public final
    void setBuffer(ByteBuf byteBuf) {
        this.byteBuf = byteBuf;

        if (byteBuf != null) {
            startIndex = byteBuf.readerIndex(); // where the object starts...
        }
        else {
            startIndex = 0;
        }
    }

    public
    ByteBuf getByteBuf() {
        return byteBuf;
    }

    /**
     * Sets a new buffer. The position and total are reset, discarding any buffered bytes.
     */
    @Override
    @Deprecated
    public
    void setBuffer(byte[] bytes) {
        throw new RuntimeException("Cannot access this method!");
    }

    /**
     * Sets a new buffer. The position and total are reset, discarding any buffered bytes.
     */
    @Override
    @Deprecated
    public
    void setBuffer(byte[] bytes, int offset, int count) {
        throw new RuntimeException("Cannot access this method!");
    }

    @Override
    @Deprecated
    public
    byte[] getBuffer() {
        throw new RuntimeException("Cannot access this method!");
    }

    @Override
    @Deprecated
    public
    InputStream getInputStream() {
        throw new RuntimeException("Cannot access this method!");
    }

    /**
     * Sets a new InputStream. The position and total are reset, discarding any buffered bytes.
     *
     * @param inputStream May be null.
     */
    @Override
    @Deprecated
    public
    void setInputStream(InputStream inputStream) {
        throw new RuntimeException("Cannot access this method!");
    }

    /**
     * Returns the number of bytes read.
     */
    @Override
    public
    long total() {
        return byteBuf.readerIndex() - startIndex;
    }

    /**
     * Returns the current position in the buffer.
     */
    @Override
    public
    int position() {
        return byteBuf.readerIndex();
    }

    /**
     * Sets the current position in the buffer.
     */
    @Override
    @Deprecated
    public
    void setPosition(int position) {
        throw new RuntimeException("Cannot access this method!");
    }

    /**
     * Returns the limit for the buffer.
     */
    @Override
    public
    int limit() {
        return byteBuf.writerIndex();
    }

    /**
     * Sets the limit in the buffer.
     */
    @Override
    @Deprecated
    public
    void setLimit(int limit) {
        throw new RuntimeException("Cannot access this method!");
    }

    /**
     * Sets the position and total to zero.
     */
    @Override
    public
    void rewind() {
        byteBuf.readerIndex(startIndex);
    }

    /**
     * Discards the specified number of bytes.
     */
    @Override
    public
    void skip(int count) throws KryoException {
        byteBuf.skipBytes(count);
    }

    /**
     * Fills the buffer with more bytes. Can be overridden to fill the bytes from a source other than the InputStream.
     */
    @Override
    @Deprecated
    protected
    int fill(byte[] buffer, int offset, int count) throws KryoException {
        throw new RuntimeException("Cannot access this method!");
    }


    // InputStream

    /**
     * Reads a single byte as an int from 0 to 255, or -1 if there are no more bytes are available.
     */
    @Override
    public
    int read() throws KryoException {
        return byteBuf.readByte() & 0xFF;
    }

    /**
     * Reads bytes.length bytes or less and writes them to the specified byte[], starting at 0, and returns the number of bytes
     * read.
     */
    @Override
    public
    int read(byte[] bytes) throws KryoException {
        int start = byteBuf.readerIndex();
        byteBuf.readBytes(bytes);
        int end = byteBuf.readerIndex();
        return end - start; // return how many bytes were actually read.
    }

    /**
     * Reads count bytes or less and writes them to the specified byte[], starting at offset, and returns the number of bytes read
     * or -1 if no more bytes are available.
     */
    @Override
    public
    int read(byte[] bytes, int offset, int count) throws KryoException {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes cannot be null.");
        }

        int start = byteBuf.readerIndex();
        byteBuf.readBytes(bytes, offset, count);
        int end = byteBuf.readerIndex();
        return end - start; // return how many bytes were actually read.
    }

    /**
     * Discards the specified number of bytes.
     */
    @Override
    public
    long skip(long count) throws KryoException {
        long remaining = count;
        while (remaining > 0) {
            int skip = Math.max(Integer.MAX_VALUE, (int) remaining);
            skip(skip);
            remaining -= skip;
        }
        return count;
    }

    /**
     * Closes the underlying InputStream, if any.
     */
    @Override
    @Deprecated
    public
    void close() throws KryoException {
        // does nothing.
    }

    // byte

    /**
     * Reads a single byte.
     */
    @Override
    public
    byte readByte() throws KryoException {
        return byteBuf.readByte();
    }

    /**
     * Reads a byte as an int from 0 to 255.
     */
    @Override
    public
    int readByteUnsigned() throws KryoException {
        return byteBuf.readUnsignedByte();
    }

    /**
     * Reads the specified number of bytes into a new byte[].
     */
    @Override
    public
    byte[] readBytes(int length) throws KryoException {
        byte[] bytes = new byte[length];
        readBytes(bytes, 0, length);
        return bytes;
    }

    /**
     * Reads bytes.length bytes and writes them to the specified byte[], starting at index 0.
     */
    @Override
    public
    void readBytes(byte[] bytes) throws KryoException {
        readBytes(bytes, 0, bytes.length);
    }

    /**
     * Reads count bytes and writes them to the specified byte[], starting at offset.
     */
    @Override
    public
    void readBytes(byte[] bytes, int offset, int count) throws KryoException {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes cannot be null.");
        }

        byteBuf.readBytes(bytes, offset, count);
    }

    // int

    /**
     * Reads a 4 byte int.
     */
    @Override
    public
    int readInt() throws KryoException {
        return byteBuf.readInt();
    }


    /**
     * Reads a 1-5 byte int. This stream may consider such a variable length encoding request as a hint. It is not guaranteed that
     * a variable length encoding will be really used. The stream may decide to use native-sized integer representation for
     * efficiency reasons.
     **/
    @Override
    public
    int readInt(boolean optimizePositive) throws KryoException {
        return readVarInt(optimizePositive);
    }

    /**
     * Reads a 1-5 byte int. It is guaranteed that a variable length encoding will be used.
     */
    @Override
    public
    int readVarInt(boolean optimizePositive) throws KryoException {
        ByteBuf buffer = byteBuf;

        int b = buffer.readByte();
        int result = b & 0x7F;
        if ((b & 0x80) != 0) {
            b = buffer.readByte();
            result |= (b & 0x7F) << 7;
            if ((b & 0x80) != 0) {
                b = buffer.readByte();
                result |= (b & 0x7F) << 14;
                if ((b & 0x80) != 0) {
                    b = buffer.readByte();
                    result |= (b & 0x7F) << 21;
                    if ((b & 0x80) != 0) {
                        b = buffer.readByte();
                        result |= (b & 0x7F) << 28;
                    }
                }
            }
        }
        return optimizePositive ? result : result >>> 1 ^ -(result & 1);
    }


    /**
     * Returns true if enough bytes are available to read an int with {@link #readInt(boolean)}.
     */
    @Override
    public
    boolean canReadInt() throws KryoException {
        ByteBuf buffer = byteBuf;
        int limit = buffer.writerIndex();

        if (limit - buffer.readerIndex() >= 5) {
            return true;
        }

        if ((buffer.readByte() & 0x80) == 0) {
            return true;
        }
        if (buffer.readerIndex() == limit) {
            return false;
        }
        if ((buffer.readByte() & 0x80) == 0) {
            return true;
        }
        if (buffer.readerIndex() == limit) {
            return false;
        }
        if ((buffer.readByte() & 0x80) == 0) {
            return true;
        }
        if (buffer.readerIndex() == limit) {
            return false;
        }
        if ((buffer.readByte() & 0x80) == 0) {
            return true;
        }
        if (buffer.readerIndex() == limit) {
            return false;
        }
        return true;
    }

    /**
     * Returns true if enough bytes are available to read a long with {@link #readLong(boolean)}.
     */
    @Override
    public
    boolean canReadLong() throws KryoException {
        ByteBuf buffer = byteBuf;
        int limit = buffer.writerIndex();

        if (limit - buffer.readerIndex() >= 9) {
            return true;
        }

        if ((buffer.readByte() & 0x80) == 0) {
            return true;
        }
        if (buffer.readerIndex() == limit) {
            return false;
        }
        if ((buffer.readByte() & 0x80) == 0) {
            return true;
        }
        if (buffer.readerIndex() == limit) {
            return false;
        }
        if ((buffer.readByte() & 0x80) == 0) {
            return true;
        }
        if (buffer.readerIndex() == limit) {
            return false;
        }
        if ((buffer.readByte() & 0x80) == 0) {
            return true;
        }
        if (buffer.readerIndex() == limit) {
            return false;
        }
        if ((buffer.readByte() & 0x80) == 0) {
            return true;
        }
        if (buffer.readerIndex() == limit) {
            return false;
        }
        if ((buffer.readByte() & 0x80) == 0) {
            return true;
        }
        if (buffer.readerIndex() == limit) {
            return false;
        }
        if ((buffer.readByte() & 0x80) == 0) {
            return true;
        }
        if (buffer.readerIndex() == limit) {
            return false;
        }
        if ((buffer.readByte() & 0x80) == 0) {
            return true;
        }
        if (buffer.readerIndex() == limit) {
            return false;
        }
        return true;
    }

    // string

    /**
     * Reads the length and string of UTF8 characters, or null. This can read strings written by {@link Output#writeString(String)}
     * , {@link Output#writeString(CharSequence)}, and {@link Output#writeAscii(String)}.
     *
     * @return May be null.
     */
    @Override
    public
    String readString() {
        ByteBuf buffer = byteBuf;

        int b = buffer.readByte();
        if ((b & 0x80) == 0) {
            return readAscii(); // ASCII.
        }
        // Null, empty, or UTF8.
        int charCount = readUtf8Length(b);
        switch (charCount) {
            case 0:
                return null;
            case 1:
                return "";
        }
        charCount--;
        if (inputChars.length < charCount) {
            inputChars = new char[charCount];
        }
        readUtf8(charCount);
        return new String(inputChars, 0, charCount);
    }

    private
    int readUtf8Length(int b) {
        int result = b & 0x3F; // Mask all but first 6 bits.
        if ((b & 0x40) != 0) { // Bit 7 means another byte, bit 8 means UTF8.
            ByteBuf buffer = byteBuf;
            b = buffer.readByte();
            result |= (b & 0x7F) << 6;
            if ((b & 0x80) != 0) {
                b = buffer.readByte();
                result |= (b & 0x7F) << 13;
                if ((b & 0x80) != 0) {
                    b = buffer.readByte();
                    result |= (b & 0x7F) << 20;
                    if ((b & 0x80) != 0) {
                        b = buffer.readByte();
                        result |= (b & 0x7F) << 27;
                    }
                }
            }
        }
        return result;
    }

    private
    void readUtf8(int charCount) {
        ByteBuf buffer = byteBuf;
        char[] chars = this.inputChars; // will be the correct size.
        // Try to read 7 bit ASCII chars.
        int charIndex = 0;
        int count = charCount;
        int b;
        while (charIndex < count) {
            b = buffer.readByte();
            if (b < 0) {
                buffer.readerIndex(buffer.readerIndex() - 1);
                break;
            }
            chars[charIndex++] = (char) b;
        }
        // If buffer didn't hold all chars or any were not ASCII, use slow path for remainder.
        if (charIndex < charCount) {
            readUtf8_slow(charCount, charIndex);
        }
    }

    private
    void readUtf8_slow(int charCount, int charIndex) {
        ByteBuf buffer = byteBuf;
        char[] chars = this.inputChars;
        while (charIndex < charCount) {
            int b = buffer.readByte() & 0xFF;
            switch (b >> 4) {
                case 0:
                case 1:
                case 2:
                case 3:
                case 4:
                case 5:
                case 6:
                case 7:
                    chars[charIndex] = (char) b;
                    break;
                case 12:
                case 13:
                    chars[charIndex] = (char) ((b & 0x1F) << 6 | buffer.readByte() & 0x3F);
                    break;
                case 14:
                    chars[charIndex] = (char) ((b & 0x0F) << 12 | (buffer.readByte() & 0x3F) << 6 | buffer.readByte() & 0x3F);
                    break;
            }
            charIndex++;
        }
    }

    private
    String readAscii() {
        ByteBuf buffer = byteBuf;

        int start = buffer.readerIndex() - 1;
        int b;
        do {
            b = buffer.readByte();
        } while ((b & 0x80) == 0);
        int i = buffer.readerIndex() - 1;
        buffer.setByte(i, buffer.getByte(i) & 0x7F);  // Mask end of ascii bit.

        int capp = buffer.readerIndex() - start;

        byte[] ba = new byte[capp];
        buffer.getBytes(start, ba);

        @SuppressWarnings("deprecation")
        String value = new String(ba, 0, 0, capp);

        buffer.setByte(i, buffer.getByte(i) | 0x80);
        return value;
    }


    /**
     * Reads the length and string of UTF8 characters, or null. This can read strings written by {@link Output#writeString(String)}
     * , {@link Output#writeString(CharSequence)}, and {@link Output#writeAscii(String)}.
     *
     * @return May be null.
     */
    @Override
    public
    StringBuilder readStringBuilder() {
        ByteBuf buffer = byteBuf;

        int b = buffer.readByte();
        if ((b & 0x80) == 0) {
            return new StringBuilder(readAscii()); // ASCII.
        }
        // Null, empty, or UTF8.
        int charCount = readUtf8Length(b);
        switch (charCount) {
            case 0:
                return null;
            case 1:
                return new StringBuilder("");
        }
        charCount--;
        if (inputChars.length < charCount) {
            inputChars = new char[charCount];
        }
        readUtf8(charCount);

        StringBuilder builder = new StringBuilder(charCount);
        builder.append(inputChars, 0, charCount);
        return builder;
    }

    // float

    /**
     * Reads a 4 byte float.
     */
    @Override
    public
    float readFloat() throws KryoException {
        return Float.intBitsToFloat(readInt());
    }

    /**
     * Reads a 1-5 byte float with reduced precision.
     */
    @Override
    public
    float readFloat(float precision, boolean optimizePositive) throws KryoException {
        return readInt(optimizePositive) / precision;
    }

    // short

    /**
     * Reads a 2 byte short.
     */
    @Override
    public
    short readShort() throws KryoException {
        return byteBuf.readShort();
    }

    /**
     * Reads a 2 byte short as an int from 0 to 65535.
     */
    @Override
    public
    int readShortUnsigned() throws KryoException {
        return byteBuf.readUnsignedShort();
    }

    // long

    /**
     * Reads an 8 byte long.
     */
    @Override
    public
    long readLong() throws KryoException {
        return byteBuf.readLong();
    }

    /**
     * Reads a 1-9 byte long.
     */
    @Override
    public
    long readLong(boolean optimizePositive) throws KryoException {
        ByteBuf buffer = byteBuf;
        int b = buffer.readByte();
        long result = b & 0x7F;
        if ((b & 0x80) != 0) {
            b = buffer.readByte();
            result |= (b & 0x7F) << 7;
            if ((b & 0x80) != 0) {
                b = buffer.readByte();
                result |= (b & 0x7F) << 14;
                if ((b & 0x80) != 0) {
                    b = buffer.readByte();
                    result |= (b & 0x7F) << 21;
                    if ((b & 0x80) != 0) {
                        b = buffer.readByte();
                        result |= (long) (b & 0x7F) << 28;
                        if ((b & 0x80) != 0) {
                            b = buffer.readByte();
                            result |= (long) (b & 0x7F) << 35;
                            if ((b & 0x80) != 0) {
                                b = buffer.readByte();
                                result |= (long) (b & 0x7F) << 42;
                                if ((b & 0x80) != 0) {
                                    b = buffer.readByte();
                                    result |= (long) (b & 0x7F) << 49;
                                    if ((b & 0x80) != 0) {
                                        b = buffer.readByte();
                                        result |= (long) b << 56;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!optimizePositive) {
            result = result >>> 1 ^ -(result & 1);
        }
        return result;
    }


    // boolean

    /**
     * Reads a 1 byte boolean.
     */
    @Override
    public
    boolean readBoolean() throws KryoException {
        return byteBuf.readBoolean();
    }

    // char

    /**
     * Reads a 2 byte char.
     */
    @Override
    public
    char readChar() throws KryoException {
        return byteBuf.readChar();
    }

    // double

    /**
     * Reads an 8 bytes double.
     */
    @Override
    public
    double readDouble() throws KryoException {
        return Double.longBitsToDouble(readLong());
    }

    /**
     * Reads a 1-9 byte double with reduced precision.
     */
    @Override
    public
    double readDouble(double precision, boolean optimizePositive) throws KryoException {
        return readLong(optimizePositive) / precision;
    }
}
