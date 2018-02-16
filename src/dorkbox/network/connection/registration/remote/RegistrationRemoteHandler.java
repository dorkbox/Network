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
import java.util.concurrent.TimeUnit;

import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECParameterSpec;

import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import dorkbox.network.connection.registration.RegistrationHandler;
import dorkbox.network.pipeline.tcp.KryoDecoder;
import dorkbox.network.pipeline.tcp.KryoDecoderCrypto;
import dorkbox.network.serialization.CryptoSerializationManager;
import dorkbox.util.crypto.CryptoECC;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

public abstract
class RegistrationRemoteHandler extends RegistrationHandler {
    static final String DELETE_IP = "eleteIP"; // purposefully missing the "D", since that is a system parameter, which starts with "-D"
    static final ECParameterSpec eccSpec = ECNamedCurveTable.getParameterSpec(CryptoECC.curve25519);

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

    protected final CryptoSerializationManager serializationManager;

    RegistrationRemoteHandler(final String name, final RegistrationWrapper registrationWrapper) {
        super(name, registrationWrapper);

        this.serializationManager = registrationWrapper.getSerializtion();
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

        boolean isTcpChannel = ConnectionImpl.isTcp(channelClass);
        boolean isUdpChannel = !isTcpChannel && ConnectionImpl.isUdp(channelClass);

        if (isTcpChannel) {
            ///////////////////////
            // DECODE (or upstream)
            ///////////////////////
            pipeline.addFirst(FRAME_AND_KRYO_DECODER,
                              new KryoDecoder(this.serializationManager)); // cannot be shared because of possible fragmentation.
        }
        else if (isUdpChannel) {
            // can be shared because there cannot be fragmentation for our UDP packets. If there is, we throw an error and continue...
            pipeline.addFirst(KRYO_DECODER, this.registrationWrapper.kryoUdpDecoder);
        }


        int idleTimeout = this.registrationWrapper.getIdleTimeout();
        if (idleTimeout > 0) {
            // this makes the proper event get raised in the registrationHandler to kill NEW idle connections. Once "connected" they last a lot longer.
            // we ALWAYS have this initial IDLE handler, so we don't have to worry about a SLOW-LORIS ATTACK against the server.
            // in Seconds -- not shared, because it is per-connection
            pipeline.addFirst(IDLE_HANDLER, new IdleStateHandler(2, 0, 0));
        }

        if (isTcpChannel) {
            /////////////////////////
            // ENCODE (or downstream)
            /////////////////////////
            pipeline.addFirst(FRAME_AND_KRYO_ENCODER, this.registrationWrapper.kryoTcpEncoder); // this is shared
        }
        else if (isUdpChannel) {
            pipeline.addFirst(KRYO_ENCODER, this.registrationWrapper.kryoUdpEncoder);
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

        if (this.logger.isInfoEnabled()) {
            Channel channel = context.channel();
            Class<? extends Channel> channelClass = channel.getClass();
            boolean isUdp = ConnectionImpl.isUdp(channelClass);

            StringBuilder stringBuilder = new StringBuilder(96);

            stringBuilder.append("Connected to remote ");
            if (ConnectionImpl.isTcp(channelClass)) {
                stringBuilder.append("TCP");
            }
            else if (isUdp) {
                stringBuilder.append("UDP");
            }
            else if (ConnectionImpl.isLocal(channelClass)) {
                stringBuilder.append("LOCAL");
            }
            else {
                stringBuilder.append("UNKNOWN");
            }

            stringBuilder.append(" connection. [");
            stringBuilder.append(channel.localAddress());

            // this means we are "Sessionless"
            if (isUdp) {
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
     * @return true if validation was successful
     */
    final
    boolean invalidAES(final MetaChannel metaChannel) {
        if (metaChannel.aesKey.length != 32) {
            logger.error("Fatal error trying to use AES key (wrong key length).");
            return true;
        }
        // IV length must == 12 because we are using GCM!
        else if (metaChannel.aesIV.length != 12) {
            logger.error("Fatal error trying to use AES IV (wrong IV length).");
            return true;
        }

        return false;
    }

    // have to setup AFTER establish connection, data, as we don't want to enable AES until we're ready.
    final
    void setupConnectionCrypto(final MetaChannel metaChannel, final InetSocketAddress remoteAddress) {

        if (this.logger.isDebugEnabled()) {
            String type = "";

            if (metaChannel.tcpChannel != null) {
                type = "TCP";

                if (metaChannel.udpChannel != null) {
                    type += "/";
                }
            }

            if (metaChannel.udpChannel != null) {
                type += "UDP";
            }

            this.logger.debug("Encrypting {} session with {}", type, remoteAddress);
        }

        if (metaChannel.tcpChannel != null) {
            ChannelPipeline pipeline = metaChannel.tcpChannel.pipeline();

            // add the new handlers (FORCE encryption and longer IDLE handler)
            pipeline.replace(FRAME_AND_KRYO_DECODER,
                             FRAME_AND_KRYO_CRYPTO_DECODER,
                             new KryoDecoderCrypto(this.serializationManager)); // cannot be shared because of possible fragmentation.

            int idleTimeout = this.registrationWrapper.getIdleTimeout();
            if (idleTimeout > 0) {
                pipeline.replace(IDLE_HANDLER, IDLE_HANDLER_FULL, new IdleStateHandler(0, 0, idleTimeout, TimeUnit.MILLISECONDS));
            }

            pipeline.replace(FRAME_AND_KRYO_ENCODER,
                             FRAME_AND_KRYO_CRYPTO_ENCODER,
                             this.registrationWrapper.kryoTcpEncoderCrypto);  // this is shared
        }

        if (metaChannel.udpChannel != null) {
            ChannelPipeline pipeline = metaChannel.udpChannel.pipeline();

            int idleTimeout = this.registrationWrapper.getIdleTimeout();
            if (idleTimeout > 0) {
                pipeline.replace(IDLE_HANDLER, IDLE_HANDLER_FULL, new IdleStateHandler(0, 0, idleTimeout, TimeUnit.MILLISECONDS));
            }

            pipeline.replace(KRYO_DECODER, KRYO_CRYPTO_DECODER, this.registrationWrapper.kryoUdpDecoderCrypto);
            pipeline.replace(KRYO_ENCODER, KRYO_CRYPTO_ENCODER, this.registrationWrapper.kryoUdpEncoderCrypto);
        }
    }

    // have to setup AFTER establish connection, data, as we don't want to enable AES until we're ready.
    @SuppressWarnings("AutoUnboxing")
    final
    void setupConnection(final MetaChannel metaChannel, final Channel channel) {
        // Now setup our meta-channel to migrate to the correct connection handler for all regular data.

        // add the "connected"/"normal" handler now that we have established a "new" connection.
        // This will have state, etc. for this connection.
        InetSocketAddress remoteAddress = (InetSocketAddress) channel.remoteAddress();
        ConnectionImpl connection = (ConnectionImpl) this.registrationWrapper.connection0(metaChannel, remoteAddress);

        if (metaChannel.tcpChannel != null) {
            ChannelPipeline pipeline = metaChannel.tcpChannel.pipeline();
            if (registrationWrapper.isClient()) {
                pipeline.remove(RegistrationRemoteHandlerClientTCP.class);
            }
            else {
                pipeline.remove(RegistrationRemoteHandlerServerTCP.class);
            }
            pipeline.addLast(CONNECTION_HANDLER, connection);
        }

        if (metaChannel.udpChannel != null) {
            ChannelPipeline pipeline = metaChannel.udpChannel.pipeline();
            if (registrationWrapper.isClient()) {
                pipeline.remove(RegistrationRemoteHandlerClientUDP.class);
            }
            else {
                pipeline.remove(RegistrationRemoteHandlerServerUDP.class);
            }
            pipeline.addLast(CONNECTION_HANDLER, connection);
        }

        if (this.logger.isInfoEnabled()) {
            String type = "";

            if (metaChannel.tcpChannel != null) {
                type = "TCP";

                if (metaChannel.udpChannel != null) {
                    type += "/";
                }
            }

            if (metaChannel.udpChannel != null) {
                type += "UDP";
            }

            this.logger.info("Created a {} connection with {}", type, remoteAddress.getAddress());
        }
    }

    /**
     * Internal call by the pipeline to notify the "Connection" object that it has "connected", meaning that modifications to the pipeline
     * are finished.
     */
    final
    void notifyConnection(MetaChannel metaChannel) {
        this.registrationWrapper.connectionConnected0(metaChannel.connection);
        this.registrationWrapper.removeSession(metaChannel);
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
}
