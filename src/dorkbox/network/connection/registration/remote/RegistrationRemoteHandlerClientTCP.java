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

import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;

public
class RegistrationRemoteHandlerClientTCP extends RegistrationRemoteHandlerClient {
    public
    RegistrationRemoteHandlerClientTCP(final String name,
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

        logger.trace("Start new TCP Connection. Sending request to server");

        Registration registration = new Registration(0);
        registration.publicKey = this.registrationWrapper.getPublicKey();

        // client start the handshake with a registration packet
        context.channel().writeAndFlush(registration);
    }

    @SuppressWarnings({"AutoUnboxing", "AutoBoxing", "Duplicates"})
    @Override
    public
    void channelRead(final ChannelHandlerContext context, final Object message) throws Exception {
        Channel channel = context.channel();

        if (message instanceof Registration) {
            Registration registration = (Registration) message;

            MetaChannel metaChannel;
            int sessionId = registration.sessionID;

            if (sessionId == 0) {
                logger.error("Invalid TCP channel session ID 0!");
                shutdown(channel, 0);
                return;
            }
            else {
                metaChannel = registrationWrapper.getSession(sessionId);

                // TCP channel registration is ALWAYS first, so this is the correct way to do this.
                if (metaChannel == null) {
                    metaChannel = registrationWrapper.createSessionClient(sessionId);
                    metaChannel.tcpChannel = channel;

                    logger.debug("New TCP connection. Saving meta-channel id: {}", metaChannel.sessionId);
                }

                // have to add a way for us to store messages in case the remote end calls "onConnect()" and sends messages before we are ready.
                prepChannelForOutOfOrderMessages(channel);
            }

            logger.trace("TCP read");
            readClient(channel, registration, "TCP client", metaChannel);
        }
        else {
            logger.trace("Out of order TCP message from server!");
            saveOutOfOrderMessage(channel, message);
        }
    }
}
