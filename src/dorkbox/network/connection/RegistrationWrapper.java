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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.LinkedList;
import java.util.List;

import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.slf4j.Logger;

import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.pipeline.tcp.KryoEncoderTcp;
import dorkbox.network.pipeline.tcp.KryoEncoderTcpCompression;
import dorkbox.network.pipeline.tcp.KryoEncoderTcpCrypto;
import dorkbox.network.pipeline.tcp.KryoEncoderTcpNone;
import dorkbox.network.pipeline.udp.KryoDecoderUdp;
import dorkbox.network.pipeline.udp.KryoDecoderUdpCompression;
import dorkbox.network.pipeline.udp.KryoDecoderUdpCrypto;
import dorkbox.network.pipeline.udp.KryoDecoderUdpNone;
import dorkbox.network.pipeline.udp.KryoEncoderUdp;
import dorkbox.network.pipeline.udp.KryoEncoderUdpCompression;
import dorkbox.network.pipeline.udp.KryoEncoderUdpCrypto;
import dorkbox.network.pipeline.udp.KryoEncoderUdpNone;
import dorkbox.network.serialization.NetworkSerializationManager;
import dorkbox.util.collections.IntMap.Values;
import dorkbox.util.collections.LockFreeIntMap;
import dorkbox.util.crypto.CryptoECC;
import dorkbox.util.exceptions.SecurityException;
import io.netty.channel.Channel;

/**
 * Just wraps common/needed methods of the client/server endpoint by the registration stage/handshake.
 * <p/>
 * This is in the connection package, so it can access the endpoint methods that it needs to without having to publicly expose them
 */
public abstract
class RegistrationWrapper {

    public
    enum STATE { ERROR, WAIT, CONTINUE }

    final org.slf4j.Logger logger;
    final EndPoint endPoint;

    // keeps track of connections/sessions (TCP/UDP/Local). The session ID '0' is reserved to mean "no session ID yet"
    final LockFreeIntMap<MetaChannel> sessionMap = new LockFreeIntMap<MetaChannel>(32, ConnectionManager.LOAD_FACTOR);


    public final KryoEncoderTcp kryoTcpEncoder;
    public final KryoEncoderTcpNone kryoTcpEncoderNone;
    public final KryoEncoderTcpCompression kryoTcpEncoderCompression;
    public final KryoEncoderTcpCrypto kryoTcpEncoderCrypto;

    public final KryoEncoderUdp kryoUdpEncoder;
    public final KryoEncoderUdpNone kryoUdpEncoderNone;
    public final KryoEncoderUdpCompression kryoUdpEncoderCompression;
    public final KryoEncoderUdpCrypto kryoUdpEncoderCrypto;

    public final KryoDecoderUdp kryoUdpDecoder;
    public final KryoDecoderUdpNone kryoUdpDecoderNone;
    public final KryoDecoderUdpCompression kryoUdpDecoderCompression;
    public final KryoDecoderUdpCrypto kryoUdpDecoderCrypto;


    public
    RegistrationWrapper(final EndPoint endPoint,
                        final Logger logger) {
        this.endPoint = endPoint;
        this.logger = logger;

        this.kryoTcpEncoder = new KryoEncoderTcp(endPoint.serializationManager);
        this.kryoTcpEncoderNone = new KryoEncoderTcpNone(endPoint.serializationManager);
        this.kryoTcpEncoderCompression = new KryoEncoderTcpCompression(endPoint.serializationManager);
        this.kryoTcpEncoderCrypto = new KryoEncoderTcpCrypto(endPoint.serializationManager);


        this.kryoUdpEncoder = new KryoEncoderUdp(endPoint.serializationManager);
        this.kryoUdpEncoderNone = new KryoEncoderUdpNone(endPoint.serializationManager);
        this.kryoUdpEncoderCompression = new KryoEncoderUdpCompression(endPoint.serializationManager);
        this.kryoUdpEncoderCrypto = new KryoEncoderUdpCrypto(endPoint.serializationManager);


        this.kryoUdpDecoder = new KryoDecoderUdp(endPoint.serializationManager);
        this.kryoUdpDecoderNone = new KryoDecoderUdpNone(endPoint.serializationManager);
        this.kryoUdpDecoderCompression = new KryoDecoderUdpCompression(endPoint.serializationManager);
        this.kryoUdpDecoderCrypto = new KryoDecoderUdpCrypto(endPoint.serializationManager);
    }

