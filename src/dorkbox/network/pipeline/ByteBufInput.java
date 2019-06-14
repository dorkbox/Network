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

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.util.Util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

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
class ByteBufInput extends Input {
    private ByteBuf byteBuf;
    private int initialReaderIndex = 0;
    private int initialWriterIndex = 0;

    private byte[] tempBuffer;

    /** Creates an uninitialized Input, {@link #setBuffer(ByteBuf)} must be called before the Input is used. */
    public ByteBufInput () {
    }

    /** Creates a new Input for reading from a direct {@link ByteBuf}.
     * @param bufferSize The size of the buffer. An exception is thrown if more bytes than this are read and
     *           {@link #fill(ByteBuf, int, int)} does not supply more bytes. */
    public ByteBufInput (int bufferSize) {
        this.capacity = bufferSize;
        byteBuf = Unpooled.buffer(bufferSize);
    }

    /** Creates a new Input for reading from a {@link ByteBuf} which is filled with the specified bytes. */
    public ByteBufInput (byte[] bytes) {
        this(bytes, 0, bytes.length);
    }

    /** Creates a new Input for reading from a {@link ByteBuf} which is filled with the specified bytes.
     * @see #setBuffer(byte[], int, int) */
    public ByteBufInput (byte[] bytes, int offset, int count) {
        if (bytes == null) throw new IllegalArgumentException("bytes cannot be null.");

        setBuffer(Unpooled.wrappedBuffer(bytes, offset, count));
    }

    /** Creates a new Input for reading from a ByteBuffer. */
    public ByteBufInput (ByteBuf buffer) {
        setBuffer(buffer);
    }

    /** @see Input#Input(InputStream) */
    public ByteBufInput (InputStream inputStream) {
        this(4096);
        if (inputStream == null) throw new IllegalArgumentException("inputStream cannot be null.");
        this.inputStream = inputStream;
    }

    /** @see Input#Input(InputStream, int) */
    public ByteBufInput (InputStream inputStream, int bufferSize) {
        this(bufferSize);
        if (inputStream == null) throw new IllegalArgumentException("inputStream cannot be null.");
        this.inputStream = inputStream;
    }

    /** Throws {@link UnsupportedOperationException} because this input uses a ByteBuffer, not a byte[].
     * @deprecated
     * @see #getByteBuf() */
    @Deprecated
    public byte[] getBuffer () {
        throw new UnsupportedOperationException("This input does not used a byte[], see #getByteBuf().");
    }

    /** Throws {@link UnsupportedOperationException} because this input uses a ByteBuffer, not a byte[].
     * @deprecated
     * @see #setBuffer(ByteBuf) */
    @Deprecated
    public void setBuffer (byte[] bytes) {
        throw new UnsupportedOperationException("This input does not used a byte[], see #setByteBuf(ByteBuf).");
    }

    /** Throws {@link UnsupportedOperationException} because this input uses a ByteBuffer, not a byte[].
     * @deprecated
     * @see #setBuffer(ByteBuf) */
    @Deprecated
    public void setBuffer (byte[] bytes, int offset, int count) {
        throw new UnsupportedOperationException("This input does not used a byte[], see #setByteBuf().");
    }

    /** Sets a new buffer to read from. The bytes are not copied, the old buffer is discarded and the new buffer used in its place.
     * The position, limit, and capacity are set to match the specified buffer. The total is reset. The
     * {@link #setInputStream(InputStream) InputStream} is set to null. */
    public void setBuffer(ByteBuf buffer) {
        if (buffer == null) throw new IllegalArgumentException("buffer cannot be null.");
        byteBuf = buffer;

        initialReaderIndex = byteBuf.readerIndex(); // where the object starts...
        initialWriterIndex = byteBuf.writerIndex(); // where the object ends...

        position = initialReaderIndex;
        limit = initialWriterIndex;
        capacity = buffer.capacity();
        total = 0;
        inputStream = null;
    }

    public ByteBuf getByteBuf () {
        return byteBuf;
    }

