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

import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.RegistrationWrapperServer;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;

@SuppressWarnings("Duplicates")
@Sharable
public
class RegistrationRemoteHandlerServerUDP extends RegistrationRemoteHandlerServer {
    public
    RegistrationRemoteHandlerServerUDP(final String name,
                                       final RegistrationWrapperServer registrationWrapper,
                                       final EventLoopGroup workerEventLoop) {
        super(name, registrationWrapper, workerEventLoop);
    }

    /**
     * STEP 3-XXXXX: We pass registration messages around until we the registration handshake is complete!
     */
    @Override
    public
    void channelRead(final ChannelHandlerContext context, Object message) throws Exception {
        Channel channel = context.channel();

        if (message instanceof Registration) {
            Registration registration = (Registration) message;

            MetaChannel metaChannel;
            int sessionId = 0;
            sessionId = registration.sessionID;
            if (sessionId == 0) {
                metaChannel = registrationWrapper.createSession();
                metaChannel.udpChannel = channel;
                logger.debug("New UDP connection. Saving meta-channel id: {}", metaChannel.sessionId);
            }
            else {
                metaChannel = registrationWrapper.getSession(sessionId);

                if (metaChannel == null) {
                    logger.error("Error getting invalid UDP channel session ID {}! MetaChannel is null!", sessionId);
                    shutdown(channel, sessionId);
                    return;
                }

                // in the event that we start with a TCP channel first, we still have to set the UDP channel
                metaChannel.udpChannel = channel;
            }

            readServer(context, channel, registration, "UDP server", metaChannel);
        }
        else if (message instanceof io.netty.channel.socket.DatagramPacket) {
            logger.error("Error registering UDP with remote client!");
            shutdown(channel, 0);
        }
        else {
            logger.error("Error registering UDP with remote client! Attempting to queue message: " + message.getClass());

            // this is what happens when the registration happens too quickly...
            Object connection = context.pipeline().last();
            if (connection instanceof ConnectionImpl) {
                ((ConnectionImpl) connection).channelRead(context, message);
            }
            else {
                shutdown(channel, 0);
            }
        }
    }
}
