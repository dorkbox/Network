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

import java.net.InetSocketAddress;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECParameterSpec;

import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import dorkbox.network.connection.registration.RegistrationHandler;
import dorkbox.network.connection.registration.UpgradeType;
import dorkbox.network.pipeline.tcp.KryoDecoderTcp;
import dorkbox.network.pipeline.tcp.KryoDecoderTcpCompression;
import dorkbox.network.pipeline.tcp.KryoDecoderTcpCrypto;
import dorkbox.network.pipeline.tcp.KryoDecoderTcpNone;
import dorkbox.util.crypto.CryptoECC;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.GenericFutureListener;

public abstract
class RegistrationRemoteHandler<T extends RegistrationWrapper> extends RegistrationHandler<T> {
    static final AttributeKey<LinkedList> MESSAGES = AttributeKey.valueOf(RegistrationRemoteHandler.class, "messages");

    static final String DELETE_IP = "eleteIP"; // purposefully missing the "D", since that is a system parameter, which starts with "-D"
    static final ECParameterSpec eccSpec = ECNamedCurveTable.getParameterSpec(CryptoECC.curve25519);

    private static final String UDP_DECODE = "ud1";
    private static final String UDP_DECODE_NONE = "ud2";
    private static final String UDP_DECODE_COMPRESS = "ud3";
    private static final String UDP_DECODE_CRYPTO = "ud4";

    private static final String TCP_DECODE = "td1";
    private static final String TCP_DECODE_NONE = "td2";
    private static final String TCP_DECODE_COMPRESS = "td3";
    private static final String TCP_DECODE_CRYPTO = "td4";


    private static final String IDLE_HANDLER = "idle";
    private static final String IDLE_HANDLER_FULL = "idleFull";

    private static final String UDP_ENCODE = "ue";
    private static final String UDP_ENCODE_NONE = "ue2";
    private static final String UDP_ENCODE_COMPRESS = "ue3";
    private static final String UDP_ENCODE_CRYPTO = "ue4";

    private static final String TCP_ENCODE = "te1";
    private static final String TCP_ENCODE_NONE = "te2";
    private static final String TCP_ENCODE_COMPRESS = "te3";
    private static final String TCP_ENCODE_CRYPTO = "te4";






    RegistrationRemoteHandler(final String name, final T registrationWrapper, final EventLoopGroup workerEventLoop) {
        super(name, registrationWrapper, workerEventLoop);
    }

    /**
     * STEP 1: Channel is first created
     */
    @Override
    protected
    void initChannel(final Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();

        Class<? extends Channel> channelClass = channel.getClass();
        // because of the way TCP works, we have to have special readers/writers. For UDP, all data must be in a single packet.

        boolean isTcpChannel = ConnectionImpl.isTcpChannel(channelClass);
        boolean isUdpChannel = !isTcpChannel && ConnectionImpl.isUdpChannel(channelClass);

        if (isTcpChannel) {
            ///////////////////////
            // DECODE (or upstream)
            ///////////////////////
            pipeline.addFirst(TCP_DECODE, new KryoDecoderTcp(registrationWrapper.getSerialization())); // cannot be shared because of possible fragmentation.
        }
        else if (isUdpChannel) {
            // can be shared because there cannot be fragmentation for our UDP packets. If there is, we throw an error and continue...
            pipeline.addFirst(UDP_DECODE, this.registrationWrapper.kryoUdpDecoder);
        }

        // this makes the proper event get raised in the registrationHandler to kill NEW idle connections. Once "connected" they last a lot longer.
        // we ALWAYS have this initial IDLE handler, so we don't have to worry about a SLOW-LORIS ATTACK against the server.
        // in Seconds -- not shared, because it is per-connection
        pipeline.addFirst(IDLE_HANDLER, new IdleStateHandler(2, 0, 0));

        if (isTcpChannel) {
            /////////////////////////
            // ENCODE (or downstream)
            /////////////////////////
            pipeline.addFirst(TCP_ENCODE, this.registrationWrapper.kryoTcpEncoder); // this is shared
        }
        else if (isUdpChannel) {
            pipeline.addFirst(UDP_ENCODE, this.registrationWrapper.kryoUdpEncoder);
        }
    }

