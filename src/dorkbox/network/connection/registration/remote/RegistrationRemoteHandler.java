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
package dorkbox.network.connection.registration.remote;

import static dorkbox.network.connection.EndPointBase.maxShutdownWaitTimeInMilliSeconds;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.bouncycastle.crypto.engines.AESFastEngine;
import org.bouncycastle.crypto.engines.IESEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.slf4j.Logger;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.RegistrationHandler;
import dorkbox.network.pipeline.KryoDecoder;
import dorkbox.network.pipeline.KryoDecoderCrypto;
import dorkbox.network.pipeline.udp.KryoDecoderUdpCrypto;
import dorkbox.network.pipeline.udp.KryoEncoderUdpCrypto;
import dorkbox.network.util.CryptoSerializationManager;
import dorkbox.util.FastThreadLocal;
import dorkbox.util.crypto.CryptoECC;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.ReferenceCountUtil;

public abstract
class RegistrationRemoteHandler<C extends Connection> extends RegistrationHandler<C> {
    static final String KRYO_ENCODER = "kryoEncoder";
    static final String KRYO_DECODER = "kryoDecoder";

    private static final String IDLE_HANDLER_FULL = "idleHandlerFull";
    private static final String FRAME_AND_KRYO_ENCODER = "frameAndKryoEncoder";
    private static final String FRAME_AND_KRYO_DECODER = "frameAndKryoDecoder";

    private static final String FRAME_AND_KRYO_CRYPTO_ENCODER = "frameAndKryoCryptoEncoder";
    private static final String FRAME_AND_KRYO_CRYPTO_DECODER = "frameAndKryoCryptoDecoder";

    private static final String KRYO_CRYPTO_ENCODER = "kryoCryptoEncoder";
    private static final String KRYO_CRYPTO_DECODER = "kryoCryptoDecoder";

    private static final String IDLE_HANDLER = "idleHandler";

    static final
    FastThreadLocal<GCMBlockCipher> aesEngine = new FastThreadLocal<GCMBlockCipher>() {
        @Override
        public
        GCMBlockCipher initialValue() {
            return new GCMBlockCipher(new AESFastEngine());
        }
    };

    final
    FastThreadLocal<IESEngine> eccEngineLocal = new FastThreadLocal<IESEngine>() {
        @Override
        public
        IESEngine initialValue() {
            return CryptoECC.createEngine();
        }
    };

    /**
     * Check to verify if two InetAddresses are equal, by comparing the underlying byte arrays.
     */
    public static
    boolean checkEqual(InetAddress serverA, InetAddress serverB) {
        //noinspection SimplifiableIfStatement
        if (serverA == null || serverB == null) {
            return false;
        }

        return Arrays.equals(serverA.getAddress(), serverB.getAddress());
    }

    protected final CryptoSerializationManager serializationManager;

    RegistrationRemoteHandler(final String name,
                              final RegistrationWrapper<C> registrationWrapper,
                              final CryptoSerializationManager serializationManager) {
        super(name, registrationWrapper);

        this.serializationManager = serializationManager;
    }

    /**
     * STEP 1: Channel is first created
     */
    @Override
    protected
    void initChannel(final Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();

        ///////////////////////
        // DECODE (or upstream)
        ///////////////////////
        pipeline.addFirst(FRAME_AND_KRYO_DECODER,
                          new KryoDecoder(this.serializationManager)); // cannot be shared because of possible fragmentation.

        int idleTimeout = this.registrationWrapper.getIdleTimeout();
        if (idleTimeout > 0) {
            // this makes the proper event get raised in the registrationHandler to kill NEW idle connections. Once "connected" they last a lot longer.
            // we ALWAYS have this initial IDLE handler, so we don't have to worry about a slow-loris attack against the server.
            pipeline.addFirst(IDLE_HANDLER, new IdleStateHandler(4, 0, 0)); // in Seconds -- not shared, because it is per-connection
        }

        /////////////////////////
        // ENCODE (or downstream)
        /////////////////////////
        pipeline.addFirst(FRAME_AND_KRYO_ENCODER, this.registrationWrapper.getKryoEncoder()); // this is shared
    }