    public void setInputStream (InputStream inputStream) {
        this.inputStream = inputStream;
        limit = 0;
        reset();
    }

    public void reset () {
        super.reset();

        byteBuf.setIndex(initialReaderIndex, initialWriterIndex);
    }

    /** Fills the buffer with more bytes. The default implementation reads from the {@link #getInputStream() InputStream}, if set.
     * Can be overridden to fill the bytes from another source. */
    protected int fill (ByteBuf buffer, int offset, int count) throws KryoException {
        if (inputStream == null) return -1;
        try {
            if (tempBuffer == null) tempBuffer = new byte[2048];
            buffer.readerIndex(offset);
            int total = 0;
            while (count > 0) {
                int read = inputStream.read(tempBuffer, 0, Math.min(tempBuffer.length, count));
                if (read == -1) {
                    if (total == 0) return -1;
                    break;
                }
                buffer.writeBytes(tempBuffer, 0, read);
                count -= read;
                total += read;
            }
            buffer.readerIndex(offset);
            return total;
        } catch (IOException ex) {
            throw new KryoException(ex);
        }
    }

    protected int require (int required) throws KryoException {
        int remaining = limit - position;
        if (remaining >= required) return remaining;
        if (required > capacity) throw new KryoException("Buffer too small: capacity: " + capacity + ", required: " + required);

        int count;
        // Try to fill the buffer.
        if (remaining > 0) {
            count = fill(byteBuf, limit, capacity - limit);
            if (count == -1) throw new KryoException("Buffer underflow.");
            remaining += count;
            if (remaining >= required) {
                limit += count;
                return remaining;
            }
        }

        // Compact. Position after compaction can be non-zero.
        // CANNOT COMPACT A BYTEBUF
        // byteBuffer.position(position);
        // byteBuffer.compact();
        // total += position;
        // position = 0;
        //
        // while (true) {
        //     count = fill(byteBuffer, remaining, capacity - remaining);
        //     if (count == -1) {
        //         if (remaining >= required) break;
        //         throw new KryoException("Buffer underflow.");
        //     }
        //     remaining += count;
        //     if (remaining >= required) break; // Enough has been read.
        // }
        // limit = remaining;
        // byteBuffer.position(0);
        return remaining;
    }

    /** Fills the buffer with at least the number of bytes specified, if possible.
     * @param optional Must be > 0.
     * @return the number of bytes remaining, but not more than optional, or -1 if {@link #fill(ByteBuf, int, int)} is unable to
     *         provide more bytes. */
    protected int optional (int optional) throws KryoException {
        int remaining = limit - position;
        if (remaining >= optional) return optional;
        optional = Math.min(optional, capacity);

        // Try to fill the buffer.
        int count = fill(byteBuf, limit, capacity - limit);
        if (count == -1) return remaining == 0 ? -1 : Math.min(remaining, optional);
        remaining += count;
        if (remaining >= optional) {
            limit += count;
            return optional;
        }

        // Compact.
        // CANNOT COMPACT A BYTEBUF
        // byteBuffer.compact();
        // total += position;
        // position = 0;
        //
        // while (true) {
        //     count = fill(byteBuffer, remaining, capacity - remaining);
        //     if (count == -1) break;
        //     remaining += count;
        //     if (remaining >= optional) break; // Enough has been read.
        // }
        // limit = remaining;
        // byteBuffer.position(position);
        return remaining == 0 ? -1 : Math.min(remaining, optional);
    }

    // InputStream:

    public int read () throws KryoException {
        if (optional(1) <= 0) return -1;
        position++;
        return byteBuf.readByte() & 0xFF;
    }

    public int read (byte[] bytes) throws KryoException {
        return read(bytes, 0, bytes.length);
    }

    public int read (byte[] bytes, int offset, int count) throws KryoException {
        if (bytes == null) throw new IllegalArgumentException("bytes cannot be null.");
        int startingCount = count;
        int copyCount = Math.min(limit - position, count);
        while (true) {
            byteBuf.getBytes(offset, bytes, 0, copyCount);
            position += copyCount;
            count -= copyCount;
            if (count == 0) break;
            offset += copyCount;
            copyCount = optional(count);
            if (copyCount == -1) {
                // End of data.
                if (startingCount == count) return -1;
                break;
            }
            if (position == limit) break;
        }
        return startingCount - count;
    }

