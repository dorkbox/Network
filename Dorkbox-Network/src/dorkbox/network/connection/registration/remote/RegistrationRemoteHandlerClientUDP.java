package dorkbox.network.connection.registration.remote;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.util.ReferenceCountUtil;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import dorkbox.network.pipeline.udp.KryoDecoderUdp;
import dorkbox.network.pipeline.udp.KryoEncoderUdp;
import dorkbox.network.util.SerializationManager;
import dorkbox.network.util.primativeCollections.IntMap;
import dorkbox.network.util.primativeCollections.IntMap.Entries;
import dorkbox.util.bytes.OptimizeUtils;
import dorkbox.util.crypto.Crypto;

public class RegistrationRemoteHandlerClientUDP extends RegistrationRemoteHandlerClient {

    public RegistrationRemoteHandlerClientUDP(String name, RegistrationWrapper registrationWrapper, SerializationManager serializationManager) {
        super(name, registrationWrapper, serializationManager);
    }

    /**
     * STEP 1: Channel is first created
     */
    @Override
    protected void initChannel(Channel channel) {
        logger.trace("Channel registered: " + channel.getClass().getSimpleName());

        ChannelPipeline pipeline = channel.pipeline();

        // UDP
        // add first to "inject" these handlers in front of myself.
        // this is only called ONCE for UDP for the CLIENT.
        pipeline.addFirst(RegistrationRemoteHandler.KRYO_DECODER, new KryoDecoderUdp(serializationManager));
        pipeline.addFirst(RegistrationRemoteHandler.KRYO_ENCODER, new KryoEncoderUdp(serializationManager));
    }

    /**
     * STEP 2: Channel is now active. Start the registration process
     */
    @Override
    public void channelActive(ChannelHandlerContext context) throws Exception {
        if (logger.isDebugEnabled()) {
           super.channelActive(context);
        }

        Channel channel = context.channel();

        // look to see if we already have a connection (in progress) for the destined IP address.
        // Note: our CHANNEL MAP can only have one item at a time, since we do NOT RELEASE the registration lock until it's complete!!

        // The ORDER has to be TCP (always) -> UDP (optional) -> UDT (optional)
        // UDP
        boolean success = false;
        InetSocketAddress udpRemoteAddress = (InetSocketAddress) channel.remoteAddress();
        if (udpRemoteAddress != null) {
            InetAddress udpRemoteServer = udpRemoteAddress.getAddress();


            try {
                IntMap<MetaChannel> channelMap = registrationWrapper.getAndLockChannelMap();
                Entries<MetaChannel> entries = channelMap.entries();
                while (entries.hasNext()) {
                    MetaChannel metaChannel = entries.next().value;

                    // associate TCP and UDP!
                    InetAddress tcpRemoteServer = ((InetSocketAddress) metaChannel.tcpChannel.remoteAddress()).getAddress();
                    if (checkEqual(tcpRemoteServer, udpRemoteServer)) {
                        channelMap.put(channel.hashCode(), metaChannel);
                        metaChannel.udpChannel = channel;
                        success = true;
                        // only allow one server per registration!
                        break;
                    }
                }
            } finally {
                registrationWrapper.releaseChannelMap();
            }

            if (!success) {
                throw new RuntimeException("UDP cannot connect to a remote server before TCP is established!");
            }

            logger.trace("Start new UDP Connection. Sending request to server");

            Registration registration = new Registration();
            // client start the handshake with a registration packet
            channel.writeAndFlush(registration);
        } else {
            throw new RuntimeException("UDP cannot connect to remote server! No remote address specified!");
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        Channel channel = context.channel();

        // if we also have a UDP channel, we will receive the "connected" message on UDP (otherwise it will be on TCP)

        MetaChannel metaChannel = null;
        try {
            IntMap<MetaChannel> channelMap = registrationWrapper.getAndLockChannelMap();
            metaChannel = channelMap.get(channel.hashCode());
        } finally {
            registrationWrapper.releaseChannelMap();
        }

        if (metaChannel != null) {
            if (message instanceof Registration) {
                Registration registration = (Registration) message;

                // now decrypt channelID using AES
                byte[] payload = Crypto.AES.decrypt(getAesEngine(), metaChannel.aesKey, metaChannel.aesIV, registration.payload);

                OptimizeUtils optimizeUtils = OptimizeUtils.get();
                if (!optimizeUtils.canReadInt(payload)) {
                    logger.error("Invalid decryption of connection ID. Aborting.");
                    shutdown(registrationWrapper, channel);

                    ReferenceCountUtil.release(message);
                    return;
                }

                Integer connectionID = optimizeUtils.readInt(payload, true);

                MetaChannel metaChannel2 = null;
                try {
                    IntMap<MetaChannel> channelMap = registrationWrapper.getAndLockChannelMap();
                    metaChannel2 = channelMap.get(connectionID);
                } finally {
                    registrationWrapper.releaseChannelMap();
                }

                if (metaChannel2 != null) {
                    // hooray! we are successful

                    // notify the client that we are ready to continue registering other session protocols (bootstraps)
                    boolean isDoneWithRegistration = registrationWrapper.continueRegistration0();

                    // tell the server we are done, and to setup crypto on it's side
                    if (isDoneWithRegistration) {
                        // bounce it back over TCP, so we can receive a "final" connected message over TCP.
                        metaChannel.tcpChannel.writeAndFlush(registration);

                        // re-sync the TCP delta round trip time
                        metaChannel.updateTcpRoundTripTime();
                    }

                    // since we are done here, we need to REMOVE this handler
                    channel.pipeline().remove(this);

                    // if we are NOT done, then we will continue registering other protocols, so do nothing else here.
                    ReferenceCountUtil.release(message);
                    return;
                }
            }
        }

        // if we get here, there was an error!

        logger.error("Error registering UDP with remote server!");
        shutdown(registrationWrapper, channel);

        ReferenceCountUtil.release(message);
    }
}
