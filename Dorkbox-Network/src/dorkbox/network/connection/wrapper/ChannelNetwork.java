package dorkbox.network.connection.wrapper;

import dorkbox.network.connection.ConnectionPointWriter;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

import java.util.concurrent.atomic.AtomicBoolean;

public
class ChannelNetwork implements ConnectionPointWriter {

    private final Channel channel;
    private final AtomicBoolean shouldFlush = new AtomicBoolean(false);

    private volatile ChannelFuture lastWriteFuture;


    public
    ChannelNetwork(Channel channel) {
        this.channel = channel;
    }

    /**
     * Write an object to the underlying channel
     */
    @Override
    public
    void write(Object object) {
        this.lastWriteFuture = this.channel.write(object);
        this.shouldFlush.set(true);
    }

    /**
     * Waits for the last write to complete. Useful when sending large amounts of data at once.
     * <b>DO NOT use this in the same thread as receiving messages! It will deadlock.</b>
     */
    @Override
    public
    void waitForWriteToComplete() {
        if (this.lastWriteFuture != null) {
            this.lastWriteFuture.awaitUninterruptibly();
        }
    }

    @Override
    public
    void flush() {
        if (this.shouldFlush.compareAndSet(true, false)) {
            this.channel.flush();
        }
    }

    public
    void close(long maxShutdownWaitTimeInMilliSeconds) {
        // Wait until all messages are flushed before closing the channel.
        if (this.lastWriteFuture != null) {
            this.lastWriteFuture.awaitUninterruptibly(maxShutdownWaitTimeInMilliSeconds);
            this.lastWriteFuture = null;
        }

        this.shouldFlush.set(false);
        this.channel.close()
                    .awaitUninterruptibly(maxShutdownWaitTimeInMilliSeconds);
    }

    public
    int id() {
        return this.channel.hashCode();
    }
}
