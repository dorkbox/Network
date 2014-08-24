package dorkbox.network.connection.wrapper;

import io.netty.channel.Channel;

import java.net.InetSocketAddress;

import dorkbox.network.connection.UdpServer;
import dorkbox.network.util.exceptions.NetException;

public class ChannelNetworkUdp extends ChannelNetwork {

    private final InetSocketAddress udpRemoteAddress;
    private final UdpServer udpServer;

    public ChannelNetworkUdp(Channel channel, InetSocketAddress udpRemoteAddress, UdpServer udpServer) {
        super(channel);

        if (udpRemoteAddress == null) {
            throw new NetException("Cannot create a server UDP channel wihtout a remote udp address!");
        }

        this.udpRemoteAddress = udpRemoteAddress;
        this.udpServer = udpServer; // ONLY valid in the server!
    }

    @Override
    public void write(Object object) {
        // this shoots out the SERVER pipeline, which is SLIGHTLY different!
        super.write(new UdpWrapper(object, udpRemoteAddress));
    }

    @Override
    public void close(long maxShutdownWaitTimeInMilliSeconds) {
        // we ONLY want to close the UDP channel when we are STOPPING the server, otherwise we close the UDP channel
        // that listens for new connections!   SEE Server.close().
        // super.close(maxShutdownWaitTimeInMilliSeconds);

        // need to UNREGISTER the address from my ChannelManager.
        if (udpServer != null) {
            // only the server does this.
            udpServer.unRegisterServerUDP(udpRemoteAddress);
        }
    }
}
