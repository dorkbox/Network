package dorkbox.network.connection.registration;

import io.netty.channel.Channel;

import java.net.InetSocketAddress;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;

import dorkbox.network.connection.ConnectionImpl;


public class MetaChannel {
    // how long between receiving data over TCP. This is used to determine how long to wait before notifying the APP,
    // so the registration message has time to arrive to the other endpoint.
    private volatile long nanoSecBetweenTCP = 0L;


    public Integer connectionID = null; // only used during the registration process
    public Channel localChannel = null; // only available for local "in jvm" channels. XOR with tcp/udp channels with CLIENT.
    public Channel tcpChannel   = null;

    // channel here (on server or socket.bind connections) doesn't have the remote address available.
    // It is apart of the inbound message, however.
    // ALSO not necessary to close it, since the server handles that.
    public Channel udpChannel   = null;
    public InetSocketAddress udpRemoteAddress = null; // SERVER ONLY. needed to be aware of the remote address to send UDP replies to

    public Channel udtChannel   = null;

    public ConnectionImpl connection; // only needed until the connection has been notified.

    public ECPublicKeyParameters publicKey; // used for ECC crypto + handshake on NETWORK (remote) connections. This is the remote public key.

    public AsymmetricCipherKeyPair ecdhKey; // used for ECC Diffie-Hellman-Merkle key exchanges: see http://en.wikipedia.org/wiki/Diffie%E2%80%93Hellman_key_exchange

    public byte[] aesKey;
    public byte[] aesIV;


    // indicates if the remote ECC key has changed for an IP address. If the client detects this, it will not connect.
    // If the server detects this, it has the option for additional security (two-factor auth, perhaps?)
    public boolean changedRemoteKey = false;

    public void close() {
        if (localChannel != null) {
            localChannel.close();
        }

        if (tcpChannel != null) {
            tcpChannel.close();
        }

        if (udtChannel != null) {
            udtChannel.close();
        }

        // only the CLIENT will have this.
        if (udpChannel != null && udpRemoteAddress == null) {
            udpChannel.close();
        }
    }

    public void close(long maxShutdownWaitTimeInMilliSeconds) {
        if (localChannel != null) {
            localChannel.close();
        }

        if (tcpChannel != null) {
            tcpChannel.close().awaitUninterruptibly(maxShutdownWaitTimeInMilliSeconds);
        }

        if (udtChannel != null) {
            udtChannel.close().awaitUninterruptibly(maxShutdownWaitTimeInMilliSeconds);
        }

        // only the CLIENT will have this.
        if (udpChannel != null && udpRemoteAddress == null) {
            udpChannel.close().awaitUninterruptibly(maxShutdownWaitTimeInMilliSeconds);
        }
    }

    /**
     * Update the TCP round trip time. Make sure to REFRESH this every time you SEND TCP data!!
     */
    public void updateTcpRoundTripTime() {
        nanoSecBetweenTCP = System.nanoTime() - nanoSecBetweenTCP;
    }

    public long getNanoSecBetweenTCP() {
        return nanoSecBetweenTCP;
    }
}