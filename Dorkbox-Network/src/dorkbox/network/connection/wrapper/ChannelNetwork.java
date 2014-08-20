package dorkbox.network.connection.wrapper;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import dorkbox.network.connection.ConnectionPoint;

public class ChannelNetwork implements ConnectionPoint {

    private volatile ChannelFuture lastWriteFuture;
    private final Channel channel;

    private volatile boolean shouldFlush = false;


    public ChannelNetwork(Channel channel) {
        this.channel = channel;
    }

    @Override
    public void write(Object object) {
        shouldFlush = true;
        lastWriteFuture = channel.write(object);
    }

    @Override
    public void flush() {
        if (shouldFlush) {
            shouldFlush = false;
            channel.flush();
        }
    }

    public void close(long maxShutdownWaitTimeInMilliSeconds) {
        // Wait until all messages are flushed before closing the channel.
        if (lastWriteFuture != null) {
            lastWriteFuture.awaitUninterruptibly(maxShutdownWaitTimeInMilliSeconds);
            lastWriteFuture = null;
        }

        shouldFlush = false;
        channel.close().awaitUninterruptibly(maxShutdownWaitTimeInMilliSeconds);
    }

    public int id() {
        return channel.hashCode();
    }
}
