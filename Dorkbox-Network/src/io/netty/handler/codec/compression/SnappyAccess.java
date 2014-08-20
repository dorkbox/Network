package io.netty.handler.codec.compression;

import io.netty.buffer.ByteBuf;

public class SnappyAccess {

    // oh well. At least we can still get to it.
    private Snappy snappy = new Snappy();

    public SnappyAccess() {
    }

    public void encode(ByteBuf slice, ByteBuf outputBuffer, short maxValue) {
        snappy.encode(slice, outputBuffer, maxValue);
    }

    public void encode(ByteBuf slice, ByteBuf outputBuffer, int dataLength) {
        snappy.encode(slice, outputBuffer, dataLength);
    }

    public void decode(ByteBuf inputBuffer, ByteBuf outputBuffer) {
        snappy.decode(inputBuffer, outputBuffer);
    }

    public void reset() {
        snappy.reset();
    }

    public static int calculateChecksum(ByteBuf data, int offset, int length) {
        return Snappy.calculateChecksum(data, offset, length);
    }

    public static int calculateChecksum(ByteBuf slice) {
        return Snappy.calculateChecksum(slice);
    }
}
