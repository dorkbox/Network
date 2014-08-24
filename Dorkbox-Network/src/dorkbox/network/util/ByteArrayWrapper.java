package dorkbox.network.util;

import java.util.Arrays;

/**
 * Necessary to provide equals and hashcode for byte arrays (if they are to be used as keys in a map/set/etc)
 */
public final class ByteArrayWrapper {
    private final byte[] data;

    public ByteArrayWrapper(byte[] data) {
        if (data == null) {
            throw new NullPointerException();
        }
        int length = data.length;
        this.data = new byte[length];
        // copy so it's immutable as a key.
        System.arraycopy(data, 0, this.data, 0, length);
    }

    public byte[] getBytes() {
        return this.data;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ByteArrayWrapper)) {
            return false;
        }
        return Arrays.equals(this.data, ((ByteArrayWrapper) other).data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(this.data);
    }
}