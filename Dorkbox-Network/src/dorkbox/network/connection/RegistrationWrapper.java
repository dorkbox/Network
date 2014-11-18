package dorkbox.network.connection;


import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.slf4j.Logger;

import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.pipeline.KryoEncoder;
import dorkbox.network.pipeline.KryoEncoderCrypto;
import dorkbox.network.util.exceptions.SecurityException;
import dorkbox.util.collections.IntMap;
import dorkbox.util.crypto.Crypto;

/**
 * Just wraps common/needed methods of the client/server endpoint by the registration stage/handshake.
 *
 * This is in the connection package, so it can access the endpoint methods that it needs to.
 */
public class RegistrationWrapper implements UdpServer {
    private final org.slf4j.Logger logger;

    private final EndPoint endPoint;

    // keeps track of connections (TCP/UDT/UDP-client)
    private final ReentrantLock channelMapLock = new ReentrantLock();
    private IntMap<MetaChannel> channelMap = new IntMap<MetaChannel>();

    // keeps track of connections (UDP-server)
    // this is final, because the REFERENCE to these will never change. They ARE NOT immutable objects (meaning their content can change)
    private final ConcurrentMap<InetSocketAddress, ConnectionImpl> udpRemoteMap;

    private KryoEncoder kryoTcpEncoder;
    private KryoEncoderCrypto kryoTcpCryptoEncoder;

    public RegistrationWrapper(EndPoint endPoint, Logger logger) {
        this.endPoint = endPoint;
        this.logger = logger;

        if (endPoint instanceof EndPointServer) {
            this.udpRemoteMap = new ConcurrentHashMap<InetSocketAddress, ConnectionImpl>();
        } else {
            this.udpRemoteMap = null;
        }
    }

    public void setKryoTcpEncoder(KryoEncoder kryoTcpEncoder) {
        this.kryoTcpEncoder = kryoTcpEncoder;
    }

    public void setKryoTcpCryptoEncoder(KryoEncoderCrypto kryoTcpCryptoEncoder) {
        this.kryoTcpCryptoEncoder = kryoTcpCryptoEncoder;
    }

    public KryoEncoder getKryoTcpEncoder() {
        return this.kryoTcpEncoder;
    }

    public KryoEncoderCrypto getKryoTcpCryptoEncoder() {
        return this.kryoTcpCryptoEncoder;
    }

    /**
     * Locks, and then returns the channelMap used by the registration process.
     * <p>
     * Make SURE to use this in a try/finally block with releaseChannelMap in the finally block!
     */
    public IntMap<MetaChannel> getAndLockChannelMap() {
        // try to lock access
        this.channelMapLock.lock();

        // guarantee that the contents of this map are visible across threads
        synchronized (this.channelMap) {}
        return this.channelMap;
    }

    public void releaseChannelMap() {
        // try to unlock access
        this.channelMapLock.unlock();
    }

    /**
     *  The amount of milli-seconds that must elapse with no read or write before {@link Listener:idle()}
     *  will be triggered
     */
    public int getIdleTimeout() {
        return this.endPoint.getIdleTimeout();
    }

    /**
     * Internal call by the pipeline to notify the client to continue registering the different session protocols.
     * The server does not use this.
     * @return true if we are done registering bootstraps
     */
    public boolean continueRegistration0() {
        return this.endPoint.continueRegistration0();
    }

    /**
     * Internal call by the pipeline to notify the "Connection" object that it has "connected", meaning that modifications
     * to the pipeline are finished.
     */
    public void connectionConnected0(Connection networkConnection) {
        this.endPoint.connectionConnected0(networkConnection);
    }

    /**
     * Internal call by the pipeline when:
     * - creating a new network connection
     * - when determining the baseClass for generics
     *
     * @param metaChannel can be NULL (when getting the baseClass)
     */
    public Connection connection0(MetaChannel metaChannel) {
        return this.endPoint.connection0(metaChannel);
    }

