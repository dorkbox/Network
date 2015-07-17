package dorkbox.network.connection.wrapper;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.ConnectionPointWriter;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.ISessionManager;
import dorkbox.network.connection.registration.MetaChannel;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.channel.local.LocalAddress;
import org.bouncycastle.crypto.params.ParametersWithIV;

import java.util.concurrent.atomic.AtomicBoolean;

public
class ChannelLocalWrapper implements ChannelWrapper, ConnectionPointWriter {

    private final Channel channel;
    private String remoteAddress;

    private final AtomicBoolean shouldFlush = new AtomicBoolean(false);

    public
    ChannelLocalWrapper(MetaChannel metaChannel) {
        this.channel = metaChannel.localChannel;
    }

    /**
     * Write an object to the underlying channel
     */
    @Override
    public
    void write(Object object) {
        this.channel.write(object);
        this.shouldFlush.set(true);
    }

    @Override
    public
    void waitForWriteToComplete() {
        // it's immediate, since it's in the same JVM.
    }

    @Override
    public
    ConnectionPointWriter tcp() {
        return this;
    }

    @Override
    public
    ConnectionPointWriter udp() {
        return this;
    }

    @Override
    public
    ConnectionPointWriter udt() {
        return this;
    }

    /**
     * Initialize the connection with any extra info that is needed but was unavailable at the channel construction.
     */
    @Override
    public final
    void init() {
        this.remoteAddress = ((LocalAddress) this.channel.remoteAddress()).id();
    }

    /**
     * Flushes the contents of the LOCAL pipes to the actual transport.
     */
    @Override
    public
    void flush() {
        if (this.shouldFlush.compareAndSet(true, false)) {
            this.channel.flush();
        }
    }

    @Override
    public
    EventLoop getEventLoop() {
        return this.channel.eventLoop();
    }

    @Override
    public
    ParametersWithIV cryptoParameters() {
        return null;
    }

    @Override
    public final
    String getRemoteHost() {
        return this.remoteAddress;
    }

    @Override
    public
    void close(Connection connection, ISessionManager sessionManager) {
        long maxShutdownWaitTimeInMilliSeconds = EndPoint.maxShutdownWaitTimeInMilliSeconds;

        this.shouldFlush.set(false);

        // Wait until the connection is closed or the connection attempt fails.
        this.channel.close()
                    .awaitUninterruptibly(maxShutdownWaitTimeInMilliSeconds);
    }

    @Override
    public
    int id() {
        return this.channel.hashCode();
    }

    @Override
    public
    int hashCode() {
        return this.channel.hashCode();
    }

    @Override
    public
    boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        ChannelLocalWrapper other = (ChannelLocalWrapper) obj;
        if (this.remoteAddress == null) {
            if (other.remoteAddress != null) {
                return false;
            }
        }
        else if (!this.remoteAddress.equals(other.remoteAddress)) {
            return false;
        }
        return true;
    }
}
