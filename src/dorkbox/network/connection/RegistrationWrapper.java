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
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.LinkedList;
import java.util.List;

import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.slf4j.Logger;

import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import dorkbox.network.pipeline.tcp.KryoEncoder;
import dorkbox.network.pipeline.tcp.KryoEncoderCrypto;
import dorkbox.network.pipeline.udp.KryoDecoderUdp;
import dorkbox.network.pipeline.udp.KryoDecoderUdpCrypto;
import dorkbox.network.pipeline.udp.KryoEncoderUdp;
import dorkbox.network.pipeline.udp.KryoEncoderUdpCrypto;
import dorkbox.network.serialization.CryptoSerializationManager;
import dorkbox.util.RandomUtil;
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
public
class RegistrationWrapper {
    public
    enum STATE { ERROR, WAIT, CONTINUE }

    private final org.slf4j.Logger logger;

    public final KryoEncoder kryoTcpEncoder;
    public final KryoEncoderCrypto kryoTcpEncoderCrypto;

    public final KryoEncoderUdp kryoUdpEncoder;
    public final KryoEncoderUdpCrypto kryoUdpEncoderCrypto;
    public final KryoDecoderUdp kryoUdpDecoder;
    public final KryoDecoderUdpCrypto kryoUdpDecoderCrypto;

    private final EndPoint endPoint;

    // keeps track of connections/sessions (TCP/UDP/Local). The session ID '0' is reserved to mean "no session ID yet"
    private final LockFreeIntMap<MetaChannel> sessionMap = new LockFreeIntMap<MetaChannel>(32, ConnectionManager.LOAD_FACTOR);

    public
    RegistrationWrapper(final EndPoint endPoint,
                        final Logger logger) {
        this.endPoint = endPoint;
        this.logger = logger;

        this.kryoTcpEncoder = new KryoEncoder(endPoint.serializationManager);
        this.kryoTcpEncoderCrypto = new KryoEncoderCrypto(endPoint.serializationManager);


        this.kryoUdpEncoder = new KryoEncoderUdp(endPoint.serializationManager);
        this.kryoUdpEncoderCrypto = new KryoEncoderUdpCrypto(endPoint.serializationManager);

        this.kryoUdpDecoder = new KryoDecoderUdp(endPoint.serializationManager);
        this.kryoUdpDecoderCrypto = new KryoDecoderUdpCrypto(endPoint.serializationManager);
    }