    public SecureRandom getSecureRandom() {
        return this.endPoint.secureRandom;
    }

    public ECPublicKeyParameters getPublicKey() {
        return this.endPoint.publicKey;
    }

    public CipherParameters getPrivateKey() {
        return this.endPoint.privateKey;
    }

    public boolean validateRemoteServerAddress(InetSocketAddress tcpRemoteServer, ECPublicKeyParameters publicKey) throws SecurityException {
        if (this.endPoint.disableRemoteKeyValidation) {
            return true;
        }

        InetAddress address = tcpRemoteServer.getAddress();
        byte[] hostAddress = address.getAddress();

        ECPublicKeyParameters savedPublicKey = this.endPoint.propertyStore.getRegisteredServerKey(hostAddress);
        Logger logger2 = this.logger;
        if (savedPublicKey == null) {
            if (logger2.isDebugEnabled()) {
                logger2.debug("Adding new remote IP address key for {}", address.getHostAddress());
            }
            this.endPoint.propertyStore.addRegisteredServerKey(hostAddress, publicKey);
        } else {
            // COMPARE!
            if (!Crypto.ECC.compare(publicKey, savedPublicKey)) {
                String byAddress;
                try {
                    byAddress = InetAddress.getByAddress(hostAddress).getHostAddress();
                } catch (UnknownHostException e) {
                    byAddress = "Unknown";
                }

                //whoa! abort since something messed up!
                logger2.error("Invalid or non-matching public key from remote server. Their public key has changed. To fix, remove entry for: {}", byAddress);
                return false;
            }
        }

        return true;
    }

    public void removeRegisteredServerKey(byte[] hostAddress) throws SecurityException {
        ECPublicKeyParameters savedPublicKey = this.endPoint.propertyStore.getRegisteredServerKey(hostAddress);
        if (savedPublicKey != null) {
            Logger logger2 = this.logger;
            if (logger2.isDebugEnabled()) {
                logger2.debug("Deleteing remote IP address key {}.{}.{}.{}", hostAddress[0], hostAddress[1], hostAddress[2], hostAddress[3]);
            }
            this.endPoint.propertyStore.removeRegisteredServerKey(hostAddress);
        }
    }

    /**
     * ONLY SERVER SIDE CALLS THIS
     * Called when creating a connection.
     * Only called if we have a UDP channel
     */
    @Override
    public final void registerServerUDP(MetaChannel metaChannel) {
        if (metaChannel != null && metaChannel.udpRemoteAddress != null) {
            this.udpRemoteMap.put(metaChannel.udpRemoteAddress, (ConnectionImpl) metaChannel.connection);

            Logger logger2 = this.logger;
            if (logger2.isDebugEnabled()) {
                logger2.debug("Connected to remote UDP connection. [{} <== {}]",
                                  metaChannel.udpChannel.localAddress().toString(),
                                  metaChannel.udpRemoteAddress.toString());
            }
        }
    }

    /**
     * ONLY SERVER SIDE CALLS THIS
     * Called when closing a connection.
     */
    @Override
    public final void unRegisterServerUDP(InetSocketAddress udpRemoteAddress) {
        if (udpRemoteAddress != null) {
            this.udpRemoteMap.remove(udpRemoteAddress);
            Logger logger2 = this.logger;
            if (logger2.isInfoEnabled()) {
                logger2.info("Closed remote UDP connection: {}", udpRemoteAddress.toString());
            }
        }
    }

    /**
     * ONLY SERVER SIDE CALLS THIS
     */
    @Override
    public ConnectionImpl getServerUDP(InetSocketAddress udpRemoteAddress) {
        if (udpRemoteAddress != null) {
            return this.udpRemoteMap.get(udpRemoteAddress);
        } else {
            return null;
        }
    }

    public void abortRegistrationIfClient() {
        if (this.endPoint instanceof EndPointClient) {
            ((EndPointClient)this.endPoint).abortRegistration();
        }
    }
}
