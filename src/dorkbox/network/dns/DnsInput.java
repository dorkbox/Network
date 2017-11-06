// Copyright (c) 2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns;

import dorkbox.network.dns.exceptions.WireParseException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * An class for parsing DNS messages.
 *
 * @author Brian Wellington
 */

public
class DnsInput {

    private ByteBuf byteBuf;
    private int savedActiveIndex = -1;
    private boolean marked = false;

    /**
     * Creates a new DnsInput
     *
     * @param input The byte array to read from
     */
    public
    DnsInput(byte[] input) {
        byteBuf = Unpooled.wrappedBuffer(input);
    }

    /**
     * Creates a new DnsInput from the given {@link ByteBuf}
     *
     * @param byteBuf The ByteBuf
     */
    public
    DnsInput(ByteBuf byteBuf) {
        this.byteBuf = byteBuf;
    }

    /**
     * Returns the current position, for reading only
     */
    public
    int readIndex() {
        return byteBuf.readerIndex();
    }

    /**
     * NOTE: "Active" restricts operations to a specific part of the buffer, defined by the current position + length. Operations that
     * extend BEYOND this section are denied by virtue that we set the max readable length in the underlying ByteBuf.
     *
     * Marks the following bytes in the stream as active, and saves it's state (so it can be restored later)
     *
     * @param len The number of bytes in the active region.
     *
     * @throws IllegalArgumentException The number of bytes in the active region
     *         is longer than the remainder of the input.
     */
    public
    void setActive(int len) {
        savedActiveIndex = byteBuf.writerIndex();

        if (len > byteBuf.readableBytes()) {
            throw new IllegalArgumentException("cannot set active " + "region past end of input");
        }

        byteBuf.writerIndex(byteBuf.readerIndex() + len);
    }

    /**
     * Restores the previously set active region.
     */
    public
    void restoreActive() {
        if (savedActiveIndex > -1) {
            byteBuf.writerIndex(savedActiveIndex);
            savedActiveIndex = -1;
        }
    }

    /**
     * Resets the current position of the input stream to the specified index,
     * and clears the active region.
     *
     * @param index The position to continue parsing at.
     *
     * @throws IllegalArgumentException The index is not within the input.
     */
    public
    void jump(int index) {
        if (index >= byteBuf.capacity()) {
            throw new IllegalArgumentException("cannot jump past " + "end of input");
        }
        byteBuf.readerIndex(index);

        restoreActive();
    }

    /**
     * Saves the current state of the input stream.  Both the current position and
     * the end of the active region are saved.
     *
     * @throws IllegalArgumentException The index is not within the input.
     */
    public
    void save() {
        marked = true;
        byteBuf.markReaderIndex();
    }

    /**
     * Restores the input stream to its state before the call to {@link #save}.
     */
    public
    void restore() {
        if (!marked) {
            throw new IllegalStateException("Not marked first");
        }
        byteBuf.resetReaderIndex();
    }

    private
    void require(int n) throws WireParseException {
        if (n > remaining()) {
            throw new WireParseException("end of input");
        }
    }

    /**
     * Returns the number of bytes that can be read from this stream before
     * reaching the end.
     */
    public
    int remaining() {
        return byteBuf.readableBytes();
    }

    /**
     * Reads an unsigned 8 bit value from the stream, as an int.
     *
     * @return An unsigned 8 bit value.
     *
     * @throws WireParseException The end of the stream was reached.
     */
    public
    int readU8() throws WireParseException {
        require(1);
        return byteBuf.readUnsignedByte();
    }

    /**
     * Reads an unsigned 16 bit value from the stream, as an int.
     *
     * @return An unsigned 16 bit value.
     *
     * @throws WireParseException The end of the stream was reached.
     */
    public
    int readU16() throws WireParseException {
        require(2);
        return byteBuf.readUnsignedShort();
    }

    /**
     * Reads an unsigned 32 bit value from the stream, as a long.
     *
     * @return An unsigned 32 bit value.
     *
     * @throws WireParseException The end of the stream was reached.
     */
    public
    long readU32() throws WireParseException {
        require(4);
        return byteBuf.readUnsignedInt();
    }

    /**
     * Reads a byte array of a specified length from the stream into an existing
     * array.
     *
     * @param b The array to read into.
     * @param off The offset of the array to start copying data into.
     * @param len The number of bytes to copy.
     *
     * @throws WireParseException The end of the stream was reached.
     */
    public
    void readByteArray(byte[] b, int off, int len) throws WireParseException {
        require(len);
        byteBuf.readBytes(b, off, len);
    }

    /**
     * Reads a byte array of a specified length from the stream.
     *
     * @return The byte array.
     *
     * @throws WireParseException The end of the stream was reached.
     */
    public
    byte[] readByteArray(int len) throws WireParseException {
        require(len);
        byte[] out = new byte[len];
        byteBuf.readBytes(out, 0, len);
        return out;
    }

    /**
     * Reads a byte array consisting of the remainder of the stream (or the
     * active region, if one is set.
     *
     * @return The byte array.
     */
    public
    byte[] readByteArray() {
        int len = remaining();
        byte[] out = new byte[len];
        byteBuf.readBytes(out, 0, len);
        return out;
    }

    /**
     * Reads a counted string from the stream.  A counted string is a one byte
     * value indicating string length, followed by bytes of data.
     *
     * @return A byte array containing the string.
     *
     * @throws WireParseException The end of the stream was reached.
     */
    public
    byte[] readCountedString() throws WireParseException {
        int len = readU8();
        return readByteArray(len);
    }
}