    public
    CryptoSerializationManager getSerialization() {
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
     * Internal call by the pipeline to check if the client has more protocol registrations to complete.
     *
     * @return true if there are more registrations to process, false if we are 100% done with all types to register (TCP/UDP/etc)
     */
    public
    boolean hasMoreRegistrations() {
        return this.endPoint.hasMoreRegistrations();
    }

    /**
     * Internal call by the pipeline to notify the client to continue registering the different session protocols. The server does not use
     * this.
     */
    public
    void startNextProtocolRegistration() {
        this.endPoint.startNextProtocolRegistration();
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
                    String byAddress;
                    try {
                        byAddress = InetAddress.getByAddress(hostAddress)
                                               .getHostAddress();
                    } catch (UnknownHostException e) {
                        byAddress = "Unknown Address";
                    }

                    if (this.endPoint.disableRemoteKeyValidation) {
                        logger2.warn("Invalid or non-matching public key from remote connection, their public key has changed. Toggling extra flag in channel to indicate key change. To fix, remove entry for: {}", byAddress);

                        metaChannel.changedRemoteKey = true;
                        return true;
                    }
                    else {
                        // keys do not match, abort!
                        logger2.error("Invalid or non-matching public key from remote connection, their public key has changed. To fix, remove entry for: {}", byAddress);
                        return false;
                    }
                }
            }
        } catch (SecurityException e) {
            return false;
        }

        return true;
    }

    public
    void removeRegisteredServerKey(final byte[] hostAddress) throws SecurityException {
        ECPublicKeyParameters savedPublicKey = this.endPoint.propertyStore.getRegisteredServerKey(hostAddress);
        if (savedPublicKey != null) {
            Logger logger2 = this.logger;
            if (logger2.isDebugEnabled()) {
                logger2.debug("Deleting remote IP address key {}.{}.{}.{}",
                              hostAddress[0],
                              hostAddress[1],
                              hostAddress[2],
                              hostAddress[3]);
            }

            this.endPoint.propertyStore.removeRegisteredServerKey(hostAddress);
        }
    }


    public
    boolean isClient() {
        return (this.endPoint instanceof EndPointClient);
    }



    /**
     * MetaChannel allow access to the same "session" across TCP/UDP/etc
     * <p>
     * The connection ID '0' is reserved to mean "no channel ID yet"
     */
    public
    MetaChannel createSessionClient(int sessionId) {
        MetaChannel metaChannel = new MetaChannel(sessionId);
        sessionMap.put(sessionId, metaChannel);

        return metaChannel;
    }

    /**
     * MetaChannel allow access to the same "session" across TCP/UDP/etc.
     * <p>
     * The connection ID '0' is reserved to mean "no channel ID yet"
     */
    public
    MetaChannel createSessionServer() {
        int sessionId = RandomUtil.int_();
        while (sessionId == 0 && sessionMap.containsKey(sessionId)) {
            sessionId = RandomUtil.int_();
        }

        MetaChannel metaChannel;
        synchronized (sessionMap) {
            // one final check, but slower...
            while (sessionId == 0 && sessionMap.containsKey(sessionId)) {
                sessionId = RandomUtil.int_();
            }

            metaChannel = new MetaChannel(sessionId);
            sessionMap.put(sessionId, metaChannel);


            // TODO: clean out sessions that are stale!
        }

        return metaChannel;
    }

    /**
     * The session ID '0' is reserved to mean "no session ID yet"
     */
    public
    MetaChannel getSession(final int sessionId) {
        return sessionMap.get(sessionId);
    }

    /**
     * @return the first session we have available. This is for the CLIENT to track sessions (between TCP/UDP) to a server
     */
    public MetaChannel getFirstSession() {
        Values<MetaChannel> values = sessionMap.values();
        if (values.hasNext) {
            return values.next();
        }
        return null;
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
     * The SERVER will stop tracking a session if there are errors
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


    public
    boolean initClassRegistration(final Channel channel, final Registration registration) {
        byte[] details = this.endPoint.getSerialization().getKryoRegistrationDetails();

        int length = details.length;
        if (length > 480) {
            // it is too large to send in a single packet

            // child arrays have index 0 also as their 'index' and 1 is the total number of fragments
            byte[][] fragments = divideArray(details, 480);
            if (fragments == null) {
                logger.error("Too many classes have been registered for Serialization. Please report this issue");

                return false;
            }

            int allButLast = fragments.length - 1;

            for (int i = 0; i < allButLast; i++) {
                final byte[] fragment = fragments[i];
                Registration fragmentedRegistration = new Registration(registration.sessionID);
                fragmentedRegistration.payload = fragment;

                // tell the server we are fragmented
                fragmentedRegistration.upgrade = true;

                // tell the server we are upgraded (it will bounce back telling us to connect)
                fragmentedRegistration.upgraded = true;
                channel.writeAndFlush(fragmentedRegistration);
            }

            // now tell the server we are done with the fragments
            Registration fragmentedRegistration = new Registration(registration.sessionID);
            fragmentedRegistration.payload = fragments[allButLast];

            // tell the server we are fragmented
            fragmentedRegistration.upgrade = true;

            // tell the server we are upgraded (it will bounce back telling us to connect)
            fragmentedRegistration.upgraded = true;
            channel.writeAndFlush(fragmentedRegistration);
        } else {
            registration.payload = details;

            // tell the server we are upgraded (it will bounce back telling us to connect)
            registration.upgraded = true;
            channel.writeAndFlush(registration);
        }

        return true;
    }

    public
    STATE verifyClassRegistration(final MetaChannel metaChannel, final Registration registration) {
        if (registration.upgrade) {
            byte[] fragment = registration.payload;

            // this means that the registrations are FRAGMENTED!
            // max size of ALL fragments is 480 * 127
            if (metaChannel.fragmentedRegistrationDetails == null) {
                metaChannel.remainingFragments = fragment[1];
                metaChannel.fragmentedRegistrationDetails = new byte[480 * fragment[1]];
            }

            System.arraycopy(fragment, 2, metaChannel.fragmentedRegistrationDetails, fragment[0] * 480, fragment.length - 2);
            metaChannel.remainingFragments--;


            if (fragment[0] + 1 == fragment[1]) {
                // this is the last fragment in the in byte array (but NOT necessarily the last fragment to arrive)
                int correctSize = (480 * (fragment[1] - 1)) + (fragment.length - 2);
                byte[] correctlySized = new byte[correctSize];
                System.arraycopy(metaChannel.fragmentedRegistrationDetails, 0, correctlySized, 0, correctSize);
                metaChannel.fragmentedRegistrationDetails = correctlySized;
            }

            if (metaChannel.remainingFragments == 0) {
                // there are no more fragments available
                byte[] details = metaChannel.fragmentedRegistrationDetails;
                metaChannel.fragmentedRegistrationDetails = null;

                if (!this.endPoint.getSerialization().verifyKryoRegistration(details)) {
                    // error
                    return STATE.ERROR;
                }
            } else {
                // wait for more fragments
                return STATE.WAIT;
            }
        }
        else {
            if (!this.endPoint.getSerialization().verifyKryoRegistration(registration.payload)) {
                return STATE.ERROR;
            }
        }

        return STATE.CONTINUE;
    }

    /**
     * Split array into chunks, max of 256 chunks.
     * byte[0] = chunk ID
     * byte[1] = total chunks (0-255) (where 0->1, 2->3, 127->127 because this is indexed by a byte)
     */
    private static
    byte[][] divideArray(byte[] source, int chunksize) {

        int fragments = (int) Math.ceil(source.length / ((double) chunksize + 2));
        if (fragments > 127) {
            // cannot allow more than 127
            return null;
        }

        // pre-allocate the memory
        byte[][] splitArray = new byte[fragments][chunksize + 2];
        int start = 0;

        for (int i = 0; i < splitArray.length; i++) {
            int length;

            if (start + chunksize > source.length) {
                length = source.length - start;
            }
            else {
                length = chunksize;
            }
            splitArray[i] = new byte[length+2];
            splitArray[i][0] = (byte) i;
            splitArray[i][1] = (byte) fragments;
            System.arraycopy(source, start, splitArray[i], 2, length);

            start += chunksize;
        }

        return splitArray;
    }
}
