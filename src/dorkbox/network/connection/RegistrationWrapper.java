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

import static dorkbox.network.connection.registration.remote.RegistrationRemoteHandler.checkEqual;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.locks.ReentrantLock;

import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.slf4j.Logger;

import com.esotericsoftware.kryo.util.ObjectMap;

import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.remote.RegistrationRemoteHandler;
import dorkbox.network.pipeline.KryoEncoder;
import dorkbox.network.pipeline.KryoEncoderCrypto;
import dorkbox.util.RandomUtil;
import dorkbox.util.collections.IntMap;
import dorkbox.util.crypto.CryptoECC;
import dorkbox.util.exceptions.SecurityException;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;

/**
 * Just wraps common/needed methods of the client/server endpoint by the registration stage/handshake.
 * <p/>
 * This is in the connection package, so it can access the endpoint methods that it needs to (without having to publicly expose them)
 */
public
class RegistrationWrapper implements UdpServer {
    private final org.slf4j.Logger logger;

    private final KryoEncoder kryoEncoder;
    private final KryoEncoderCrypto kryoEncoderCrypto;

    private final EndPoint endPointConnection;

    // keeps track of connections (TCP/UDP-client)
    private final ReentrantLock channelMapLock = new ReentrantLock();
    private final IntMap<MetaChannel> channelMap = new IntMap<MetaChannel>();

    // keeps track of connections (UDP-server)
    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private volatile ObjectMap<InetSocketAddress, ConnectionImpl> udpRemoteMap;


    // synchronized is used here to ensure the "single writer principle", and make sure that ONLY one thread at a time can enter this
    // section. Because of this, we can have unlimited reader threads all going at the same time, without contention (which is our
    // use-case 99% of the time)
    private final Object singleWriterLock1 = new Object();

    // Recommended for best performance while adhering to the "single writer principle". Must be static-final
    private static final AtomicReferenceFieldUpdater<RegistrationWrapper, ObjectMap> udpRemoteMapREF =
                    AtomicReferenceFieldUpdater.newUpdater(RegistrationWrapper.class,
                                                           ObjectMap.class,
                                                           "udpRemoteMap");


