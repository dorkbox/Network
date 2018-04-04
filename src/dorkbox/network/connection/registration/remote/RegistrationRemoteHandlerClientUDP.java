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
import java.net.InetSocketAddress;

import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;

@SuppressWarnings("Duplicates")
public
class RegistrationRemoteHandlerClientUDP extends RegistrationRemoteHandlerClient {
    public
    RegistrationRemoteHandlerClientUDP(final String name,
                                       final RegistrationWrapper registrationWrapper,
                                       final EventLoopGroup workerEventLoop) {
        super(name, registrationWrapper, workerEventLoop);
    }

    /**
     * STEP 2: Channel is now active. Start the registration process
     */
    @Override
    public
    void channelActive(final ChannelHandlerContext context) throws Exception {
        super.channelActive(context);

        Channel channel = context.channel();

        InetSocketAddress udpRemoteAddress = (InetSocketAddress) channel.remoteAddress();
        if (udpRemoteAddress != null) {
            Registration outboundRegister = new Registration(0);
            outboundRegister.publicKey = this.registrationWrapper.getPublicKey();

            // check to see if we have an already existing TCP connection to the server, so we can reuse the MetaChannel.
            // UDP will always be registered after TCP
            MetaChannel firstSession = this.registrationWrapper.getFirstSession();
            if (firstSession != null) {
                outboundRegister.sessionID = firstSession.sessionId;
                outboundRegister.hasMore = registrationWrapper.hasMoreRegistrations();
            }

            // no size info, since this is UDP, it is not segmented
            channel.writeAndFlush(outboundRegister);
        }
        else {
            throw new IOException("UDP cannot connect to remote server! No remote address specified!");
        }
    }

    @Override
    public
    void channelRead(final ChannelHandlerContext context, Object message) throws Exception {
        // REGISTRATION is the ONLY thing NOT encrypted. ALSO, this handler is REMOVED once registration is complete

        Channel channel = context.channel();

        if (message instanceof Registration) {
            Registration registration = (Registration) message;

            MetaChannel metaChannel;
            int sessionId = registration.sessionID;

            if (sessionId == 0) {
                logger.error("Invalid UDP channel session ID 0!");
                return;
            }
            else {
                metaChannel = registrationWrapper.getSession(sessionId);

                if (metaChannel == null) {
                    metaChannel = registrationWrapper.createSessionClient(sessionId);

                    logger.debug("New UDP connection. Saving meta-channel id: {}", metaChannel.sessionId);
                }
                else if (metaChannel.udpChannel == null) {
                    logger.debug("Using TCP connection meta-channel for UDP connection");
                }

                // in the event that we start with a TCP channel first, we still have to set the UDP channel
                metaChannel.udpChannel = channel;

                // have to add a way for us to store messages in case the remote end calls "onConnect()" and sends messages before we are ready.
                // note: UDP channels are also unique (just like TCP channels) because of the SessionManager we added
                prepChannelForOutOfOrderMessages(channel);
            }

            readClient(channel, registration, "UDP client", metaChannel);
        }
        else {
            logger.trace("Out of order UDP message from server!");
            saveOutOfOrderMessage(channel, message);
        }
    }
}