    public void setPosition (int position) {
        this.position = position;
        byteBuf.readerIndex(position);
    }

    public void setLimit (int limit) {
        this.limit = limit;
        byteBuf.writerIndex(limit);
    }

    public void skip (int count) throws KryoException {
        super.skip(count);
        byteBuf.readerIndex(position);
    }

    public long skip (long count) throws KryoException {
        long remaining = count;
        while (remaining > 0) {
            int skip = (int)Math.min(Util.maxArraySize, remaining);
            skip(skip);
            remaining -= skip;
        }
        return count;
    }

    public void close () throws KryoException {
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (IOException ignored) {
            }
        }
    }

    // byte:

    public byte readByte () throws KryoException {
        if (position == limit) require(1);
        position++;
        return byteBuf.readByte();
    }

    public int readByteUnsigned () throws KryoException {
        if (position == limit) require(1);
        position++;
        return byteBuf.readByte() & 0xFF;
    }

    public byte[] readBytes (int length) throws KryoException {
        byte[] bytes = new byte[length];
        readBytes(bytes, 0, length);
        return bytes;
    }

    public void readBytes (byte[] bytes, int offset, int count) throws KryoException {
        if (bytes == null) throw new IllegalArgumentException("bytes cannot be null.");
        int copyCount = Math.min(limit - position, count);
        while (true) {
            byteBuf.readBytes(bytes, offset, copyCount);
            position += copyCount;
            count -= copyCount;
            if (count == 0) break;
            offset += copyCount;
            copyCount = Math.min(count, capacity);
            require(copyCount);
        }
    }

    // int:

    public int readInt () throws KryoException {
        require(4);
        position += 4;
        ByteBuf byteBuf = this.byteBuf;
        return byteBuf.readByte() & 0xFF //
               | (byteBuf.readByte() & 0xFF) << 8 //
               | (byteBuf.readByte() & 0xFF) << 16 //
               | (byteBuf.readByte() & 0xFF) << 24;
    }

    public int readVarInt (boolean optimizePositive) throws KryoException {
        if (require(1) < 5) return readVarInt_slow(optimizePositive);
        int b = byteBuf.readByte();
        int result = b & 0x7F;
        if ((b & 0x80) != 0) {
            ByteBuf byteBuf = this.byteBuf;
            b = byteBuf.readByte();
            result |= (b & 0x7F) << 7;
            if ((b & 0x80) != 0) {
                b = byteBuf.readByte();
                result |= (b & 0x7F) << 14;
                if ((b & 0x80) != 0) {
                    b = byteBuf.readByte();
                    result |= (b & 0x7F) << 21;
                    if ((b & 0x80) != 0) {
                        b = byteBuf.readByte();
                        result |= (b & 0x7F) << 28;
                    }
                }
            }
        }
        position = byteBuf.readerIndex();
        return optimizePositive ? result : ((result >>> 1) ^ -(result & 1));
    }

    private int readVarInt_slow (boolean optimizePositive) {
        // The buffer is guaranteed to have at least 1 byte.
        position++;
        int b = byteBuf.readByte();
        int result = b & 0x7F;
        if ((b & 0x80) != 0) {
            if (position == limit) require(1);
            ByteBuf byteBuf = this.byteBuf;
            position++;
            b = byteBuf.readByte();
            result |= (b & 0x7F) << 7;
            if ((b & 0x80) != 0) {
                if (position == limit) require(1);
                position++;
                b = byteBuf.readByte();
                result |= (b & 0x7F) << 14;
                if ((b & 0x80) != 0) {
                    if (position == limit) require(1);
                    position++;
                    b = byteBuf.readByte();
                    result |= (b & 0x7F) << 21;
                    if ((b & 0x80) != 0) {
                        if (position == limit) require(1);
                        position++;
                        b = byteBuf.readByte();
                        result |= (b & 0x7F) << 28;
                    }
                }
            }
        }
        return optimizePositive ? result : ((result >>> 1) ^ -(result & 1));
    }

    public boolean canReadVarInt () throws KryoException {
        if (limit - position >= 5) return true;
        if (optional(5) <= 0) return false;
        int p = position, limit = this.limit;
        ByteBuf byteBuf = this.byteBuf;
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
    public boolean readVarIntFlag () {
        if (position == limit) require(1);
        return (byteBuf.getByte(position) & 0x80) != 0;
    }

    /** Reads the 1-5 byte int part of a varint flag. The position is advanced so if the boolean part is needed it should be read
     * first with {@link #readVarIntFlag()}. */
    public int readVarIntFlag (boolean optimizePositive) {
        if (require(1) < 5) return readVarIntFlag_slow(optimizePositive);
        int b = byteBuf.readByte();
        int result = b & 0x3F; // Mask first 6 bits.
        if ((b & 0x40) != 0) { // Bit 7 means another byte, bit 8 means UTF8.
            ByteBuf byteBuf = this.byteBuf;
            b = byteBuf.readByte();
            result |= (b & 0x7F) << 6;
            if ((b & 0x80) != 0) {
                b = byteBuf.readByte();
                result |= (b & 0x7F) << 13;
                if ((b & 0x80) != 0) {
                    b = byteBuf.readByte();
                    result |= (b & 0x7F) << 20;
                    if ((b & 0x80) != 0) {
                        b = byteBuf.readByte();
                        result |= (b & 0x7F) << 27;
                    }
                }
            }
        }
        position = byteBuf.readerIndex();
        return optimizePositive ? result : ((result >>> 1) ^ -(result & 1));
    }

    private int readVarIntFlag_slow (boolean optimizePositive) {
        // The buffer is guaranteed to have at least 1 byte.
        position++;
        int b = byteBuf.readByte();
        int result = b & 0x3F;
        if ((b & 0x40) != 0) {
            if (position == limit) require(1);
            position++;
            ByteBuf byteBuf = this.byteBuf;
            b = byteBuf.readByte();
            result |= (b & 0x7F) << 6;
            if ((b & 0x80) != 0) {
                if (position == limit) require(1);
                position++;
                b = byteBuf.readByte();
                result |= (b & 0x7F) << 13;
                if ((b & 0x80) != 0) {
                    if (position == limit) require(1);
                    position++;
                    b = byteBuf.readByte();
                    result |= (b & 0x7F) << 20;
                    if ((b & 0x80) != 0) {
                        if (position == limit) require(1);
                        position++;
                        b = byteBuf.readByte();
                        result |= (b & 0x7F) << 27;
                    }
                }
            }
        }
        return optimizePositive ? result : ((result >>> 1) ^ -(result & 1));
    }

    // long:

    public long readLong () throws KryoException {
        require(8);
        position += 8;
        ByteBuf byteBuf = this.byteBuf;
        return byteBuf.readByte() & 0xFF //
               | (byteBuf.readByte() & 0xFF) << 8 //
               | (byteBuf.readByte() & 0xFF) << 16 //
               | (long)(byteBuf.readByte() & 0xFF) << 24 //
               | (long)(byteBuf.readByte() & 0xFF) << 32 //
               | (long)(byteBuf.readByte() & 0xFF) << 40 //
               | (long)(byteBuf.readByte() & 0xFF) << 48 //
               | (long)byteBuf.readByte() << 56;
    }

    public long readVarLong (boolean optimizePositive) throws KryoException {
        if (require(1) < 9) return readVarLong_slow(optimizePositive);
        int b = byteBuf.readByte();
        long result = b & 0x7F;
        if ((b & 0x80) != 0) {
            ByteBuf byteBuf = this.byteBuf;
            b = byteBuf.readByte();
            result |= (b & 0x7F) << 7;
            if ((b & 0x80) != 0) {
                b = byteBuf.readByte();
                result |= (b & 0x7F) << 14;
                if ((b & 0x80) != 0) {
                    b = byteBuf.readByte();
                    result |= (b & 0x7F) << 21;
                    if ((b & 0x80) != 0) {
                        b = byteBuf.readByte();
                        result |= (long)(b & 0x7F) << 28;
                        if ((b & 0x80) != 0) {
                            b = byteBuf.readByte();
                            result |= (long)(b & 0x7F) << 35;
                            if ((b & 0x80) != 0) {
                                b = byteBuf.readByte();
                                result |= (long)(b & 0x7F) << 42;
                                if ((b & 0x80) != 0) {
                                    b = byteBuf.readByte();
                                    result |= (long)(b & 0x7F) << 49;
                                    if ((b & 0x80) != 0) {
                                        b = byteBuf.readByte();
                                        result |= (long)b << 56;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        position = byteBuf.readerIndex();
        return optimizePositive ? result : ((result >>> 1) ^ -(result & 1));
    }

    private long readVarLong_slow (boolean optimizePositive) {
        // The buffer is guaranteed to have at least 1 byte.
        position++;
        int b = byteBuf.readByte();
        long result = b & 0x7F;
        if ((b & 0x80) != 0) {
            if (position == limit) require(1);
            ByteBuf byteBuf = this.byteBuf;
            position++;
            b = byteBuf.readByte();
            result |= (b & 0x7F) << 7;
            if ((b & 0x80) != 0) {
                if (position == limit) require(1);
                position++;
                b = byteBuf.readByte();
                result |= (b & 0x7F) << 14;
                if ((b & 0x80) != 0) {
                    if (position == limit) require(1);
                    position++;
                    b = byteBuf.readByte();
                    result |= (b & 0x7F) << 21;
                    if ((b & 0x80) != 0) {
                        if (position == limit) require(1);
                        position++;
                        b = byteBuf.readByte();
                        result |= (long)(b & 0x7F) << 28;
                        if ((b & 0x80) != 0) {
                            if (position == limit) require(1);
                            position++;
                            b = byteBuf.readByte();
                            result |= (long)(b & 0x7F) << 35;
                            if ((b & 0x80) != 0) {
                                if (position == limit) require(1);
                                position++;
                                b = byteBuf.readByte();
                                result |= (long)(b & 0x7F) << 42;
                                if ((b & 0x80) != 0) {
                                    if (position == limit) require(1);
                                    position++;
                                    b = byteBuf.readByte();
                                    result |= (long)(b & 0x7F) << 49;
                                    if ((b & 0x80) != 0) {
                                        if (position == limit) require(1);
                                        position++;
                                        b = byteBuf.readByte();
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

    public boolean canReadVarLong () throws KryoException {
        if (limit - position >= 9) return true;
        if (optional(5) <= 0) return false;
        int p = position, limit = this.limit;
        ByteBuf byteBuf = this.byteBuf;
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

    public float readFloat () throws KryoException {
        require(4);
        ByteBuf byteBuf = this.byteBuf;
        int p = this.position;
        this.position = p + 4;
        return Float.intBitsToFloat(byteBuf.readByte() & 0xFF //
                                    | (byteBuf.readByte() & 0xFF) << 8 //
                                    | (byteBuf.readByte() & 0xFF) << 16 //
                                    | (byteBuf.readByte() & 0xFF) << 24);
    }

    // double:

    public double readDouble () throws KryoException {
        require(8);
        ByteBuf byteBuf = this.byteBuf;
        int p = position;
        position = p + 8;
        return Double.longBitsToDouble(byteBuf.readByte() & 0xFF //
                                       | (byteBuf.readByte() & 0xFF) << 8 //
                                       | (byteBuf.readByte() & 0xFF) << 16 //
                                       | (long)(byteBuf.readByte() & 0xFF) << 24 //
                                       | (long)(byteBuf.readByte() & 0xFF) << 32 //
                                       | (long)(byteBuf.readByte() & 0xFF) << 40 //
                                       | (long)(byteBuf.readByte() & 0xFF) << 48 //
                                       | (long)byteBuf.readByte() << 56);
    }

    // boolean:

    public boolean readBoolean () throws KryoException {
        if (position == limit) require(1);
        position++;
        return byteBuf.readByte() == 1 ? true : false;
    }

    // short:

    public short readShort () throws KryoException {
        require(2);
        position += 2;
        return (short)((byteBuf.readByte() & 0xFF) | ((byteBuf.readByte() & 0xFF) << 8));
    }

    public int readShortUnsigned () throws KryoException {
        require(2);
        position += 2;
        return (byteBuf.readByte() & 0xFF) | ((byteBuf.readByte() & 0xFF) << 8);
    }

    // char:

    public char readChar () throws KryoException {
        require(2);
        position += 2;
        return (char)((byteBuf.readByte() & 0xFF) | ((byteBuf.readByte() & 0xFF) << 8));
    }

    // String:

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
        charCount--;
        readUtf8Chars(charCount);
        return new String(chars, 0, charCount);
    }

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
        ByteBuf byteBuf = this.byteBuf;
        int charIndex = 0;
        int count = Math.min(require(1), charCount);
        while (charIndex < count) {
            int b = byteBuf.readByte();
            if (b < 0) break;
            chars[charIndex++] = (char)b;
        }
        position += charIndex;
        // If buffer didn't hold all chars or any were not ASCII, use slow path for remainder.
        if (charIndex < charCount) {
            byteBuf.readerIndex(position);
            readUtf8Chars_slow(charCount, charIndex);
        }
    }

    private void readUtf8Chars_slow (int charCount, int charIndex) {
        ByteBuf byteBuf = this.byteBuf;
        char[] chars = this.chars;
        while (charIndex < charCount) {
            if (position == limit) require(1);
            position++;
            int b = byteBuf.readByte() & 0xFF;
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
                    if (position == limit) require(1);
                    position++;
                    chars[charIndex] = (char)((b & 0x1F) << 6 | byteBuf.readByte() & 0x3F);
                    break;
                case 14:
                    require(2);
                    position += 2;
                    int b2 = byteBuf.readByte();
                    int b3 = byteBuf.readByte();
                    chars[charIndex] = (char)((b & 0x0F) << 12 | (b2 & 0x3F) << 6 | b3 & 0x3F);
                    break;
            }
            charIndex++;
        }
    }

    private String readAsciiString () {
        char[] chars = this.chars;
        ByteBuf byteBuf = this.byteBuf;
        int charCount = 0;
        for (int n = Math.min(chars.length, limit - position); charCount < n; charCount++) {
            int b = byteBuf.readByte();
            if ((b & 0x80) == 0x80) {
                position = byteBuf.readerIndex();
                chars[charCount] = (char)(b & 0x7F);
                return new String(chars, 0, charCount + 1);
            }
            chars[charCount] = (char)b;
        }
        position = byteBuf.readerIndex();
        return readAscii_slow(charCount);
    }

    private String readAscii_slow (int charCount) {
        char[] chars = this.chars;
        ByteBuf byteBuf = this.byteBuf;
        while (true) {
            if (position == limit) require(1);
            position++;
            int b = byteBuf.readByte();
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

    // Primitive arrays:

    public int[] readInts (int length) throws KryoException {
        int[] array = new int[length];
        if (optional(length << 2) == length << 2) {
            ByteBuf byteBuf = this.byteBuf;
            for (int i = 0; i < length; i++) {
                array[i] = byteBuf.readByte() & 0xFF //
                           | (byteBuf.readByte() & 0xFF) << 8 //
                           | (byteBuf.readByte() & 0xFF) << 16 //
                           | (byteBuf.readByte() & 0xFF) << 24;
            }
            position = byteBuf.readerIndex();
        } else {
            for (int i = 0; i < length; i++)
                array[i] = readInt();
        }
        return array;
    }

    public long[] readLongs (int length) throws KryoException {
        long[] array = new long[length];
        if (optional(length << 3) == length << 3) {
            ByteBuf byteBuf = this.byteBuf;
            for (int i = 0; i < length; i++) {
                array[i] = byteBuf.readByte() & 0xFF//
                           | (byteBuf.readByte() & 0xFF) << 8 //
                           | (byteBuf.readByte() & 0xFF) << 16 //
                           | (long)(byteBuf.readByte() & 0xFF) << 24 //
                           | (long)(byteBuf.readByte() & 0xFF) << 32 //
                           | (long)(byteBuf.readByte() & 0xFF) << 40 //
                           | (long)(byteBuf.readByte() & 0xFF) << 48 //
                           | (long)byteBuf.readByte() << 56;
            }
            position = byteBuf.readerIndex();
        } else {
            for (int i = 0; i < length; i++)
                array[i] = readLong();
        }
        return array;
    }

    public float[] readFloats (int length) throws KryoException {
        float[] array = new float[length];
        if (optional(length << 2) == length << 2) {
            ByteBuf byteBuf = this.byteBuf;
            for (int i = 0; i < length; i++) {
                array[i] = Float.intBitsToFloat(byteBuf.readByte() & 0xFF //
                                                | (byteBuf.readByte() & 0xFF) << 8 //
                                                | (byteBuf.readByte() & 0xFF) << 16 //
                                                | (byteBuf.readByte() & 0xFF) << 24);
            }
            position = byteBuf.readerIndex();
        } else {
            for (int i = 0; i < length; i++)
                array[i] = readFloat();
        }
        return array;
    }

    public double[] readDoubles (int length) throws KryoException {
        double[] array = new double[length];
        if (optional(length << 3) == length << 3) {
            ByteBuf byteBuf = this.byteBuf;
            for (int i = 0; i < length; i++) {
                array[i] = Double.longBitsToDouble(byteBuf.readByte() & 0xFF //
                                                   | (byteBuf.readByte() & 0xFF) << 8 //
                                                   | (byteBuf.readByte() & 0xFF) << 16 //
                                                   | (long)(byteBuf.readByte() & 0xFF) << 24 //
                                                   | (long)(byteBuf.readByte() & 0xFF) << 32 //
                                                   | (long)(byteBuf.readByte() & 0xFF) << 40 //
                                                   | (long)(byteBuf.readByte() & 0xFF) << 48 //
                                                   | (long)byteBuf.readByte() << 56);
            }
            position = byteBuf.readerIndex();
        } else {
            for (int i = 0; i < length; i++)
                array[i] = readDouble();
        }
        return array;
    }

    public short[] readShorts (int length) throws KryoException {
        short[] array = new short[length];
        if (optional(length << 1) == length << 1) {
            ByteBuf byteBuf = this.byteBuf;
            for (int i = 0; i < length; i++)
                array[i] = (short)((byteBuf.readByte() & 0xFF) | ((byteBuf.readByte() & 0xFF) << 8));
            position = byteBuf.readerIndex();
        } else {
            for (int i = 0; i < length; i++)
                array[i] = readShort();
        }
        return array;
    }

    public char[] readChars (int length) throws KryoException {
        char[] array = new char[length];
        if (optional(length << 1) == length << 1) {
            ByteBuf byteBuf = this.byteBuf;
            for (int i = 0; i < length; i++)
                array[i] = (char)((byteBuf.readByte() & 0xFF) | ((byteBuf.readByte() & 0xFF) << 8));
            position = byteBuf.readerIndex();
        } else {
            for (int i = 0; i < length; i++)
                array[i] = readChar();
        }
        return array;
    }

    public boolean[] readBooleans (int length) throws KryoException {
        boolean[] array = new boolean[length];
        if (optional(length) == length) {
            ByteBuf byteBuf = this.byteBuf;
            for (int i = 0; i < length; i++)
                array[i] = byteBuf.readByte() != 0;
            position = byteBuf.readerIndex();
        } else {
            for (int i = 0; i < length; i++)
                array[i] = readBoolean();
        }
        return array;
    }
}