    /**
     * STEP 2: Channel is now active. (if debug is enabled...) Debug output, so we can tell what direction the connection is in the log
     */
    @Override
    public
    void channelActive(ChannelHandlerContext context) throws Exception {
        // add the channel so we can access it later.
        // do NOT want to add UDP channels, since they are tracked differently.


        // this whole bit is inside a if (logger.isDebugEnabled()) section.
        Channel channel = context.channel();
        Class<? extends Channel> channelClass = channel.getClass();


        StringBuilder stringBuilder = new StringBuilder(96);

        stringBuilder.append("Connected to remote ");
        if (channelClass == NioSocketChannel.class || channelClass == EpollSocketChannel.class) {
            stringBuilder.append("TCP");
        }
        else if (channelClass == NioDatagramChannel.class || channelClass == EpollDatagramChannel.class) {
            stringBuilder.append("UDP");
        }
        else {
            stringBuilder.append("UNKNOWN");
        }
        stringBuilder.append(" connection. [");
        stringBuilder.append(channel.localAddress());

        boolean isSessionless = channel instanceof NioDatagramChannel;
        if (isSessionless) {
            if (channel.remoteAddress() != null) {
                stringBuilder.append(" ==> ");
                stringBuilder.append(channel.remoteAddress());
            }
            else {
                // this means we are LISTENING.
                stringBuilder.append(" <== ");
                stringBuilder.append("?????");
            }
        }
        else {
            stringBuilder.append(getConnectionDirection());
            stringBuilder.append(channel.remoteAddress());
        }
        stringBuilder.append("]");

        this.logger.info(stringBuilder.toString());
    }

    @Override
    public
    void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception {
        Channel channel = context.channel();

        this.logger.error("Unexpected exception while trying to send/receive data on Client remote (network) channel.  ({})" +
                          System.getProperty("line.separator"), channel.remoteAddress(), cause);
        if (channel.isOpen()) {
            channel.close();
        }
    }

    /**
     * @return the direction that traffic is going to this handler (" <== " or " ==> ")
     */
    protected abstract
    String getConnectionDirection();

    // have to setup AFTER establish connection, data, as we don't want to enable AES until we're ready.
    final
    void setupConnectionCrypto(MetaChannel metaChannel) {

        if (this.logger.isDebugEnabled()) {
            String type = "TCP";
            if (metaChannel.udpChannel != null) {
                type += "/UDP";
            }

            InetSocketAddress address = (InetSocketAddress) metaChannel.tcpChannel.remoteAddress();
            this.logger.debug("Encrypting {} session with {}", type, address.getAddress());
        }

        ChannelPipeline pipeline = metaChannel.tcpChannel.pipeline();
        int idleTimeout = this.registrationWrapper.getIdleTimeout();

        // add the new handlers (FORCE encryption and longer IDLE handler)
        pipeline.replace(FRAME_AND_KRYO_DECODER,
                         FRAME_AND_KRYO_CRYPTO_DECODER,
                         new KryoDecoderCrypto(this.serializationManager)); // cannot be shared because of possible fragmentation.

        if (idleTimeout > 0) {
            pipeline.replace(IDLE_HANDLER, IDLE_HANDLER_FULL, new IdleStateHandler(0, 0, idleTimeout, TimeUnit.MILLISECONDS));
        }

        pipeline.replace(FRAME_AND_KRYO_ENCODER,
                         FRAME_AND_KRYO_CRYPTO_ENCODER,
                         this.registrationWrapper.getKryoEncoderCrypto());  // this is shared


        if (metaChannel.udpChannel != null && metaChannel.udpRemoteAddress == null) {
            // CLIENT ONLY. The server handles this very differently.
            pipeline = metaChannel.udpChannel.pipeline();
            pipeline.replace(KRYO_DECODER, KRYO_CRYPTO_DECODER, new KryoDecoderUdpCrypto(this.serializationManager));
            pipeline.replace(KRYO_ENCODER, KRYO_CRYPTO_ENCODER, new KryoEncoderUdpCrypto(this.serializationManager));
        }
    }