    public
    RegistrationWrapper(final EndPoint endPointConnection,
                        final Logger logger,
                        final KryoEncoder kryoEncoder,
                        final KryoEncoderCrypto kryoEncoderCrypto) {
        this.endPointConnection = endPointConnection;
        this.logger = logger;
        this.kryoEncoder = kryoEncoder;
        this.kryoEncoderCrypto = kryoEncoderCrypto;

        if (endPointConnection instanceof EndPointServer) {
            this.udpRemoteMap = new ObjectMap<InetSocketAddress, ConnectionImpl>(32, ConnectionManager.LOAD_FACTOR);
        }
        else {
            this.udpRemoteMap = null;
        }
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
    private
    IntMap<MetaChannel> getAndLockChannelMap() {
        // try to lock access, also guarantees that the contents of this map are visible across threads
        this.channelMapLock.lock();
        return this.channelMap;
    }

    private
    void releaseChannelMap() {
        // try to unlock access
        this.channelMapLock.unlock();
    }

    /**
     * The amount of milli-seconds that must elapse with no read or write before {@link Listener:idle()} will be triggered
     */
    public
    int getIdleTimeout() {
        return this.endPointConnection.getIdleTimeout();
    }

    /**
     * Internal call by the pipeline to notify the client to continue registering the different session protocols. The server does not use
     * this.
     *
     * @return true if we are done registering bootstraps
     */
    public
    boolean registerNextProtocol0() {
        return this.endPointConnection.registerNextProtocol0();
    }

    /**
     * Internal call by the pipeline to notify the "Connection" object that it has "connected", meaning that modifications to the pipeline
     * are finished.
     */
    public
    void connectionConnected0(ConnectionImpl networkConnection) {
        this.endPointConnection.connectionConnected0(networkConnection);
    }

    /**
     * Internal call by the pipeline when: - creating a new network connection - when determining the baseClass for generics
     *
     * @param metaChannel can be NULL (when getting the baseClass)
     */
    public
    Connection connection0(MetaChannel metaChannel) {
        return this.endPointConnection.connection0(metaChannel);
    }

    public
    SecureRandom getSecureRandom() {
        return this.endPointConnection.secureRandom;
    }

    public
    ECPublicKeyParameters getPublicKey() {
        return this.endPointConnection.publicKey;
    }

    public
    CipherParameters getPrivateKey() {
        return this.endPointConnection.privateKey;
    }


    /**
     * @return true if the remote address public key matches the one saved
     *
     * @throws SecurityException
     */
    public
    boolean validateRemoteAddress(final InetSocketAddress tcpRemoteServer, final ECPublicKeyParameters publicKey)
                    throws SecurityException {

        InetAddress address = tcpRemoteServer.getAddress();
        byte[] hostAddress = address.getAddress();

        ECPublicKeyParameters savedPublicKey = this.endPointConnection.propertyStore.getRegisteredServerKey(hostAddress);
        Logger logger2 = this.logger;
        if (savedPublicKey == null) {
            if (logger2.isDebugEnabled()) {
                logger2.debug("Adding new remote IP address key for {}", address.getHostAddress());
            }
            this.endPointConnection.propertyStore.addRegisteredServerKey(hostAddress, publicKey);
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

                if (this.endPointConnection.disableRemoteKeyValidation) {
                    logger2.warn("Invalid or non-matching public key from remote server. Their public key has changed. To fix, remove entry for: {}", byAddress);
                    return true;
                }
                else {
                    // keys do not match, abort!
                    logger2.error("Invalid or non-matching public key from remote server. Their public key has changed. To fix, remove entry for: {}", byAddress);
                    return false;
                }
            }
        }

        return true;
    }

    @SuppressWarnings("AutoBoxing")
    public
    void removeRegisteredServerKey(final byte[] hostAddress) throws SecurityException {
        ECPublicKeyParameters savedPublicKey = this.endPointConnection.propertyStore.getRegisteredServerKey(hostAddress);
        if (savedPublicKey != null) {
            Logger logger2 = this.logger;
            if (logger2.isDebugEnabled()) {
                logger2.debug("Deleting remote IP address key {}.{}.{}.{}",
                              hostAddress[0],
                              hostAddress[1],
                              hostAddress[2],
                              hostAddress[3]);
            }
            this.endPointConnection.propertyStore.removeRegisteredServerKey(hostAddress);
        }
    }

    /**
     * ONLY SERVER SIDE CALLS THIS Called when creating a connection. Only called if we have a UDP channel
     */
    @Override
    public final
    void registerServerUDP(final MetaChannel metaChannel) {
        if (metaChannel != null && metaChannel.udpRemoteAddress != null) {
            // synchronized is used here to ensure the "single writer principle", and make sure that ONLY one thread at a time can enter this
            // section. Because of this, we can have unlimited reader threads all going at the same time, without contention (which is our
            // use-case 99% of the time)
            synchronized (singleWriterLock1) {
                // access a snapshot of the connections (single-writer-principle)
                final ObjectMap<InetSocketAddress, ConnectionImpl> udpRemoteMap = udpRemoteMapREF.get(this);

                udpRemoteMap.put(metaChannel.udpRemoteAddress, metaChannel.connection);

                // save this snapshot back to the original (single writer principle)
                udpRemoteMapREF.lazySet(this, udpRemoteMap);
            }

            this.logger.info("Connected to remote UDP connection. [{} <== {}]",
                             metaChannel.udpChannel.localAddress(),
                             metaChannel.udpRemoteAddress);
        }
    }

