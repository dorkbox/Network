
package dorkbox.network.connection.idle;


import java.io.IOException;
import java.io.InputStream;

import dorkbox.network.connection.Connection;
import dorkbox.network.util.NetException;

abstract public class InputStreamSender<C extends Connection> extends IdleSender<C,byte[]> {

    private final InputStream input;
    private final byte[] chunk;

    public InputStreamSender(InputStream input, int chunkSize) {
        this.input = input;
        this.chunk = new byte[chunkSize];
    }

    @Override
    protected final byte[] next() {
        try {
            int total = 0;
            while (total < this.chunk.length) {
                int count = this.input.read(this.chunk, total, this.chunk.length - total);
                if (count < 0) {
                    if (total == 0) {
                        return null;
                    }
                    byte[] partial = new byte[total];
                    System.arraycopy(this.chunk, 0, partial, 0, total);
                    return onNext(partial);
                }
                total += count;
            }
        } catch (IOException ex) {
            throw new NetException(ex);
        }
        return onNext(this.chunk);
    }

    abstract protected byte[] onNext (byte[] chunk);
}
