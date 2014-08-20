package dorkbox.network.connection;


import java.net.InetSocketAddress;

import dorkbox.network.connection.registration.MetaChannel;

public interface UdpServer {
    /**
     * Called when creating a connection.
     */
    public void registerServerUDP(MetaChannel metaChannel);

    /**
     * Called when closing a connection.
     */
    public void unRegisterServerUDP(InetSocketAddress udpRemoteAddress);

    /**
     * @return the connection for a remote UDP address
     */
    public ConnectionImpl getServerUDP(InetSocketAddress udpRemoteAddress);
}