    /**
     * ONLY SERVER SIDE CALLS THIS Called when closing a connection.
     */
    @Override
    public final
    void unRegisterServerUDP(final InetSocketAddress udpRemoteAddress) {
        if (udpRemoteAddress != null) {
            // synchronized is used here to ensure the "single writer principle", and make sure that ONLY one thread at a time can enter this
            // section. Because of this, we can have unlimited reader threads all going at the same time, without contention (which is our
            // use-case 99% of the time)
            synchronized (singleWriterLock1) {
                // access a snapshot of the connections (single-writer-principle)
                final ObjectMap<InetSocketAddress, ConnectionImpl> udpRemoteMap = udpRemoteMapREF.get(this);

                udpRemoteMap.remove(udpRemoteAddress);

                // save this snapshot back to the original (single writer principle)
                udpRemoteMapREF.lazySet(this, udpRemoteMap);
            }

            logger.info("Closed remote UDP connection: {}", udpRemoteAddress);
        }
    }

    /**
     * ONLY SERVER SIDE CALLS THIS
     */
    @Override
    public
    ConnectionImpl getServerUDP(final InetSocketAddress udpRemoteAddress) {
        if (udpRemoteAddress != null) {
            // access a snapshot of the connections (single-writer-principle)
            final ObjectMap<InetSocketAddress, ConnectionImpl> udpRemoteMap = udpRemoteMapREF.get(this);
            return udpRemoteMap.get(udpRemoteAddress);
        }
        else {
            return null;
        }
    }

    public
    void abortRegistrationIfClient() {
        if (this.endPointConnection instanceof EndPointClient) {
            ((EndPointClient) this.endPointConnection).abortRegistration();
        }
    }

    public
    void addChannel(final int channelHashCodeOrId, final MetaChannel metaChannel) {
        try {
            IntMap<MetaChannel> channelMap = this.getAndLockChannelMap();
            channelMap.put(channelHashCodeOrId, metaChannel);
        } finally {
            this.releaseChannelMap();
        }

    }

    public
    MetaChannel removeChannel(final int channelHashCodeOrId) {
        try {
            IntMap<MetaChannel> channelMap = getAndLockChannelMap();
            return  channelMap.remove(channelHashCodeOrId);
        } finally {
            releaseChannelMap();
        }
    }

    public
    MetaChannel getChannel(final int channelHashCodeOrId) {
        try {
            IntMap<MetaChannel> channelMap = getAndLockChannelMap();
            return channelMap.get(channelHashCodeOrId);
        } finally {
            releaseChannelMap();
        }
    }

    /**
     * Closes all connections ONLY (keeps the server/client running).
     *
     * @param maxShutdownWaitTimeInMilliSeconds
     *                 The amount of time in milli-seconds to wait for this endpoint to close all {@link Channel}s and shutdown gracefully.
     */
    public
    void closeChannels(final long maxShutdownWaitTimeInMilliSeconds) {
        try {
            IntMap<MetaChannel> channelMap = getAndLockChannelMap();
            IntMap.Entries<MetaChannel> entries = channelMap.entries();
            while (entries.hasNext()) {
                MetaChannel metaChannel = entries.next().value;
                metaChannel.close(maxShutdownWaitTimeInMilliSeconds);
                Thread.yield();
            }

            channelMap.clear();

        } finally {
            releaseChannelMap();
        }
    }

    /**
     * Closes the specified connections ONLY (keeps the server/client running).
     *
     * @param maxShutdownWaitTimeInMilliSeconds
     *                 The amount of time in milli-seconds to wait for this endpoint to close all {@link Channel}s and shutdown gracefully.
     */
    public
    MetaChannel closeChannel(final Channel channel, final long maxShutdownWaitTimeInMilliSeconds) {
        try {
            IntMap<MetaChannel> channelMap = getAndLockChannelMap();
            IntMap.Entries<MetaChannel> entries = channelMap.entries();
            while (entries.hasNext()) {
                MetaChannel metaChannel = entries.next().value;

                if (metaChannel.localChannel == channel ||
                    metaChannel.tcpChannel == channel ||
                    metaChannel.udpChannel == channel) {

                    entries.remove();
                    metaChannel.close(maxShutdownWaitTimeInMilliSeconds);
                    return metaChannel;
                }
            }
        } finally {
            releaseChannelMap();
        }

        return null;
    }

