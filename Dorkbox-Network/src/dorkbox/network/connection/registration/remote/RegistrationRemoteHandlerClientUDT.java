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

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import dorkbox.network.util.CryptoSerializationManager;
import dorkbox.util.bytes.OptimizeUtilsByteArray;
import dorkbox.util.collections.IntMap;
import dorkbox.util.collections.IntMap.Entries;
import dorkbox.util.crypto.Crypto;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

public
class RegistrationRemoteHandlerClientUDT<C extends Connection> extends RegistrationRemoteHandlerClient<C> {

    public
    RegistrationRemoteHandlerClientUDT(final String name,
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
        this.logger.trace("Channel registered: {}",
                          channel.getClass()
                                 .getSimpleName());

        // TCP & UDT

        // use the default.
        super.initChannel(channel);
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

        // The ORDER has to be TCP (always) -> UDP (optional) -> UDT (optional)
        // UDT
        boolean success = false;
        InetSocketAddress udtRemoteAddress = (InetSocketAddress) channel.remoteAddress();
        if (udtRemoteAddress != null) {
            InetAddress udtRemoteServer = udtRemoteAddress.getAddress();

            RegistrationWrapper<C> registrationWrapper2 = this.registrationWrapper;
            try {
                IntMap<MetaChannel> channelMap = registrationWrapper2.getAndLockChannelMap();
                Entries<MetaChannel> entries = channelMap.entries();
                while (entries.hasNext()) {
                    MetaChannel metaChannel = entries.next().value;

                    // associate TCP and UDP!
                    InetAddress tcpRemoteServer = ((InetSocketAddress) metaChannel.tcpChannel.remoteAddress()).getAddress();
                    if (checkEqual(tcpRemoteServer, udtRemoteServer)) {
                        channelMap.put(channel.hashCode(), metaChannel);
                        metaChannel.udtChannel = channel;
                        success = true;
                        // only allow one server per registration!
                        break;
                    }
                }

            } finally {
                registrationWrapper2.releaseChannelMap();
            }

            if (!success) {
                throw new IOException("UDT cannot connect to a remote server before TCP is established!");
            }

            Logger logger2 = this.logger;
            if (logger2.isTraceEnabled()) {
                logger2.trace("Start new UDT Connection. Sending request to server");
            }

            Registration registration = new Registration();
            // client start the handshake with a registration packet
            channel.writeAndFlush(registration);
        }
        else {
            throw new IOException("UDT cannot connect to remote server! No remote address specified!");
        }
    }


    @SuppressWarnings({"AutoUnboxing", "AutoBoxing"})
    @Override
    public
    void channelRead(final ChannelHandlerContext context, final Object message) throws Exception {
        Channel channel = context.channel();

        // if we also have a UDP channel, we will receive the "connected" message on UDP (otherwise it will be on TCP)
        MetaChannel metaChannel = null;

        RegistrationWrapper<C> registrationWrapper2 = this.registrationWrapper;
        try {
            IntMap<MetaChannel> channelMap = registrationWrapper2.getAndLockChannelMap();
            metaChannel = channelMap.get(channel.hashCode());
        } finally {
            registrationWrapper2.releaseChannelMap();
        }

        Logger logger2 = this.logger;
        if (metaChannel != null) {
            if (message instanceof Registration) {
                Registration registration = (Registration) message;

                // now decrypt channelID using AES
                byte[] payload = Crypto.AES.decrypt(getAesEngine(), metaChannel.aesKey, metaChannel.aesIV, registration.payload, logger);

                OptimizeUtilsByteArray optimizeUtils = OptimizeUtilsByteArray.get();
                if (!optimizeUtils.canReadInt(payload)) {
                    logger2.error("Invalid decryption of connection ID. Aborting.");
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
                    ReferenceCountUtil.release(message);
                    return;
                }
            }
        }

        // if we get here, there was an error!

        logger2.error("Error registering UDT with remote server!");
        shutdown(registrationWrapper2, channel);
        ReferenceCountUtil.release(message);
    }
}
