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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import org.slf4j.Logger;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPointBase;
import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import dorkbox.network.pipeline.udp.KryoDecoderUdp;
import dorkbox.network.pipeline.udp.KryoEncoderUdp;
import dorkbox.network.serialization.CryptoSerializationManager;
import dorkbox.util.bytes.OptimizeUtilsByteArray;
import dorkbox.util.crypto.CryptoAES;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.FixedRecvByteBufAllocator;

public
class RegistrationRemoteHandlerClientUDP<C extends Connection> extends RegistrationRemoteHandlerClient<C> {

    public
    RegistrationRemoteHandlerClientUDP(final String name,
                                       final RegistrationWrapper<C> registrationWrapper,
                                       final CryptoSerializationManager serializationManager) {
        super(name, registrationWrapper, serializationManager);
    }

    /**
     * STEP 1: Channel is first created
     */
    @Override
    protected
    void initChannel(final Channel channel) {
        Logger logger2 = this.logger;
        if (logger2.isTraceEnabled()) {
            logger2.trace("Channel registered: " + channel.getClass()
                                                          .getSimpleName());
        }

        // Netty4 has default of 2048 bytes as upper limit for datagram packets.
        channel.config()
               .setRecvByteBufAllocator(new FixedRecvByteBufAllocator(EndPointBase.udpMaxSize));

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
    public
    void channelActive(final ChannelHandlerContext context) throws Exception {
        super.channelActive(context);

        Channel channel = context.channel();

        // look to see if we already have a connection (in progress) for the destined IP address.
        // Note: our CHANNEL MAP can only have one item at a time, since we do NOT RELEASE the registration lock until it's complete!!

        // The ORDER has to be TCP (always) -> UDP (optional)
        // UDP

        InetSocketAddress udpRemoteAddress = (InetSocketAddress) channel.remoteAddress();
        if (udpRemoteAddress != null) {
            InetAddress udpRemoteServer = udpRemoteAddress.getAddress();

            boolean success = registrationWrapper.associateChannels(channel, udpRemoteServer);
            if (!success) {
                throw new IOException("UDP cannot connect to a remote server before TCP is established!");
            }

            Logger logger2 = this.logger;
            if (logger2.isTraceEnabled()) {
                logger2.trace("Start new UDP Connection. Sending request to server");
            }

            Registration registration = new Registration();
            // client start the handshake with a registration packet
            channel.writeAndFlush(registration);
        }
        else {
            throw new IOException("UDP cannot connect to remote server! No remote address specified!");
        }
    }

    @SuppressWarnings({"AutoUnboxing", "AutoBoxing"})
    @Override
    public
    void channelRead(final ChannelHandlerContext context, Object message) throws Exception {
        Channel channel = context.channel();

        // if we also have a UDP channel, we will receive the "connected" message on UDP (otherwise it will be on TCP)

        RegistrationWrapper<C> registrationWrapper2 = this.registrationWrapper;
        MetaChannel metaChannel = registrationWrapper2.getChannel(channel.hashCode());

        if (metaChannel != null) {
            if (message instanceof Registration) {
                Registration registration = (Registration) message;

                // now decrypt channelID using AES
                byte[] payload = CryptoAES.decrypt(aesEngine.get(), metaChannel.aesKey, metaChannel.aesIV, registration.payload, logger);

                if (!OptimizeUtilsByteArray.canReadInt(payload)) {
                    this.logger.error("Invalid decryption of connection ID. Aborting.");
                    shutdown(registrationWrapper2, channel);
                    return;
                }

                Integer connectionID = OptimizeUtilsByteArray.readInt(payload, true);

                MetaChannel metaChannel2 = registrationWrapper2.getChannel(connectionID);

                if (metaChannel2 != null) {
                    // hooray! we are successful

                    // notify the client that we are ready to continue registering other session protocols (bootstraps)
                    boolean isDoneWithRegistration = registrationWrapper2.registerNextProtocol0();

                    // tell the server we are done, and to setup crypto on it's side
                    if (isDoneWithRegistration) {
                        // bounce it back over TCP, so we can receive a "final" connected message over TCP.
                        metaChannel.tcpChannel.writeAndFlush(registration);

                        // re-sync the TCP delta round trip time
                        metaChannel.updateTcpRoundTripTime();
                    }

                    // since we are done here, we need to REMOVE this handler
                    channel.pipeline()
                           .remove(this);

                    // if we are NOT done, then we will continue registering other protocols, so do nothing else here.
                    return;
                }
            }
        }

        // if we get here, there was an error!

        this.logger.error("Error registering UDP with remote server!");
        shutdown(registrationWrapper2, channel);
    }
}