    /**
     * now that we are CONNECTED, we want to remove ourselves (and channel ID's) from the map.
     * they will be ADDED in another map, in the followup handler!!
     */
    public
    boolean setupChannels(final RegistrationRemoteHandler handler, final MetaChannel metaChannel) {
        boolean registerServer = false;

        try {
            IntMap<MetaChannel> channelMap = getAndLockChannelMap();

            channelMap.remove(metaChannel.tcpChannel.hashCode());
            channelMap.remove(metaChannel.connectionID);


            ChannelPipeline pipeline = metaChannel.tcpChannel.pipeline();
            // The TCP channel is what calls this method, so we can use "this" for TCP, and the others are handled during the registration process
            pipeline.remove(handler);

            if (metaChannel.udpChannel != null) {
                // the setup is different between CLIENT / SERVER
                if (metaChannel.udpRemoteAddress == null) {
                    // CLIENT RUNS THIS
                    // don't want to muck with the SERVER udp pipeline, as it NEVER CHANGES.
                    //  More specifically, the UDP SERVER doesn't use a channelMap, it uses the udpRemoteMap
                    //  to keep track of UDP connections. This is very different than how the client works
                    // only the client will have the udp remote address
                    channelMap.remove(metaChannel.udpChannel.hashCode());
                }
                else {
                    // SERVER RUNS THIS
                    // don't ALWAYS have UDP on SERVER...
                    registerServer = true;
                }
            }
        } finally {
            releaseChannelMap();
        }

        return registerServer;
    }

    public
    Integer initializeChannel(final MetaChannel metaChannel) {
        Integer connectionID = RandomUtil.int_();
        try {
            IntMap<MetaChannel> channelMap = getAndLockChannelMap();
            while (channelMap.containsKey(connectionID)) {
                connectionID = RandomUtil.int_();
            }

            metaChannel.connectionID = connectionID;
            channelMap.put(connectionID, metaChannel);

        } finally {
            releaseChannelMap();
        }

        return connectionID;
    }

    public
    boolean associateChannels(final Channel channel, final InetAddress remoteAddress) {
        boolean success = false;

        try {
            IntMap<MetaChannel> channelMap = getAndLockChannelMap();
            IntMap.Entries<MetaChannel> entries = channelMap.entries();
            while (entries.hasNext()) {
                MetaChannel metaChannel = entries.next().value;

                // associate TCP and UDP!
                final InetSocketAddress inetSocketAddress = (InetSocketAddress) metaChannel.tcpChannel.remoteAddress();
                InetAddress tcpRemoteServer = inetSocketAddress.getAddress();
                if (checkEqual(tcpRemoteServer, remoteAddress)) {
                    channelMap.put(channel.hashCode(), metaChannel);
                    metaChannel.udpChannel = channel;
                    success = true;
                    // only allow one server per registration!
                    break;
                }
            }
        } finally {
            releaseChannelMap();
        }

        return success;
    }

    public
    MetaChannel getAssociatedChannel_UDP(final InetAddress remoteAddress) {
        try {
            MetaChannel metaChannel;
            IntMap<MetaChannel> channelMap = getAndLockChannelMap();
            IntMap.Entries<MetaChannel> entries = channelMap.entries();

            while (entries.hasNext()) {
                metaChannel = entries.next().value;

                // only look at connections that do not have UDP already setup.
                if (metaChannel.udpChannel == null) {
                    InetSocketAddress tcpRemote = (InetSocketAddress) metaChannel.tcpChannel.remoteAddress();
                    InetAddress tcpRemoteAddress = tcpRemote.getAddress();

                    if (RegistrationRemoteHandler.checkEqual(tcpRemoteAddress, remoteAddress)) {
                        return metaChannel;
                    }
                    else {
                        return null;
                    }
                }
            }
        } finally {
            releaseChannelMap();
        }

        return null;
    }
}
