package dorkbox.network.connection.registration.remote;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.util.ReferenceCountUtil;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import org.slf4j.Logger;

import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import dorkbox.network.pipeline.udp.KryoDecoderUdp;
import dorkbox.network.pipeline.udp.KryoEncoderUdp;
import dorkbox.network.util.SerializationManager;
import dorkbox.util.bytes.OptimizeUtilsByteArray;
import dorkbox.util.collections.IntMap;
import dorkbox.util.collections.IntMap.Entries;
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
        Logger logger2 = this.logger;
        if (logger2.isTraceEnabled()) {
            logger2.trace("Channel registered: " + channel.getClass().getSimpleName());
        }

        ChannelPipeline pipeline = channel.pipeline();

        // UDP
        // add first to "inject" these handlers in front of myself.
        // this is only called ONCE for UDP for the CLIENT.
        pipeline.addFirst(RegistrationRemoteHandler.KRYO_DECODER, new KryoDecoderUdp(this.serializationManager));
        pipeline.addFirst(RegistrationRemoteHandler.KRYO_ENCODER, new KryoEncoderUdp(this.serializationManager));
    }

    /**
     * STEP 2: Channel is now active. Start the registration process
     */
    @Override
    public void channelActive(ChannelHandlerContext context) throws Exception {
        Logger logger2 = this.logger;
        if (logger2.isDebugEnabled()) {
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


            RegistrationWrapper registrationWrapper2 = this.registrationWrapper;
            try {
                IntMap<MetaChannel> channelMap = registrationWrapper2.getAndLockChannelMap();
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
                registrationWrapper2.releaseChannelMap();
            }

            if (!success) {
                throw new RuntimeException("UDP cannot connect to a remote server before TCP is established!");
            }

            if (logger2.isTraceEnabled()) {
                logger2.trace("Start new UDP Connection. Sending request to server");
            }

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
        RegistrationWrapper registrationWrapper2 = this.registrationWrapper;
        try {
            IntMap<MetaChannel> channelMap = registrationWrapper2.getAndLockChannelMap();
            metaChannel = channelMap.get(channel.hashCode());
        } finally {
            registrationWrapper2.releaseChannelMap();
        }

        if (metaChannel != null) {
            if (message instanceof Registration) {
                Registration registration = (Registration) message;

                // now decrypt channelID using AES
                byte[] payload = Crypto.AES.decrypt(getAesEngine(), metaChannel.aesKey, metaChannel.aesIV, registration.payload);

                OptimizeUtilsByteArray optimizeUtils = OptimizeUtilsByteArray.get();
                if (!optimizeUtils.canReadInt(payload)) {
                    this.logger.error("Invalid decryption of connection ID. Aborting.");
                    shutdown(registrationWrapper2, channel);

                    ReferenceCountUtil.release(message);
                    return;
                }

                Integer connectionID = optimizeUtils.readInt(payload, true);

                MetaChannel metaChannel2 = null;
                try {
                    IntMap<MetaChannel> channelMap = registrationWrapper2.getAndLockChannelMap();
                    metaChannel2 = channelMap.get(connectionID);
                } finally {
                    registrationWrapper2.releaseChannelMap();
                }

                if (metaChannel2 != null) {
                    // hooray! we are successful

                    // notify the client that we are ready to continue registering other session protocols (bootstraps)
                    boolean isDoneWithRegistration = registrationWrapper2.continueRegistration0();

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

        this.logger.error("Error registering UDP with remote server!");
        shutdown(registrationWrapper2, channel);

        ReferenceCountUtil.release(message);
    }
}
