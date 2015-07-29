/*
 * Copyright 2010 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.network.connection;

import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.pipeline.KryoEncoder;
import dorkbox.network.pipeline.KryoEncoderCrypto;
import dorkbox.util.collections.IntMap;
import dorkbox.util.crypto.Crypto;
import dorkbox.util.exceptions.SecurityException;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.slf4j.Logger;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Just wraps common/needed methods of the client/server endpoint by the registration stage/handshake.
 * <p/>
 * This is in the connection package, so it can access the endpoint methods that it needs to.
 */
public
class RegistrationWrapper<C extends Connection> implements UdpServer {
    private final org.slf4j.Logger logger;

    private final KryoEncoder kryoEncoder;
    private final KryoEncoderCrypto kryoEncoderCrypto;

    private final EndPoint<C> endPoint;

    // keeps track of connections (TCP/UDT/UDP-client)
    private final ReentrantLock channelMapLock = new ReentrantLock();
    private final IntMap<MetaChannel> channelMap = new IntMap<MetaChannel>();

    // keeps track of connections (UDP-server)
    // this is final, because the REFERENCE to these will never change. They ARE NOT immutable objects (meaning their content can change)
    private final ConcurrentMap<InetSocketAddress, ConnectionImpl> udpRemoteMap;


    public
    RegistrationWrapper(final EndPoint<C> endPoint,
                        final Logger logger,
                        final KryoEncoder kryoEncoder,
                        final KryoEncoderCrypto kryoEncoderCrypto) {
        this.endPoint = endPoint;
        this.logger = logger;
        this.kryoEncoder = kryoEncoder;
        this.kryoEncoderCrypto = kryoEncoderCrypto;

        if (endPoint instanceof EndPointServer) {
            this.udpRemoteMap = new ConcurrentHashMap<InetSocketAddress, ConnectionImpl>();
        }
        else {
            this.udpRemoteMap = null;
        }
    }

    /**
     * @return true if RMI is enabled
     */
    public
    boolean rmiEnabled() {
        return endPoint.globalRmiBridge != null;
    }

    public
    KryoEncoder getKryoEncoder() {
        return this.kryoEncoder;
    }

    public
    KryoEncoderCrypto getKryoEncoderCrypto() {
        return this.kryoEncoderCrypto;
    }

    /**
     * Locks, and then returns the channelMap used by the registration process.
     * <p/>
     * Make SURE to use this in a try/finally block with releaseChannelMap in the finally block!
     */
    public
    IntMap<MetaChannel> getAndLockChannelMap() {
        // try to lock access
        this.channelMapLock.lock();

        // guarantee that the contents of this map are visible across threads
        synchronized (this.channelMap) {
        }
        return this.channelMap;
    }

    public
    void releaseChannelMap() {
        // try to unlock access
        this.channelMapLock.unlock();
    }

    /**
     * The amount of milli-seconds that must elapse with no read or write before {@link Listener:idle()} will be triggered
     */
    public
    int getIdleTimeout() {
        return this.endPoint.getIdleTimeout();
    }

    /**
     * Internal call by the pipeline to notify the client to continue registering the different session protocols. The server does not use
     * this.
     *
     * @return true if we are done registering bootstraps
     */
    public
    boolean registerNextProtocol0() {
        return this.endPoint.registerNextProtocol0();
    }

    /**
     * Internal call by the pipeline to notify the "Connection" object that it has "connected", meaning that modifications to the pipeline
     * are finished.
     */
    public
    void connectionConnected0(ConnectionImpl networkConnection) {
        this.endPoint.connectionConnected0(networkConnection);
    }

    /**
     * Internal call by the pipeline when: - creating a new network connection - when determining the baseClass for generics
     *
     * @param metaChannel
     *                 can be NULL (when getting the baseClass)
     */
    public
    Connection connection0(MetaChannel metaChannel) {
        return this.endPoint.connection0(metaChannel);
    }

    public
    SecureRandom getSecureRandom() {
        return this.endPoint.secureRandom;
    }

    public
    ECPublicKeyParameters getPublicKey() {
        return this.endPoint.publicKey;
    }

    public
    CipherParameters getPrivateKey() {
        return this.endPoint.privateKey;
    }

    public
    boolean validateRemoteServerAddress(final InetSocketAddress tcpRemoteServer, final ECPublicKeyParameters publicKey)
                    throws SecurityException {
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
        }
        else {
            // COMPARE!
            if (!Crypto.ECC.compare(publicKey, savedPublicKey)) {
                String byAddress;
                try {
                    byAddress = InetAddress.getByAddress(hostAddress)
                                           .getHostAddress();
                } catch (UnknownHostException e) {
                    byAddress = "Unknown";
                }

                //whoa! abort since something messed up!
                logger2.error("Invalid or non-matching public key from remote server. Their public key has changed. To fix, remove entry for: {}",
                              byAddress);
                return false;
            }
        }

        return true;
    }

    @SuppressWarnings("AutoBoxing")
    public
    void removeRegisteredServerKey(final byte[] hostAddress) throws SecurityException {
        ECPublicKeyParameters savedPublicKey = this.endPoint.propertyStore.getRegisteredServerKey(hostAddress);
        if (savedPublicKey != null) {
            Logger logger2 = this.logger;
            if (logger2.isDebugEnabled()) {
                logger2.debug("Deleteing remote IP address key {}.{}.{}.{}",
                              hostAddress[0],
                              hostAddress[1],
                              hostAddress[2],
                              hostAddress[3]);
            }
            this.endPoint.propertyStore.removeRegisteredServerKey(hostAddress);
        }
    }

    /**
     * ONLY SERVER SIDE CALLS THIS Called when creating a connection. Only called if we have a UDP channel
     */
    @Override
    public final
    void registerServerUDP(final MetaChannel metaChannel) {
        if (metaChannel != null && metaChannel.udpRemoteAddress != null) {
            this.udpRemoteMap.put(metaChannel.udpRemoteAddress, metaChannel.connection);

            Logger logger2 = this.logger;
            if (logger2.isDebugEnabled()) {
                logger2.debug("Connected to remote UDP connection. [{} <== {}]",
                              metaChannel.udpChannel.localAddress()
                                                    .toString(),
                              metaChannel.udpRemoteAddress.toString());
            }
        }
    }

    /**
     * ONLY SERVER SIDE CALLS THIS Called when closing a connection.
     */
    @Override
    public final
    void unRegisterServerUDP(final InetSocketAddress udpRemoteAddress) {
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
    public
    ConnectionImpl getServerUDP(final InetSocketAddress udpRemoteAddress) {
        if (udpRemoteAddress != null) {
            return this.udpRemoteMap.get(udpRemoteAddress);
        }
        else {
            return null;
        }
    }

    public
    void abortRegistrationIfClient() {
        if (this.endPoint instanceof EndPointClient) {
            ((EndPointClient<C>) this.endPoint).abortRegistration();
        }
    }
}
