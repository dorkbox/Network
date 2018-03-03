// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * A class for rendering DNS messages.
 *
 * @author Brian Wellington
 */


public
class DnsOutput {

    private ByteBuf byteBuf;
    private boolean marked = false;

    /**
     * Create a new DnsOutput
     */
    public
    DnsOutput() {
        this(32);
    }

    /**
     * Create a new DnsOutput with a specified size.
     *
     * @param size The initial size
     */
    public
    DnsOutput(int size) {
        this(Unpooled.buffer(size));
    }

    /**
     * Create a new DnsOutput with a specified ByteBuf.
     *
     * @param byteBuf The ByteBuf to use
     */
    public
    DnsOutput(ByteBuf byteBuf) {
        this.byteBuf = byteBuf;
    }

    /**
     * Returns the current position.
     */
    public
    int current() {
        return byteBuf.writerIndex();
    }

    /**
     * Resets the current position of the output stream to the specified index.
     *
     * @param index The new current position.
     *
     * @throws IllegalArgumentException The index is not within the output.
     */
    public
    void jump(int index) {
        if (index >= byteBuf.writerIndex()) {
            // we haven't written data to this point yet, and the contract for jump() is that it can only jump to a PREVIOUSLY written spot
            throw new IllegalArgumentException("Unable to jump to invalid position " + index + ". Max is " + byteBuf.writerIndex());
        }

        byteBuf.writerIndex(index);
    }

    /**
     * Saves the current state of the output stream.
     *
     * @throws IllegalArgumentException The index is not within the output.
     */
    public
    void save() {
        marked = true;
        byteBuf.markWriterIndex();
    }

    /**
     * Restores the input stream to its state before the call to {@link #save}.
     */
    public
    void restore() {
        if (!marked) {
            throw new IllegalStateException("Not marked first");
        }
        byteBuf.resetWriterIndex();
        marked = false;
    }

    /**
     * Writes an unsigned 8 bit value to the stream.
     *
     * @param val The value to be written
     */
    public
    void writeU8(int val) {
        check(val, 8);

        byteBuf.ensureWritable(1);
        byteBuf.writeByte(val);
    }

    private
    void check(long val, int bits) {
        long max = 1;
        max <<= bits;
        if (val < 0 || val > max) {
            throw new IllegalArgumentException(val + " out of range for " + bits + " bit value");
        }
    }

    /**
     * Writes an unsigned 16 bit value to the stream.
     *
     * @param val The value to be written
     */
    public
    void writeU16(int val) {
        check(val, 16);

        byteBuf.ensureWritable(2);
        byteBuf.writeShort(val);
    }

    /**
     * Writes an unsigned 16 bit value to the specified position in the stream.
     *
     * @param val The value to be written
     * @param where The position to write the value.
     */
    public
    void writeU16At(int val, int where) {
        check(val, 16);

        // save and set both the read/write index, otherwise if the read index is > write index, errors happen.
        int saved = byteBuf.writerIndex();
        int readSaved = byteBuf.readerIndex();

        byteBuf.setIndex(where, where);
        byteBuf.ensureWritable(2);
        byteBuf.writeShort(val);

        // put the read/write back to where it was (since this was an operation to write a value at a specific position)
        byteBuf.writerIndex(saved);
        byteBuf.readerIndex(readSaved);
    }

    /**
     * Writes an unsigned 32 bit value to the stream.
     *
     * @param val The value to be written
     */
    public
    void writeU32(long val) {
        check(val, 32);

        byteBuf.ensureWritable(4);
        byteBuf.writeInt((int) val);
    }

    /**
     * Writes a byte array to the stream.
     *
     * @param b The array to write.
     */
    public
    void writeByteArray(byte[] b) {
        writeByteArray(b, 0, b.length);
    }

    /**
     * Writes a byte array to the stream.
     *
     * @param b The array to write.
     * @param off The offset of the array to start copying data from.
     * @param len The number of bytes to write.
     */
    public
    void writeByteArray(byte[] b, int off, int len) {
        byteBuf.ensureWritable(len);
        byteBuf.writeBytes(b, off, len);
    }

    /**
     * Writes a counted string from the stream.  A counted string is a one byte
     * value indicating string length, followed by bytes of data.
     *
     * @param s The string to write.
     */
    public
    void writeCountedString(byte[] s) {
        if (s.length > 0xFF) {
            throw new IllegalArgumentException("Invalid counted string");
        }

        byteBuf.ensureWritable(1 + s.length);
        byteBuf.writeByte(s.length);
        byteBuf.writeBytes(s, 0, s.length);
    }

    /**
     * Returns a byte array containing the current contents of the stream.
     */
    public
    byte[] toByteArray() {
        byte[] out = new byte[byteBuf.writerIndex()];
        byteBuf.readBytes(out, 0, out.length);
        return out;
    }

    public
    ByteBuf getByteBuf() {
        return byteBuf;
    }
}