    public
    NetworkSerializationManager getSerialization() {
        return endPoint.getSerialization();
    }

    /**
     * The amount of milli-seconds that must elapse with no read or write before Listener.OnIdle() will be triggered
     */
    public
    int getIdleTimeout() {
        return this.endPoint.getIdleTimeout();
    }

    /**
     * Internal call by the pipeline to notify the "Connection" object that it has "connected".
     */
    public
    void connectionConnected0(ConnectionImpl connection) {
        this.endPoint.connectionConnected0(connection);
    }

    /**
     * Internal call by the pipeline when: - creating a new network connection - when determining the baseClass for generics
     *
     * @param metaChannel can be NULL (when getting the baseClass)
     */
    public
    ConnectionImpl connection0(MetaChannel metaChannel, final InetSocketAddress remoteAddress) {
        return this.endPoint.connection0(metaChannel, remoteAddress);
    }

    public
    SecureRandom getSecureRandom() {
        return this.endPoint.secureRandom;
    }

    public
    ECPublicKeyParameters getPublicKey() {
        return this.endPoint.publicKey;
    }






    /**
     * If the key does not match AND we have disabled remote key validation, then metachannel.changedRemoteKey = true. OTHERWISE, key validation is REQUIRED!
     *
     * @return true if the remote address public key matches the one saved or we disabled remote key validation.
     */
    public
    boolean validateRemoteAddress(final MetaChannel metaChannel, final InetSocketAddress remoteAddress, final ECPublicKeyParameters publicKey) {
        InetAddress address = remoteAddress.getAddress();
        byte[] hostAddress = address.getAddress();

        try {
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
                if (!CryptoECC.compare(publicKey, savedPublicKey)) {
                    if (this.endPoint.disableRemoteKeyValidation) {
                        logger2.warn("Invalid or non-matching public key from remote connection, their public key has changed. Toggling extra flag in channel to indicate key change. To fix, remove entry for: {}", address.getHostAddress());

                        metaChannel.changedRemoteKey = true;
                        return true;
                    }
                    else {
                        // keys do not match, abort!
                        logger2.error("Invalid or non-matching public key from remote connection, their public key has changed. To fix, remove entry for: {}", address.getHostAddress());
                        return false;
                    }
                }
            }
        } catch (SecurityException e) {
            return false;
        }

        return true;
    }




    /**
     * The session ID '0' is reserved to mean "no session ID yet"
     */
    public
    MetaChannel getSession(final int sessionId) {
        return sessionMap.get(sessionId);
    }

    /**
     * The SERVER AND CLIENT will stop tracking a session once the session is complete.
     */
    public
    void removeSession(final MetaChannel metaChannel) {
        int sessionId = metaChannel.sessionId;
        if (sessionId != 0) {
            sessionMap.remove(sessionId);
        }
    }

    /**
     * The CLIENT/SERVER will stop tracking a session if there are errors
     */
    public
    void closeSession(final int sessionId) {
        if (sessionId != 0) {
            MetaChannel metaChannel = sessionMap.remove(sessionId);
            if (metaChannel != null) {
                if (metaChannel.tcpChannel != null && metaChannel.tcpChannel.isOpen()) {
                    metaChannel.tcpChannel.close();
                }
                if (metaChannel.udpChannel != null && metaChannel.udpChannel.isOpen()) {
                    metaChannel.udpChannel.close();
                }
            }
        }
    }

    /**
     * Remove all session associations (keeps the server/client running).
     */
    public
    void clearSessions() {
        List<Channel> channels = new LinkedList<Channel>();

        synchronized (sessionMap) {
            Values<MetaChannel> values = sessionMap.values();
            for (MetaChannel metaChannel : values) {
                if (metaChannel.tcpChannel != null && metaChannel.tcpChannel.isOpen()) {
                    channels.add(metaChannel.tcpChannel);
                }
                if (metaChannel.udpChannel != null && metaChannel.udpChannel.isOpen()) {
                    channels.add(metaChannel.udpChannel);
                }
            }

            // remote all session associations. Any session in progress will have to restart it's registration process
            sessionMap.clear();
        }

        // close all "in progress" registrations as well
        for (Channel channel : channels) {
            channel.close();
        }
    }
}
