package dorkbox.network.connection.wrapper;

import io.netty.channel.Channel;
import io.netty.channel.local.LocalAddress;

import java.util.concurrent.atomic.AtomicBoolean;

import org.bouncycastle.crypto.params.ParametersWithIV;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.ConnectionPoint;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.ISessionManager;
import dorkbox.network.connection.ping.PingFuture;
import dorkbox.network.connection.ping.PingFutureLocal;
import dorkbox.network.connection.registration.MetaChannel;

public class ChannelLocalWrapper implements ChannelWrapper, ConnectionPoint {

    private final Channel channel;
    private String remoteAddress;

    private AtomicBoolean shouldFlush = new AtomicBoolean(false);

    public ChannelLocalWrapper(MetaChannel metaChannel) {
        this.channel = metaChannel.localChannel;
    }

    /**
     * Initialize the connection with any extra info that is needed but was unavailable at the channel construction.
     */
    @Override
    public final void init() {
        remoteAddress = ((LocalAddress) this.channel.remoteAddress()).id();
    }

    @Override
    public void write(Object object) {
        channel.write(object);
        shouldFlush.set(true);
    }

    /**
     * Flushes the contents of the LOCAL pipes to the actual transport.
     */
    @Override
    public void flush() {
        if (shouldFlush.compareAndSet(true, false)) {
            channel.flush();
        }
    }

    @Override
    public void close(Connection connection, ISessionManager sessionManager) {
        long maxShutdownWaitTimeInMilliSeconds = EndPoint.maxShutdownWaitTimeInMilliSeconds;

        // Wait until the connection is closed or the connection attempt fails.
        channel.close().awaitUninterruptibly(maxShutdownWaitTimeInMilliSeconds);
    }

    @Override
    public ConnectionPoint tcp() {
        return this;
    }

    @Override
    public ConnectionPoint udp() {
        return this;
    }

    @Override
    public ConnectionPoint udt() {
        return this;
    }

    @Override
    public PingFuture pingFuture() {
        return new PingFutureLocal();
    }

    @Override
    public ParametersWithIV cryptoParameters() {
        return null;
    }

    @Override
    public final String getRemoteHost() {
        return remoteAddress;
    }

    @Override
    public int id() {
        return channel.hashCode();
    }

    @Override
    public int hashCode() {
        return channel.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
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
        } else if (!this.remoteAddress.equals(other.remoteAddress)) {
            return false;
        }
        return true;
    }
}