    /**
     * Setup our meta-channel to migrate to the correct connection handler for all regular data.
     */
    final
    void establishConnection(MetaChannel metaChannel) {
        ChannelPipeline tcpPipe = metaChannel.tcpChannel.pipeline();
        ChannelPipeline udpPipe;

        if (metaChannel.udpChannel != null && metaChannel.udpRemoteAddress == null) {
            // don't want to muck with the SERVER udp pipeline, as it NEVER CHANGES.
            // only the client will have the udp remote address
            udpPipe = metaChannel.udpChannel.pipeline();
        }
        else {
            udpPipe = null;
        }

        // add the "connected"/"normal" handler now that we have established a "new" connection.
        // This will have state, etc. for this connection.
        ConnectionImpl connection = (ConnectionImpl) this.registrationWrapper.connection0(metaChannel);


        // to have connection notified via the disruptor, we have to specify a custom ChannelHandlerInvoker.
        tcpPipe.addLast(CONNECTION_HANDLER, connection);

        if (udpPipe != null) {
            // remember, server is different than client!
            udpPipe.addLast(CONNECTION_HANDLER, connection);
        }
    }

    final
    boolean verifyAesInfo(final Object message,
                          final Channel channel,
                          final RegistrationWrapper<C> registrationWrapper,
                          final MetaChannel metaChannel,
                          final Logger logger) {

        if (metaChannel.aesKey.length != 32) {
            logger.error("Fatal error trying to use AES key (wrong key length).");
            shutdown(registrationWrapper, channel);

            ReferenceCountUtil.release(message);
            return true;
        }
        // IV length must == 12 because we are using GCM!
        else if (metaChannel.aesIV.length != 12) {
            logger.error("Fatal error trying to use AES IV (wrong IV length).");
            shutdown(registrationWrapper, channel);

            ReferenceCountUtil.release(message);
            return true;
        }

        return false;
    }

    // have to setup AFTER establish connection, data, as we don't want to enable AES until we're ready.
    @SuppressWarnings("AutoUnboxing")
    final
    void setupConnection(MetaChannel metaChannel) {
        // now that we are CONNECTED, we want to remove ourselves (and channel ID's) from the map.
        // they will be ADDED in another map, in the followup handler!!
        boolean registerServer = this.registrationWrapper.setupChannels(this, metaChannel);

        if (registerServer) {
            // Only called if we have a UDP channel
            setupServerUdpConnection(metaChannel);
        }

        if (this.logger.isInfoEnabled()) {
            String type = "TCP";
            if (metaChannel.udpChannel != null) {
                type += "/UDP";
            }

            InetSocketAddress address = (InetSocketAddress) metaChannel.tcpChannel.remoteAddress();
            this.logger.info("Created a {} connection with {}", type, address.getAddress());
        }
    }

    /**
     * Registers the metachannel for the UDP server. Default is to do nothing.
     * <p/>
     * The server will override this. Only called if we have a UDP channel when we finalize the setup of the TCP connection
     */
    @SuppressWarnings("unused")
    protected
    void setupServerUdpConnection(MetaChannel metaChannel) {
    }

    /**
     * Internal call by the pipeline to notify the "Connection" object that it has "connected", meaning that modifications to the pipeline
     * are finished.
     */
    final
    void notifyConnection(MetaChannel metaChannel) {
        this.registrationWrapper.connectionConnected0(metaChannel.connection);
    }

    @Override
    public final
    void channelInactive(ChannelHandlerContext context) throws Exception {
        Channel channel = context.channel();

        this.logger.info("Closed connection: {}", channel.remoteAddress());

        // also, once we notify, we unregister this.
        // SEARCH for our channel!

        // on the server, we only get this for TCP events!
        this.registrationWrapper.closeChannel(channel, maxShutdownWaitTimeInMilliSeconds);

        super.channelInactive(context);
    }
}
