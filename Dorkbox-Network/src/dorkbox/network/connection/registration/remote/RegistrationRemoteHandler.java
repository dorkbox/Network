package dorkbox.network.connection.registration.remote;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.udt.nio.NioUdtByteConnectorChannel;
import io.netty.handler.timeout.IdleStateHandler;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.bouncycastle.crypto.engines.AESFastEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;

import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.RegistrationHandler;
import dorkbox.network.pipeline.KryoDecoder;
import dorkbox.network.pipeline.KryoDecoderCrypto;
import dorkbox.network.pipeline.udp.KryoDecoderUdpCrypto;
import dorkbox.network.pipeline.udp.KryoEncoderUdpCrypto;
import dorkbox.network.util.SerializationManager;
import dorkbox.util.primativeCollections.IntMap;
import dorkbox.util.primativeCollections.IntMap.Entries;

public abstract class RegistrationRemoteHandler extends RegistrationHandler {
    private static final String IDLE_HANDLER_FULL = "idleHandlerFull";

    protected static final String KRYO_ENCODER = "kryoEncoder";
    protected static final String KRYO_DECODER = "kryoDecoder";

    private static final String FRAME_AND_KRYO_ENCODER = "frameAndKryoEncoder";
    private static final String FRAME_AND_KRYO_DECODER = "frameAndKryoDecoder";

    private static final String FRAME_AND_KRYO_CRYPTO_ENCODER = "frameAndKryoCryptoEncoder";
    private static final String FRAME_AND_KRYO_CRYPTO_DECODER = "frameAndKryoCryptoDecoder";

    private static final String KRYO_CRYPTO_ENCODER = "kryoCryptoEncoder";
    private static final String KRYO_CRYPTO_DECODER = "kryoCryptoDecoder";

    private static final String IDLE_HANDLER = "idleHandler";

    protected final SerializationManager serializationManager;

    private static ThreadLocal<GCMBlockCipher> aesEngineLocal = new ThreadLocal<GCMBlockCipher>();

    public RegistrationRemoteHandler(String name, RegistrationWrapper registrationWrapper, SerializationManager serializationManager) {
        super(name, registrationWrapper);

        this.serializationManager = serializationManager;
    }

    protected static final GCMBlockCipher getAesEngine() {
        GCMBlockCipher aesEngine = aesEngineLocal.get();
        if (aesEngine == null) {
            aesEngine = new GCMBlockCipher(new AESFastEngine());
            aesEngineLocal.set(aesEngine);
        }
        return aesEngine;
    }

    /**
     * STEP 1: Channel is first created
     */
    @Override
    protected void initChannel(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();

        ///////////////////////
        // DECODE (or upstream)
        ///////////////////////
        pipeline.addFirst(FRAME_AND_KRYO_DECODER, new KryoDecoder(this.serializationManager)); // cannot be shared because of possible fragmentation.

        // this makes the proper event get raised in the registrationHandler to kill NEW idle connections. Once "connected" they last a lot longer.
        // we ALWAYS have this initial IDLE handler, so we don't have to worry about a slow-loris attack against the server.
        pipeline.addFirst(IDLE_HANDLER, new IdleStateHandler(4, 0, 0)); // timer must be shared.


        /////////////////////////
        // ENCODE (or downstream)
        /////////////////////////
        pipeline.addFirst(FRAME_AND_KRYO_ENCODER, this.registrationWrapper.getKryoTcpEncoder()); // this is shared
    }

    /**
     * STEP 2: Channel is now active. (if debug is enabled...)
     * Debug output, so we can tell what direction the connection is in the log
     */
    @Override
    public void channelActive(ChannelHandlerContext context) throws Exception {
        // add the channel so we can access it later.
        // do NOT want to add UDP channels, since they are tracked differently.


        // this whole bit is inside a if (logger.isDebugEnabled()) section.
        Channel channel = context.channel();
        Class<? extends Channel> channelClass = channel.getClass();


        StringBuilder stringBuilder = new StringBuilder(76);

        stringBuilder.append("Connected to remote ");
        if (channelClass == NioSocketChannel.class) {
            stringBuilder.append("TCP");
        } else if (channelClass == EpollSocketChannel.class) {
            stringBuilder.append("TCP");
        } else if (channelClass == NioDatagramChannel.class) {
            stringBuilder.append("UDP");
        } else if (channelClass == EpollDatagramChannel.class) {
            stringBuilder.append("UDP");
        } else if (channelClass == NioUdtByteConnectorChannel.class) {
            stringBuilder.append("UDT");
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
            } else {
                // this means we are LISTENING.
                stringBuilder.append(" <== ");
                stringBuilder.append("?????");
            }
        } else {
            stringBuilder.append(getConnectionDirection());
            stringBuilder.append(channel.remoteAddress());
        }
        stringBuilder.append("]");

