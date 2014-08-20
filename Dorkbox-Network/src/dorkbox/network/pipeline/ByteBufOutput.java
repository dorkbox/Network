package dorkbox.network.pipeline;

import io.netty.buffer.ByteBuf;

import java.io.DataOutput;
import java.io.OutputStream;

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Output;

/**
 * An {@link OutputStream} which writes data to a {@link ChannelBuffer}.
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

    private ByteBuf byteBuf;
	private int startIndex;


    /** Creates an uninitialized Output. {@link #setBuffer(ByteBuf)} must be called before the Output is used. */
	public ByteBufOutput () {
	}

	public ByteBufOutput(ByteBuf buffer) {
	    setBuffer(buffer);
	}

    public final void setBuffer(ByteBuf byteBuf) {
        this.byteBuf = byteBuf;

        if (byteBuf != null) {
            this.byteBuf.readerIndex(0);
            startIndex = byteBuf.writerIndex();
        } else {
            startIndex = 0;
        }
    }

    public ByteBuf getByteBuf() {
        return byteBuf;
    }


    @Override
    @Deprecated
    public OutputStream getOutputStream () {
	    throw new RuntimeException("Cannot access this method!");
	}

	/** Sets a new OutputStream. The position and total are reset, discarding any buffered bytes.
	 * @param outputStream May be null. */
	@Override
	@Deprecated
    public void setOutputStream (OutputStream outputStream) {
	    throw new RuntimeException("Cannot access this method!");
	}

	/** Sets the buffer that will be written to. {@link #setBuffer(byte[], int)} is called with the specified buffer's length as the
	 * maxBufferSize. */
	@Override
    @Deprecated
    public void setBuffer (byte[] buffer) {
	    throw new RuntimeException("Cannot access this method!");
	}

	/** Sets the buffer that will be written to. The position and total are reset, discarding any buffered bytes. The
	 * {@link #setOutputStream(OutputStream) OutputStream} is set to null.
	 * @param maxBufferSize The buffer is doubled as needed until it exceeds maxBufferSize and an exception is thrown. */
	@Override
    @Deprecated
    public void setBuffer (byte[] buffer, int maxBufferSize) {
	    throw new RuntimeException("Cannot access this method!");
	}

	/** Returns the buffer. The bytes between zero and {@link #position()} are the data that has been written. */
	@Override
    @Deprecated
    public byte[] getBuffer () {
	    throw new RuntimeException("Cannot access this method!");
	}

	/** Returns a new byte array containing the bytes currently in the buffer between zero and {@link #position()}. */
	@Override
    @Deprecated
    public byte[] toBytes () {
	    throw new RuntimeException("Cannot access this method!");
	}

	/** Returns the current position in the buffer. This is the number of bytes that have not been flushed. */
	@Override
    public int position () {
		return byteBuf.writerIndex();
	}

	/** Sets the current position in the buffer. */
	@Override
    @Deprecated
    public void setPosition (int position) {
	    throw new RuntimeException("Cannot access this method!");
	}

	/** Returns the total number of bytes written. This may include bytes that have not been flushed. */
	@Override
    public long total () {
	    return byteBuf.writerIndex() - startIndex;
	}

	/** Sets the position and total to zero. */
	@Override
    public void clear () {
	    byteBuf.readerIndex(0);
	    byteBuf.writerIndex(startIndex);
	}


	// OutputStream

	/** Writes the buffered bytes to the underlying OutputStream, if any. */
	@Override
	@Deprecated
    public void flush () throws KryoException {
	    // do nothing...
	}

	/** Flushes any buffered bytes and closes the underlying OutputStream, if any. */
	@Override
    @Deprecated
    public void close () throws KryoException {
	 // do nothing...
	}

	/** Writes a byte. */
	@Override
    public void write (int value) throws KryoException {
		byteBuf.writeByte(value);
	}

	/** Writes the bytes. Note the byte[] length is not written. */
	@Override
    public void write (byte[] bytes) throws KryoException {
		if (bytes == null) {
            throw new IllegalArgumentException("bytes cannot be null.");
        }
		writeBytes(bytes, 0, bytes.length);
	}

	/** Writes the bytes. Note the byte[] length is not written. */
	@Override
    public void write (byte[] bytes, int offset, int length) throws KryoException {
		writeBytes(bytes, offset, length);
	}

	// byte

	@Override
    public void writeByte (byte value) throws KryoException {
	    byteBuf.writeByte(value);
	}

	@Override
    public void writeByte (int value) throws KryoException {
	    byteBuf.writeByte((byte)value);
	}

	/** Writes the bytes. Note the byte[] length is not written. */
	@Override
    public void writeBytes (byte[] bytes) throws KryoException {
		if (bytes == null) {
            throw new IllegalArgumentException("bytes cannot be null.");
        }
		writeBytes(bytes, 0, bytes.length);
	}

	/** Writes the bytes. Note the byte[] length is not written. */
	@Override
    public void writeBytes (byte[] bytes, int offset, int count) throws KryoException {
	    if (bytes == null) {
            throw new IllegalArgumentException("bytes cannot be null.");
        }
		byteBuf.writeBytes(bytes, offset, count);
	}

	// int

	/** Writes a 4 byte int. */
	@Override
    public void writeInt (int value) throws KryoException {
	    byteBuf.writeInt(value);
	}

	/** Writes a 1-5 byte int. This stream may consider such a variable length encoding request as a hint. It is not guaranteed that
     * a variable length encoding will be really used. The stream may decide to use native-sized integer representation for
     * efficiency reasons.
     *
     * @param optimizePositive If true, small positive numbers will be more efficient (1 byte) and small negative numbers will be
     *           inefficient (5 bytes). */
	@Override
    public int writeInt (int value, boolean optimizePositive) throws KryoException {
	    return writeVarInt(value, optimizePositive);
	}

	/** Writes a 1-5 byte int. It is guaranteed that a varible length encoding will be used.
	 *
     * @param optimizePositive If true, small positive numbers will be more efficient (1 byte) and small negative numbers will be
     *           inefficient (5 bytes). */
    @Override
    public int writeVarInt (int value, boolean optimizePositive) throws KryoException {
	    ByteBuf buffer = byteBuf;

	    if (!optimizePositive) {
            value = value << 1 ^ value >> 31;
        }
        if (value >>> 7 == 0) {
            buffer.writeByte((byte)value);
            return 1;
        }
        if (value >>> 14 == 0) {
            buffer.writeByte((byte)(value & 0x7F | 0x80));
            buffer.writeByte((byte)(value >>> 7));
            return 2;
        }
        if (value >>> 21 == 0) {
            buffer.writeByte((byte)(value & 0x7F | 0x80));
            buffer.writeByte((byte)(value >>> 7 | 0x80));
            buffer.writeByte((byte)(value >>> 14));
            return 3;
        }
        if (value >>> 28 == 0) {
            buffer.writeByte((byte)(value & 0x7F | 0x80));
            buffer.writeByte((byte)(value >>> 7 | 0x80));
            buffer.writeByte((byte)(value >>> 14 | 0x80));
            buffer.writeByte((byte)(value >>> 21));
            return 4;
        }
        buffer.writeByte((byte)(value & 0x7F | 0x80));
        buffer.writeByte((byte)(value >>> 7 | 0x80));
        buffer.writeByte((byte)(value >>> 14 | 0x80));
        buffer.writeByte((byte)(value >>> 21 | 0x80));
        buffer.writeByte((byte)(value >>> 28));
        return 5;
	}

	// string

	/** Writes the length and string, or null. Short strings are checked and if ASCII they are written more efficiently, else they
	 * are written as UTF8. If a string is known to be ASCII, {@link #writeAscii(String)} may be used. The string can be read using
	 * {@link Input#readString()} or {@link Input#readStringBuilder()}.
	 * @param value May be null. */
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
		// Detect ASCII.
		boolean ascii = false;
		if (charCount > 1 && charCount < 64) { // only snoop 64 chars in
			ascii = true;
			for (int i = 0; i < charCount; i++) {
				int c = value.charAt(i);
				if (c > 127) {
					ascii = false;
					break;
				}
			}
		}

		ByteBuf buffer = byteBuf;
		if (buffer.writableBytes() < charCount) {
            buffer.capacity(buffer.capacity() + charCount + 1);
        }

		if (!ascii) {
		    writeUtf8Length(charCount + 1);
		}

		int charIndex = 0;
        // Try to write 8 bit chars.
        for (; charIndex < charCount; charIndex++) {
            int c = value.charAt(charIndex);
            if (c > 127)
             {
                break; // whoops! detect ascii. have to continue with a slower method!
            }
            buffer.writeByte((byte)c);
        }
        if (charIndex < charCount) {
            writeString_slow(value, charCount, charIndex);
        }
        else if (ascii) {
            // specify it's ASCII
            int i = buffer.writerIndex() - 1;
            buffer.setByte(i, buffer.getByte(i) | 0x80); // Bit 8 means end of ASCII.
        }
	}

	/** Writes the length and CharSequence as UTF8, or null. The string can be read using {@link Input#readString()} or
	 * {@link Input#readStringBuilder()}.
	 * @param value May be null. */
	@Override
    public void writeString (CharSequence value) throws KryoException {
		if (value == null) {
			writeByte(0x80); // 0 means null, bit 8 means UTF8.
			return;
		}
		int charCount = value.length();
		if (charCount == 0) {
			writeByte(1 | 0x80); // 1 means empty string, bit 8 means UTF8.
			return;
		}
		writeUtf8Length(charCount + 1);

		ByteBuf buffer = byteBuf;
		if (buffer.writableBytes() < charCount) {
            buffer.capacity(buffer.capacity() + charCount + 1);
        }
		int charIndex = 0;
        // Try to write 8 bit chars.
        for (; charIndex < charCount; charIndex++) {
            int c = value.charAt(charIndex);
            if (c > 127)
             {
                break;  // whoops! have to continue with a slower method!
            }
            buffer.writeByte((byte)c);
        }
        if (charIndex < charCount) {
            writeString_slow(value, charCount, charIndex);
        }
	}

	/** Writes a string that is known to contain only ASCII characters. Non-ASCII strings passed to this method will be corrupted.
	 * Each byte is a 7 bit character with the remaining byte denoting if another character is available. This is slightly more
	 * efficient than {@link #writeString(String)}. The string can be read using {@link Input#readString()} or
	 * {@link Input#readStringBuilder()}.
	 * @param value May be null. */
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

		ByteBuf buffer = byteBuf;
		if (buffer.writableBytes() < charCount) {
		    buffer.capacity(buffer.capacity() + charCount + 1);
		}


		int charIndex = 0;
        // Try to write 8 bit chars.
        for (; charIndex < charCount; charIndex++) {
            int c = value.charAt(charIndex);
            buffer.writeByte((byte)c);
        }
        // specify it's ASCII
        int i = buffer.writerIndex() - 1;
        buffer.setByte(i, buffer.getByte(i) | 0x80); // Bit 8 means end of ASCII.
	}

	/** Writes the length of a string, which is a variable length encoded int except the first byte uses bit 8 to denote UTF8 and
	 * bit 7 to denote if another byte is present. */
	private void writeUtf8Length (int value) {
	    if (value >>> 6 == 0) {
	        byteBuf.writeByte((byte)(value | 0x80)); // Set bit 8.
        } else if (value >>> 13 == 0) {
            ByteBuf buffer = byteBuf;
            buffer.writeByte((byte)(value | 0x40 | 0x80)); // Set bit 7 and 8.
            buffer.writeByte((byte)(value >>> 6));
        } else if (value >>> 20 == 0) {
            ByteBuf buffer = byteBuf;
            buffer.writeByte((byte)(value | 0x40 | 0x80)); // Set bit 7 and 8.
            buffer.writeByte((byte)(value >>> 6 | 0x80)); // Set bit 8.
            buffer.writeByte((byte)(value >>> 13));
        } else if (value >>> 27 == 0) {
            ByteBuf buffer = byteBuf;
            buffer.writeByte((byte)(value | 0x40 | 0x80)); // Set bit 7 and 8.
            buffer.writeByte((byte)(value >>> 6 | 0x80)); // Set bit 8.
            buffer.writeByte((byte)(value >>> 13 | 0x80)); // Set bit 8.
            buffer.writeByte((byte)(value >>> 20));
        } else {
            ByteBuf buffer = byteBuf;
            buffer.writeByte((byte)(value | 0x40 | 0x80)); // Set bit 7 and 8.
            buffer.writeByte((byte)(value >>> 6 | 0x80)); // Set bit 8.
            buffer.writeByte((byte)(value >>> 13 | 0x80)); // Set bit 8.
            buffer.writeByte((byte)(value >>> 20 | 0x80)); // Set bit 8.
            buffer.writeByte((byte)(value >>> 27));
        }
	}

	private void writeString_slow (CharSequence value, int charCount, int charIndex) {
	    ByteBuf buffer = byteBuf;

	    for (; charIndex < charCount; charIndex++) {
            int c = value.charAt(charIndex);
            if (c <= 0x007F) {
                buffer.writeByte((byte)c);
            } else if (c > 0x07FF) {
                buffer.writeByte((byte)(0xE0 | c >> 12 & 0x0F));
                buffer.writeByte((byte)(0x80 | c >> 6 & 0x3F));
                buffer.writeByte((byte)(0x80 | c & 0x3F));
            } else {
                buffer.writeByte((byte)(0xC0 | c >> 6 & 0x1F));
                buffer.writeByte((byte)(0x80 | c & 0x3F));
            }
        }
    }

	// float

	/** Writes a 4 byte float. */
	@Override
    public void writeFloat (float value) throws KryoException {
		writeInt(Float.floatToIntBits(value));
	}

	/** Writes a 1-5 byte float with reduced precision.
	 * @param optimizePositive If true, small positive numbers will be more efficient (1 byte) and small negative numbers will be
	 *           inefficient (5 bytes). */
	@Override
    public int writeFloat (float value, float precision, boolean optimizePositive) throws KryoException {
		return writeInt((int)(value * precision), optimizePositive);
	}

	// short

	/** Writes a 2 byte short. */
	@Override
    public void writeShort (int value) throws KryoException {
	    byteBuf.writeShort(value);
	}

	// long

	/** Writes an 8 byte long. */
	@Override
    public void writeLong (long value) throws KryoException {
	    byteBuf.writeLong(value);
	}

	/** Writes a 1-9 byte long. This stream may consider such a variable length encoding request as a hint. It is not guaranteed
     * that a variable length encoding will be really used. The stream may decide to use native-sized integer representation for
     * efficiency reasons.
     *
     * @param optimizePositive If true, small positive numbers will be more efficient (1 byte) and small negative numbers will be
     *           inefficient (9 bytes). */
    @Override
    public int writeLong (long value, boolean optimizePositive) throws KryoException {
        return writeVarLong(value, optimizePositive);
    }

    /** Writes a 1-9 byte long. It is guaranteed that a varible length encoding will be used.
     * @param optimizePositive If true, small positive numbers will be more efficient (1 byte) and small negative numbers will be
     *           inefficient (9 bytes). */
    @Override
    public int writeVarLong (long value, boolean optimizePositive) throws KryoException {
	    if (!optimizePositive) {
            value = value << 1 ^ value >> 63;
        }
        if (value >>> 7 == 0) {
            byteBuf.writeByte((byte)value);
            return 1;
        }
        if (value >>> 14 == 0) {
            ByteBuf buffer = byteBuf;
            buffer.writeByte((byte)(value & 0x7F | 0x80));
            buffer.writeByte((byte)(value >>> 7));
            return 2;
        }
        if (value >>> 21 == 0) {
            ByteBuf buffer = byteBuf;
            buffer.writeByte((byte)(value & 0x7F | 0x80));
            buffer.writeByte((byte)(value >>> 7 | 0x80));
            buffer.writeByte((byte)(value >>> 14));
            return 3;
        }
        if (value >>> 28 == 0) {
            ByteBuf buffer = byteBuf;
            buffer.writeByte((byte)(value & 0x7F | 0x80));
            buffer.writeByte((byte)(value >>> 7 | 0x80));
            buffer.writeByte((byte)(value >>> 14 | 0x80));
            buffer.writeByte((byte)(value >>> 21));
            return 4;
        }
        if (value >>> 35 == 0) {
            ByteBuf buffer = byteBuf;
            buffer.writeByte((byte)(value & 0x7F | 0x80));
            buffer.writeByte((byte)(value >>> 7 | 0x80));
            buffer.writeByte((byte)(value >>> 14 | 0x80));
            buffer.writeByte((byte)(value >>> 21 | 0x80));
            buffer.writeByte((byte)(value >>> 28));
            return 5;
        }
        if (value >>> 42 == 0) {
            ByteBuf buffer = byteBuf;
            buffer.writeByte((byte)(value & 0x7F | 0x80));
            buffer.writeByte((byte)(value >>> 7 | 0x80));
            buffer.writeByte((byte)(value >>> 14 | 0x80));
            buffer.writeByte((byte)(value >>> 21 | 0x80));
            buffer.writeByte((byte)(value >>> 28 | 0x80));
            buffer.writeByte((byte)(value >>> 35));
            return 6;
        }
        if (value >>> 49 == 0) {
            ByteBuf buffer = byteBuf;
            buffer.writeByte((byte)(value & 0x7F | 0x80));
            buffer.writeByte((byte)(value >>> 7 | 0x80));
            buffer.writeByte((byte)(value >>> 14 | 0x80));
            buffer.writeByte((byte)(value >>> 21 | 0x80));
            buffer.writeByte((byte)(value >>> 28 | 0x80));
            buffer.writeByte((byte)(value >>> 35 | 0x80));
            buffer.writeByte((byte)(value >>> 42));
            return 7;
        }
        if (value >>> 56 == 0) {
            ByteBuf buffer = byteBuf;
            buffer.writeByte((byte)(value & 0x7F | 0x80));
            buffer.writeByte((byte)(value >>> 7 | 0x80));
            buffer.writeByte((byte)(value >>> 14 | 0x80));
            buffer.writeByte((byte)(value >>> 21 | 0x80));
            buffer.writeByte((byte)(value >>> 28 | 0x80));
            buffer.writeByte((byte)(value >>> 35 | 0x80));
            buffer.writeByte((byte)(value >>> 42 | 0x80));
            buffer.writeByte((byte)(value >>> 49));
            return 8;
        }
        ByteBuf buffer = byteBuf;
        buffer.writeByte((byte)(value & 0x7F | 0x80));
        buffer.writeByte((byte)(value >>> 7 | 0x80));
        buffer.writeByte((byte)(value >>> 14 | 0x80));
        buffer.writeByte((byte)(value >>> 21 | 0x80));
        buffer.writeByte((byte)(value >>> 28 | 0x80));
        buffer.writeByte((byte)(value >>> 35 | 0x80));
        buffer.writeByte((byte)(value >>> 42 | 0x80));
        buffer.writeByte((byte)(value >>> 49 | 0x80));
        buffer.writeByte((byte)(value >>> 56));
        return 9;
	}

	// boolean

	/** Writes a 1 byte boolean. */
	@Override
    public void writeBoolean (boolean value) throws KryoException {
	    byteBuf.writeBoolean(value);
	}

	// char

	/** Writes a 2 byte char. */
	@Override
    public void writeChar (char value) throws KryoException {
	    byteBuf.writeChar(value);
	}

	// double

	/** Writes an 8 byte double. */
	@Override
    public void writeDouble (double value) throws KryoException {
		writeLong(Double.doubleToLongBits(value));
	}

	/** Writes a 1-9 byte double with reduced precision.
	 * @param optimizePositive If true, small positive numbers will be more efficient (1 byte) and small negative numbers will be
	 *           inefficient (9 bytes). */
	@Override
    public int writeDouble (double value, double precision, boolean optimizePositive) throws KryoException {
		return writeLong((long)(value * precision), optimizePositive);
	}

	/** Returns the number of bytes that would be written with {@link #writeInt(int, boolean)}. */
	static public int intLength (int value, boolean optimizePositive) {
		if (!optimizePositive) {
            value = value << 1 ^ value >> 31;
        }
		if (value >>> 7 == 0) {
            return 1;
        }
		if (value >>> 14 == 0) {
            return 2;
        }
		if (value >>> 21 == 0) {
            return 3;
        }
		if (value >>> 28 == 0) {
            return 4;
        }
		return 5;
	}

	/** Returns the number of bytes that would be written with {@link #writeLong(long, boolean)}. */
	static public int longLength (long value, boolean optimizePositive) {
		if (!optimizePositive) {
            value = value << 1 ^ value >> 63;
        }
		if (value >>> 7 == 0) {
            return 1;
        }
		if (value >>> 14 == 0) {
            return 2;
        }
		if (value >>> 21 == 0) {
            return 3;
        }
		if (value >>> 28 == 0) {
            return 4;
        }
		if (value >>> 35 == 0) {
            return 5;
        }
		if (value >>> 42 == 0) {
            return 6;
        }
		if (value >>> 49 == 0) {
            return 7;
        }
		if (value >>> 56 == 0) {
            return 8;
        }
		return 9;
	}
}