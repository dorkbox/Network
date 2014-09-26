package dorkbox.network.connection.wrapper;

import dorkbox.network.connection.ConnectionPoint;
import dorkbox.network.connection.ConnectionPointWriter;

public class ChannelNull implements ConnectionPointWriter {

    private static final ConnectionPoint INSTANCE = new ChannelNull();
    public static ConnectionPoint get() {
        return INSTANCE;
    }

    private ChannelNull() {
    }

    /**
     * Write an object to the underlying channel
     */
    @Override
    public void write(Object object) {
    }

    /**
     * Waits for the last write to complete. Useful when sending large amounts of data at once.
     * <b>DO NOT use this in the same thread as receiving messages! It will deadlock.</b>
     */
    @Override
    public void waitForWriteToComplete() {
    }

    @Override
    public void flush() {
    }
}
