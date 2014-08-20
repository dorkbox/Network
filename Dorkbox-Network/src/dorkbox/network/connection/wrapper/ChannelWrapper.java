package dorkbox.network.connection.wrapper;


import org.bouncycastle.crypto.params.ParametersWithIV;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.ConnectionPoint;
import dorkbox.network.connection.ISessionManager;
import dorkbox.network.connection.ping.PingFuture;

public interface ChannelWrapper {

    public ConnectionPoint tcp();
    public ConnectionPoint udp();
    public ConnectionPoint udt();

    /**
     * Initialize the connection with any extra info that is needed but was unavailable at the channel construction.
     */
    public void init();

    /**
     * Flushes the contents of the TCP/UDP/UDT/etc pipes to the actual transport.
     */
    public void flush();

    public PingFuture pingFuture();

    public ParametersWithIV cryptoParameters();

    /**
     * @return the remote host (can be local, tcp, udp, udt)
     */
    public String getRemoteHost();

    public void close(final Connection connection, final ISessionManager sessionManager);

    @Override
    public String toString();

    public int id();

    @Override
    public boolean equals(Object obj);
}