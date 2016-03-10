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
import dorkbox.util.crypto.CryptoAES;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;

import java.net.InetAddress;
import java.net.InetSocketAddress;

public
class RegistrationRemoteHandlerServerUDT<C extends Connection> extends RegistrationRemoteHandlerServer<C> {

    public
    RegistrationRemoteHandlerServerUDT(final String name,
                                       final RegistrationWrapper<C> registrationWrapper,
                                       final CryptoSerializationManager serializationManager) {
        super(name, registrationWrapper, serializationManager);
    }

    /**
     * STEP 1: Channel is first created (This is TCP/UDT only, as such it differs from the client which is TCP/UDP)
     */
    @Override
    protected
    void initChannel(final Channel channel) {
        super.initChannel(channel);
    }

    /**
     * STEP 2: Channel is now active. Prepare the meta channel to listen for the registration process
     */
    @Override
    public
    void channelActive(final ChannelHandlerContext context) throws Exception {
        super.channelActive(context);

        // UDT channels are added when the registration request arrives on a UDT channel.
    }

    /**
     * STEP 3-XXXXX: We pass registration messages around until we the registration handshake is complete!
     */
    @SuppressWarnings("AutoUnboxing")
    @Override
    public
    void channelRead(final ChannelHandlerContext context, final Object message) throws Exception {
        Channel channel = context.channel();

        // only TCP will come across here for the server. (UDP here is called by the UDP handler/wrapper)

        RegistrationWrapper<C> registrationWrapper2 = this.registrationWrapper;
        Logger logger2 = this.logger;

        if (message instanceof Registration) {
            // find out and make sure that UDP and TCP are talking to the same server
            InetAddress udtRemoteAddress = ((InetSocketAddress) channel.remoteAddress()).getAddress();

            boolean matches = false;
            MetaChannel metaChannel = null;
            try {
                IntMap<MetaChannel> channelMap = registrationWrapper2.getAndLockChannelMap();
                Entries<MetaChannel> entries = channelMap.entries();
                while (entries.hasNext()) {
                    metaChannel = entries.next().value;

                    // only look at connections that do not have UDT already setup.
                    if (metaChannel.udtChannel == null) {
                        InetSocketAddress tcpRemote = (InetSocketAddress) metaChannel.tcpChannel.remoteAddress();
                        InetAddress tcpRemoteAddress = tcpRemote.getAddress();

                        if (checkEqual(tcpRemoteAddress, udtRemoteAddress)) {
                            matches = true;
                        }
                        else {
                            if (logger2.isErrorEnabled()) {
                                logger2.error(this.name,
                                              "Mismatch UDT and TCP client addresses! UDP: {}  TCP: {}",
                                              udtRemoteAddress,
                                              tcpRemoteAddress);
                            }
                            shutdown(registrationWrapper2, channel);
                            ReferenceCountUtil.release(message);
                            return;
                        }
                    }
                }

            } finally {
                registrationWrapper2.releaseChannelMap();
            }

            if (matches) {
                // associate TCP and UDT!
                metaChannel.udtChannel = channel;

                Registration register = new Registration();

                // save off the connectionID as a byte array, then encrypt it
                int intLength = OptimizeUtilsByteArray.intLength(metaChannel.connectionID, true);
                byte[] idAsBytes = new byte[intLength];
                OptimizeUtilsByteArray.writeInt(idAsBytes, metaChannel.connectionID, true);

                // now encrypt payload via AES
                register.payload = CryptoAES.encrypt(RegistrationRemoteHandler.aesEngine.get(),
                                                     metaChannel.aesKey,
                                                     metaChannel.aesIV,
                                                     idAsBytes,
                                                     logger);

                // send back, so the client knows that UDP was ok. We include the encrypted connection ID, so the client knows it's a legit server
                channel.writeAndFlush(register);

                // since we are done here, we need to REMOVE this handler
                channel.pipeline()
                       .remove(this);

                if (logger2.isTraceEnabled()) {
                    logger2.trace("Register UDT connection from {}", udtRemoteAddress);
                }
                ReferenceCountUtil.release(message);
            }
            else {
                // if we get here, there was a failure!
                if (logger2.isErrorEnabled()) {
                    logger2.error("Error trying to register UDT without udt specified! UDT: {}", udtRemoteAddress);
                }
                shutdown(registrationWrapper2, channel);
                ReferenceCountUtil.release(message);
            }
        }
        else {
            if (logger2.isErrorEnabled()) {
                logger2.error("UDT attempting to spoof client! Unencrypted packet other than registration received.");
            }
            shutdown(registrationWrapper2, channel);
            ReferenceCountUtil.release(message);
        }
    }
}
