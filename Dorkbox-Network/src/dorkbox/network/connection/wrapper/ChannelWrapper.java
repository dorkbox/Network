package dorkbox.network.connection.wrapper;


import dorkbox.network.connection.Connection;
import dorkbox.network.connection.ConnectionPointWriter;
import dorkbox.network.connection.ISessionManager;
import io.netty.channel.EventLoop;
import org.bouncycastle.crypto.params.ParametersWithIV;

public
interface ChannelWrapper {

    ConnectionPointWriter tcp();

    ConnectionPointWriter udp();

    ConnectionPointWriter udt();

    /**
     * Initialize the connection with any extra info that is needed but was unavailable at the channel construction.
     */
    void init();

    /**
     * Flushes the contents of the TCP/UDP/UDT/etc pipes to the actual transport.
     */
    void flush();

    EventLoop getEventLoop();

    ParametersWithIV cryptoParameters();

    /**
     * @return the remote host (can be local, tcp, udp, udt)
     */
    String getRemoteHost();

    void close(final Connection connection, final ISessionManager sessionManager);

    int id();

    @Override
    boolean equals(Object obj);

    @Override
    String toString();
}