    /**
     * STEP 2: Channel is now active. (if debug is enabled...) Debug output, so we can tell what direction the connection is in the log
     */
    @Override
    public
    void channelActive(ChannelHandlerContext context) throws Exception {
        // add the channel so we can access it later.
        // do NOT want to add UDP channels, since they are tracked differently.

        if (this.logger.isDebugEnabled()) {
            Channel channel = context.channel();
            Class<? extends Channel> channelClass = channel.getClass();
            boolean isUdp = ConnectionImpl.isUdpChannel(channelClass);

            StringBuilder stringBuilder = new StringBuilder(96);

            stringBuilder.append("Connected to remote ");
            if (ConnectionImpl.isTcpChannel(channelClass)) {
                stringBuilder.append("TCP");
            }
            else if (isUdp) {
                stringBuilder.append("UDP");
            }
            else if (ConnectionImpl.isLocalChannel(channelClass)) {
                stringBuilder.append("LOCAL");
            }
            else {
                stringBuilder.append("UNKNOWN");
            }

            stringBuilder.append(" connection  [");
            EndPoint.getHostDetails(stringBuilder, channel.localAddress());

            stringBuilder.append(getConnectionDirection());
            EndPoint.getHostDetails(stringBuilder, channel.remoteAddress());
            stringBuilder.append("]");

            this.logger.debug(stringBuilder.toString());
        }
    }

    /**
     * Invoked when a {@link Channel} has been idle for a while.
     */
    @Override
    public
    void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
        if (event instanceof IdleStateEvent) {
            if (((IdleStateEvent) event).state() == IdleState.ALL_IDLE) {
                // this IS BAD, because we specify an idle handler to prevent slow-loris type attacks on the webserver
                Channel channel = context.channel();
                channel.close();
                return;
            }
        }

