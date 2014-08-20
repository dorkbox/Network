package dorkbox.network.connection.wrapper;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Promise;

import java.net.InetSocketAddress;

import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.ConnectionPoint;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.ISessionManager;
import dorkbox.network.connection.UdpServer;
import dorkbox.network.connection.ping.PingFuture;
import dorkbox.network.connection.registration.MetaChannel;

public class ChannelNetworkWrapper implements ChannelWrapper {

    private final ChannelNetwork tcp;
    private final ChannelNetwork udp;
    private final ChannelNetwork udt;

    private final ParametersWithIV cryptoAesKeyAndIV;

    // did the remote connection public ECC key change?
    private final boolean remotePublicKeyChanged;

    private final String remoteAddress;
    private final EventLoop eventLoop;


    /**
     * @param udpServer is null when created by the client, non-null when created by the server
     */
    public ChannelNetworkWrapper(MetaChannel metaChannel, UdpServer udpServer) {

        Channel tcpChannel = metaChannel.tcpChannel;
        eventLoop = tcpChannel.eventLoop();

        tcp = new ChannelNetwork(tcpChannel);

        if (metaChannel.udpChannel != null) {
            if (metaChannel.udpRemoteAddress != null) {
                udp = new ChannelNetworkUdp(metaChannel.udpChannel, metaChannel.udpRemoteAddress, udpServer);
            } else {
                udp = new ChannelNetwork(metaChannel.udpChannel);
            }
        } else {
            udp = null;
        }

        if (metaChannel.udtChannel != null) {
            udt = new ChannelNetwork(metaChannel.udtChannel);
        } else {
            udt = null;
        }


        remoteAddress = ((InetSocketAddress)tcpChannel.remoteAddress()).getAddress().getHostAddress();
        remotePublicKeyChanged = metaChannel.changedRemoteKey;

        // AES key & IV (only for networked connections)
        cryptoAesKeyAndIV = new ParametersWithIV(new KeyParameter(metaChannel.aesKey), metaChannel.aesIV);
        // TODO: have to be able to get a NEW IV, so we can rotate keys!
    }

    /**
     * Initialize the connection with any extra info that is needed but was unavailable at the channel construction.
     */
    @Override
    public final void init() {
        // nothing to do.
    }

    public final boolean remoteKeyChanged() {
        return remotePublicKeyChanged;
    }

    /**
     * Flushes the contents of the TCP/UDP/UDT/etc pipes to the actual transport.
     */
    @Override
    public void flush() {
        tcp.flush();

        if (udp != null) {
            udp.flush();
        }

        if (udt != null) {
            udt.flush();
        }
    }

    @Override
    public ParametersWithIV cryptoParameters() {
        return cryptoAesKeyAndIV;
    }

    @Override
    public ConnectionPoint tcp() {
        return tcp;
    }

    @Override
    public ConnectionPoint udp() {
        return udp;
    }

    @Override
    public ConnectionPoint udt() {
        return udt;
    }

    @Override
    public PingFuture pingFuture() {
        Promise<Integer> newPromise = eventLoop.newPromise();
        return new PingFuture(newPromise);
    }

    @Override
    public String getRemoteHost() {
        return remoteAddress;
    }


    @Override
    public void close(final Connection connection, final ISessionManager sessionManager) {
        long maxShutdownWaitTimeInMilliSeconds = EndPoint.maxShutdownWaitTimeInMilliSeconds;

        tcp.close(maxShutdownWaitTimeInMilliSeconds);

        if (udp != null) {
            udp.close(maxShutdownWaitTimeInMilliSeconds);
        }

        if (udt != null) {
            udt.close(maxShutdownWaitTimeInMilliSeconds);

            // we need to yield the thread here, so that the socket has a chance to close
            Thread.yield();
        }
    }

    @Override
    public String toString() {
        return "NetworkConnection [" + getRemoteHost() + "]";
    }

    @Override
    public int id() {
        return tcp.id();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!super.equals(obj)) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }

        ChannelNetworkWrapper other = (ChannelNetworkWrapper) obj;
        if (remoteAddress == null) {
            if (other.remoteAddress != null) {
                return false;
            }
        } else if (!remoteAddress.equals(other.remoteAddress)) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        return remoteAddress.hashCode();
    }
}