        this.logger.debug(stringBuilder.toString());
    }

    /**
     * @return the direction that traffic is going to this handler (" <== " or " ==> ")
     */
    protected abstract String getConnectionDirection();


    /**
     * Check to verify if two InetAdresses are equal, by comparing the underlying byte arrays.
     */
    public static boolean checkEqual(InetAddress serverA, InetAddress serverB) {
        if (serverA == null || serverB == null) {
            return false;
        }

        return Arrays.equals(serverA.getAddress(), serverB.getAddress());
    }


    // have to setup AFTER establish connection, data, as we don't want to enable AES until we're ready.
    protected final void setupConnectionCrypto(MetaChannel metaChannel) {

        if (this.logger.isDebugEnabled()) {
            String type = "TCP";
            if (metaChannel.udpChannel != null) {
                type += "/UDP";
            }
            if (metaChannel.udtChannel != null) {
                type += "/UDT";
            }


            InetSocketAddress address = (InetSocketAddress)metaChannel.tcpChannel.remoteAddress();
            this.logger.debug("Encrypting {} session with {}", type, address.getAddress());
        }

        ChannelPipeline pipeline = metaChannel.tcpChannel.pipeline();
        int idleTimeout = this.registrationWrapper.getIdleTimeout();

        // add the new handlers (FORCE encryption and longer IDLE handler)
        pipeline.replace(FRAME_AND_KRYO_DECODER, FRAME_AND_KRYO_CRYPTO_DECODER, new KryoDecoderCrypto(this.serializationManager)); // cannot be shared because of possible fragmentation.
        if (idleTimeout > 0) {
            pipeline.replace(IDLE_HANDLER, IDLE_HANDLER_FULL, new IdleStateHandler(0, 0, this.registrationWrapper.getIdleTimeout(), TimeUnit.MILLISECONDS));
        } else {
            pipeline.remove(IDLE_HANDLER);
        }
        pipeline.replace(FRAME_AND_KRYO_ENCODER, FRAME_AND_KRYO_CRYPTO_ENCODER, this.registrationWrapper.getKryoTcpCryptoEncoder());  // this is shared


        if (metaChannel.udpChannel != null && metaChannel.udpRemoteAddress == null) {
            // CLIENT ONLY. The server handles this very differently.
            pipeline = metaChannel.udpChannel.pipeline();
            pipeline.replace(KRYO_DECODER, KRYO_CRYPTO_DECODER, new KryoDecoderUdpCrypto(this.serializationManager));
            pipeline.replace(KRYO_ENCODER, KRYO_CRYPTO_ENCODER, new KryoEncoderUdpCrypto(this.serializationManager));
        }

        if (metaChannel.udtChannel != null) {
            pipeline = metaChannel.udtChannel.pipeline();
            pipeline.replace(FRAME_AND_KRYO_DECODER, FRAME_AND_KRYO_CRYPTO_DECODER, new KryoDecoderCrypto(this.serializationManager)); // cannot be shared because of possible fragmentation.
            if (idleTimeout > 0) {
                pipeline.replace(IDLE_HANDLER, IDLE_HANDLER_FULL, new IdleStateHandler(0, 0, idleTimeout, TimeUnit.MILLISECONDS));
            } else {
                pipeline.remove(IDLE_HANDLER);
            }
            pipeline.replace(FRAME_AND_KRYO_ENCODER, FRAME_AND_KRYO_CRYPTO_ENCODER, this.registrationWrapper.getKryoTcpCryptoEncoder());
        }
    }

    /**
     * Setup our meta-channel to migrate to the correct connection handler for all regular data.
     */
    protected final void establishConnection(MetaChannel metaChannel) {
        ChannelPipeline tcpPipe = metaChannel.tcpChannel.pipeline();
        ChannelPipeline udpPipe;
        ChannelPipeline udtPipe;

        if (metaChannel.udpChannel != null && metaChannel.udpRemoteAddress == null) {
            // don't want to muck with the SERVER udp pipeline, as it NEVER CHANGES.
            // only the client will have the udp remote address
            udpPipe = metaChannel.udpChannel.pipeline();
        } else {
            udpPipe = null;
        }

        if (metaChannel.udtChannel != null) {
            udtPipe = metaChannel.udtChannel.pipeline();
        } else {
            udtPipe = null;
        }


        // add the "connected"/"normal" handler now that we have established a "new" connection.
        // This will have state, etc. for this connection.
        ConnectionImpl connection = (ConnectionImpl) this.registrationWrapper.connection0(metaChannel);


        // to have connection notified via the disruptor, we have to specify a custom ChannelHandlerInvoker.
//        ChannelHandlerInvoker channelHandlerInvoker = new ChannelHandlerInvoker();

        tcpPipe.addLast(CONNECTION_HANDLER, connection);

        if (udpPipe != null) {
            // remember, server is different than client!
            udpPipe.addLast(CONNECTION_HANDLER, connection);
        }

        if (udtPipe != null) {
            udtPipe.addLast(CONNECTION_HANDLER, connection);
        }
    }


    // have to setup AFTER establish connection, data, as we don't want to enable AES until we're ready.
    protected final void setupConnection(MetaChannel metaChannel) {
        boolean registerServer = false;

        // now that we are CONNECTED, we want to remove ourselves (and channel ID's) from the map.
        // they will be ADDED in another map, in the followup handler!!
        try {
            IntMap<MetaChannel> channelMap = this.registrationWrapper.getAndLockChannelMap();

            channelMap.remove(metaChannel.tcpChannel.hashCode());
            channelMap.remove(metaChannel.connectionID);


            ChannelPipeline pipeline = metaChannel.tcpChannel.pipeline();
            // The TCP channel is what calls this method, so we can use "this" for TCP, and the others are handled during the registration process
            pipeline.remove(this);

            if (metaChannel.udpChannel != null) {
                // the setup is different between CLIENT / SERVER
                if (metaChannel.udpRemoteAddress == null) {
                    // CLIENT RUNS THIS
                    // don't want to muck with the SERVER udp pipeline, as it NEVER CHANGES.
                    //  More specifically, the UDP SERVER doesn't use a channelMap, it uses the udpRemoteMap
                    //  to keep track of UDP connections. This is very different than how the client works
                    // only the client will have the udp remote address
                    channelMap.remove(metaChannel.udpChannel.hashCode());
                } else {
                    // SERVER RUNS THIS
                    // don't ALWAYS have UDP on SERVER...
                    registerServer = true;
                }
            }
        } finally {
            this.registrationWrapper.releaseChannelMap();
        }

        if (registerServer) {
            // Only called if we have a UDP channel
            setupServerUdpConnection(metaChannel);
        }

        if (this.logger.isInfoEnabled()) {
            String type = "TCP";
            if (metaChannel.udpChannel != null) {
                type += "/UDP";
            }
            if (metaChannel.udtChannel != null) {
                type += "/UDT";
            }

            InetSocketAddress address = (InetSocketAddress)metaChannel.tcpChannel.remoteAddress();
            this.logger.info("Created a {} connection with {}", type, address.getAddress());
        }
    }

    /**
     * Registers the metachannel for the UDP server. Default is to do nothing.
     *
     * The server will override this.
     * Only called if we have a UDP channel when we finalize the setup of the TCP connection
     */
    protected void setupServerUdpConnection(MetaChannel metaChannel) {
    }

    /**
     * Internal call by the pipeline to notify the "Connection" object that it has "connected", meaning that modifications
     * to the pipeline are finished.
     */
    protected final void notifyConnection(MetaChannel metaChannel) {
        this.registrationWrapper.connectionConnected0(metaChannel.connection);
    }

    @Override
    public final void channelInactive(ChannelHandlerContext context) throws Exception {
        Channel channel = context.channel();

        this.logger.info("Closed connection: {}", channel.remoteAddress());

        long maxShutdownWaitTimeInMilliSeconds = EndPoint.maxShutdownWaitTimeInMilliSeconds;
        // also, once we notify, we unregister this.
        // SEARCH for our channel!

        // on the server, we only get this for TCP events!
        try {
            IntMap<MetaChannel> channelMap = this.registrationWrapper.getAndLockChannelMap();
            Entries<MetaChannel> entries = channelMap.entries();
            while (entries.hasNext()) {
                MetaChannel metaChannel = entries.next().value;

                if (metaChannel.tcpChannel == channel || metaChannel.udpChannel == channel || metaChannel.udtChannel == channel) {
                    metaChannel.close(maxShutdownWaitTimeInMilliSeconds);
                    entries.remove();
                    break;
                }
            }

        } finally {
            this.registrationWrapper.releaseChannelMap();
        }

        super.channelInactive(context);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception {
        Channel channel = context.channel();

        this.logger.error("Unexpected exception while trying to send/receive data on Client remote (network) channel.  ({})" + System.getProperty("line.separator"), channel.remoteAddress(), cause);
        if (channel.isOpen()) {
            channel.close();
        }
    }
}