        super.userEventTriggered(context, event);
    }

    @Override
    public
    void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception {
        Channel channel = context.channel();

        this.logger.error("Unexpected exception while trying to send/receive data on remote network channel.  ({})" +
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

    /**
     * upgrades a channel ONE channel at a time
     */
    final
    void upgradeDecoders(final int upgradeType, final Channel channel, final MetaChannel metaChannel) {
        ChannelPipeline pipeline = channel.pipeline();

        try {
            if (metaChannel.tcpChannel == channel) {
                // cannot be shared because of possible fragmentation.
                switch (upgradeType) {
                    case (UpgradeType.NONE) :
                        pipeline.replace(TCP_DECODE, TCP_DECODE_NONE, new KryoDecoderTcpNone(registrationWrapper.getSerialization()));
                        break;

                    case (UpgradeType.COMPRESS) :
                        pipeline.replace(TCP_DECODE, TCP_DECODE_COMPRESS, new KryoDecoderTcpCompression(registrationWrapper.getSerialization()));
                        break;

                    case (UpgradeType.ENCRYPT) :
                        pipeline.replace(TCP_DECODE, TCP_DECODE_CRYPTO, new KryoDecoderTcpCrypto(registrationWrapper.getSerialization()));
                        break;
                    default:
                        throw new IllegalArgumentException("Unable to upgrade TCP connection pipeline for type: " + upgradeType);
                }
            }
            else if (metaChannel.udpChannel == channel) {
                // shared decoder
                switch (upgradeType) {
                    case (UpgradeType.NONE) :
                        pipeline.replace(UDP_DECODE, UDP_DECODE_NONE, this.registrationWrapper.kryoUdpDecoderNone);
                        break;

                    case (UpgradeType.COMPRESS) :
                        pipeline.replace(UDP_DECODE, UDP_DECODE_COMPRESS, this.registrationWrapper.kryoUdpDecoderCompression);
                        break;

                    case (UpgradeType.ENCRYPT) :
                        pipeline.replace(UDP_DECODE, UDP_DECODE_CRYPTO, this.registrationWrapper.kryoUdpDecoderCrypto);
                        break;
                    default:
                        throw new IllegalArgumentException("Unable to upgrade UDP connection pipeline for type: " + upgradeType);
                }
            }
        } catch (Exception e) {
            logger.error("Error during connection pipeline upgrade", e);
        }
    }

    /**
     * upgrades a channel ONE channel at a time
     */
    final
    void upgradeEncoders(final int upgradeType, final Channel channel, final MetaChannel metaChannel) {
        ChannelPipeline pipeline = channel.pipeline();

        if (metaChannel.tcpChannel == channel) {
            // shared encoder
            switch (upgradeType) {
                case (UpgradeType.NONE) :
                    pipeline.replace(TCP_ENCODE, TCP_ENCODE_NONE, this.registrationWrapper.kryoTcpEncoderNone);
                    break;

                case (UpgradeType.COMPRESS) :
                    pipeline.replace(TCP_ENCODE, TCP_ENCODE_COMPRESS, this.registrationWrapper.kryoTcpEncoderCompression);
                    break;

                case (UpgradeType.ENCRYPT) :
                    pipeline.replace(TCP_ENCODE, TCP_ENCODE_CRYPTO, this.registrationWrapper.kryoTcpEncoderCrypto);
                    break;
                default:
                    throw new IllegalArgumentException("Unable to upgrade TCP connection pipeline for type: " + upgradeType);
            }
        }
        else if (metaChannel.udpChannel == channel) {
            // shared encoder
            switch (upgradeType) {
                case (UpgradeType.NONE) :
                    pipeline.replace(UDP_ENCODE, UDP_ENCODE_NONE, this.registrationWrapper.kryoUdpEncoderNone);
                    break;

                case (UpgradeType.COMPRESS) :
                    pipeline.replace(UDP_ENCODE, UDP_ENCODE_COMPRESS, this.registrationWrapper.kryoUdpEncoderCompression);
                    break;

                case (UpgradeType.ENCRYPT) :
                    pipeline.replace(UDP_ENCODE, UDP_ENCODE_CRYPTO, this.registrationWrapper.kryoUdpEncoderCrypto);
                    break;
                default:
                    throw new IllegalArgumentException("Unable to upgrade UDP connection pipeline for type: " + upgradeType);
            }
        }
    }

    final
    void logChannelUpgrade(final int upgradeType, final Channel channel, final MetaChannel metaChannel) {
        if (this.logger.isInfoEnabled()) {
            final String channelType;

            if (metaChannel.tcpChannel == channel) {
                // cannot be shared because of possible fragmentation.
                switch (upgradeType) {
                    case (UpgradeType.NONE) :
                        channelType = "TCP simple";
                        break;

                    case (UpgradeType.COMPRESS) :
                        channelType = "TCP compression";
                        break;

                    case (UpgradeType.ENCRYPT) :
                        channelType = "TCP encrypted";
                        break;
                    default:
                        this.logger.error("Unable to upgrade TCP connection pipeline for type: " + upgradeType);
                        return;
                }
            }
            else if (metaChannel.udpChannel == channel) {
                // shared decoder
                switch (upgradeType) {
                    case (UpgradeType.NONE) :
                        channelType = "UDP simple";
                        break;

                    case (UpgradeType.COMPRESS) :
                        channelType = "UDP compression";
                        break;

                    case (UpgradeType.ENCRYPT) :
                        channelType = "UDP encrypted";
                        break;
                    default:
                        this.logger.error("Unable to upgrade UDP connection pipeline for type: " + upgradeType);
                        return;
                }
            }
           else {
                this.logger.error("Unable to upgrade unknown connection pipeline for type: " + upgradeType);
                return;
            }


            StringBuilder stringBuilder = new StringBuilder(96);

            stringBuilder.append(channelType);
            stringBuilder.append(" connection  [");

            EndPoint.getHostDetails(stringBuilder, channel.localAddress());

            stringBuilder.append(getConnectionDirection());
            EndPoint.getHostDetails(stringBuilder, channel.remoteAddress());

            stringBuilder.append("]");

            this.logger.info(stringBuilder.toString());
        }
    }

    final
    void cleanupPipeline(final Channel channel, final MetaChannel metaChannel, final Runnable preConnectRunnable, final Runnable postConnectRunnable) {
        final int idleTimeout = this.registrationWrapper.getIdleTimeout();

        try {
            // channel should NEVER == null! (we will always have TCP or UDP!)
            // we also ONLY want to add this to a single cleanup, NOT BOTH, because this must only run once!!
            final ChannelPromise channelPromise = channel.newPromise();
            channelPromise.addListener(new FutureListener<Void>() {
                @Override
                public
                void operationComplete(final Future<Void> future) throws Exception {
                    // this runs on the channel's event loop
                    logger.trace("Connection connected");

                    if (preConnectRunnable != null) {
                        preConnectRunnable.run();
                    }

                    // safe cast, because it's always this way...
                    registrationWrapper.connectionConnected0(metaChannel.connection);

                    if (postConnectRunnable != null) {
                        postConnectRunnable.run();
                    }
                }
            });


            if (metaChannel.tcpChannel != null) {
                cleanupPipeline0(idleTimeout, metaChannel.tcpChannel, channelPromise);
            }

            if (metaChannel.udpChannel != null) {
                cleanupPipeline0(idleTimeout, metaChannel.udpChannel, channelPromise);
            }
        } catch (Exception e) {
            logger.error("Error during pipeline replace", e);
        }
    }

    private
    void cleanupPipeline0(final int idleTimeout, final Channel channel, final ChannelPromise channelPromise) {
        final ChannelPipeline pipeline = channel.pipeline();

        if (idleTimeout > 0) {
            pipeline.replace(IDLE_HANDLER, IDLE_HANDLER_FULL, new IdleStateHandler(0, 0, idleTimeout, TimeUnit.MILLISECONDS));
        }
        else {
            pipeline.remove(IDLE_HANDLER);
        }

        // we also DEREGISTER from the HANDSHAKE event-loop and run on the worker event-loop!
        ChannelFuture future = channel.deregister();
        future.addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public
            void operationComplete(final Future<? super Void> f) throws Exception {
                if (f.isSuccess()) {
                    workerEventLoop.register(channel);

                    // TCP and UDP register on DIFFERENT event loops. This channel promise ONLY runs on the same worker as the
                    // channel that called `cleanupPipeline`
                    if (channelPromise.channel() == channel) {
                        workerEventLoop.register(channelPromise);
                    }
                }
            }
        });
    }

    // whoa! Didn't send valid public key info!
    boolean invalidPublicKey(final Registration message, final String type) {
        if (message.publicKey == null) {
            logger.error("Null ECC public key during " + type + " handshake. This shouldn't happen!");
            return true;
        }

        return false;
    }

    // want to validate the public key used! This is similar to how SSH works, in that once we use a public key, we want to validate
    // against that ip-address::key pair, so we can better protect against MITM/spoof attacks.
    boolean invalidRemoteAddress(final MetaChannel metaChannel,
                                 final Registration message,
                                 final String type,
                                 final InetSocketAddress remoteAddress) {


        boolean valid = registrationWrapper.validateRemoteAddress(metaChannel, remoteAddress, message.publicKey);
        if (!valid) {
            //whoa! abort since something messed up! (log happens inside of validate method)
            String hostAddress = remoteAddress.getAddress()
                                              .getHostAddress();
            logger.error("Invalid ECC public key for server IP {} during {} handshake. WARNING. The server has changed!", hostAddress, type);
            logger.error("Fix by adding the argument   -D{} {}   when starting the client.", DELETE_IP, hostAddress);

            return true;
        }

        return false;
    }

    /**
     * have to have a way for us to store messages in case the remote end calls "onConnect()" and sends messages before we are ready.
     */
    void prepChannelForOutOfOrderMessages(final Channel channel) {
        // this could POSSIBLY be screwed up when getting created, so we make sure to only create the list ONE time
        channel.attr(MESSAGES)
               .setIfAbsent(new LinkedList<Object>());
    }


    /**
     * have to have a way for us to store messages in case the remote end calls "onConnect()" and sends messages before we are ready.
     */
    void saveOutOfOrderMessage(final Channel channel, final Object message) {
        // this will ALWAYS have already been created, or IF NULL -- then something really screwed up!
        LinkedList list = channel.attr(MESSAGES)
                                 .get();

        if (list == null) {
            logger.error("Completely screwed up message order from server!");
            shutdown(channel, 0);
            return;
        }

        //noinspection unchecked
        list.add(message);
    }

    /**
     * Gets all of the messages for this channel that were "out of order" (onMessage called before onConnect finished).
     */
    List<Object> getOutOfOrderMessagesAndReset(final Channel channel) {
        LinkedList messages = channel.attr(MESSAGES)
                                     .getAndSet(null);

        return messages;
    }
}
