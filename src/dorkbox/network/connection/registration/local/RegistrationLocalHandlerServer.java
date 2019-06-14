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
package dorkbox.network.connection.registration.local;

import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.RegistrationWrapper.STATE;
import dorkbox.network.connection.RegistrationWrapperServer;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.util.ReferenceCountUtil;

public
class RegistrationLocalHandlerServer extends RegistrationLocalHandler<RegistrationWrapperServer> {


    public
    RegistrationLocalHandlerServer(String name, RegistrationWrapperServer registrationWrapper) {
        super(name, registrationWrapper);
    }

    /**
     * STEP 1: Channel is first created
     */
    @Override
    protected
    void initChannel(Channel channel) {
        MetaChannel metaChannel = registrationWrapper.createSession();
        metaChannel.localChannel = channel;

        channel.attr(META_CHANNEL)
               .set(metaChannel);

        logger.trace("New LOCAL connection.");
    }

    /**
     * STEP 2: Channel is now active. Start the registration process (Client starts the process)
     */
    @Override
    public
    void channelActive(ChannelHandlerContext context) throws Exception {
        Channel channel = context.channel();
        logger.info("Connected to LOCAL connection. [{} <== {}]",
                    context.channel()
                           .localAddress(),
                    channel.remoteAddress());


        super.channelActive(context);
    }

    @Override
    public
    void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        Channel channel = context.channel();
        ChannelPipeline pipeline = channel.pipeline();

        if (!(message instanceof Registration)) {
            logger.error("Expected registration message was [{}] instead!", message.getClass());
            shutdown(channel, 0);
            ReferenceCountUtil.release(message);
            return;
        }

        MetaChannel metaChannel = channel.attr(META_CHANNEL).get();


        if (metaChannel == null) {
            logger.error("Server MetaChannel was null. It shouldn't be.");
            shutdown(channel, 0);
            ReferenceCountUtil.release(message);
            return;
        }

        Registration registration = (Registration) message;

        // verify the class ID registration details.
        // the client will send their class registration data. VERIFY IT IS CORRECT!
        STATE state = registrationWrapper.verifyClassRegistration(metaChannel, registration);
        if (state == STATE.ERROR) {
            // abort! There was an error
            shutdown(channel, 0);
            return;
        }
        else if (state == STATE.WAIT) {
            return;
        }
        // else, continue.



        // have to remove the pipeline FIRST, since if we don't, and we expect to receive a message --- when we REMOVE "this" from the pipeline,
        // we will ALSO REMOVE all it's messages, which we want to receive!
        pipeline.remove(this);

        registration.payload = null;

        // we no longer need the meta channel, so remove it
        channel.attr(META_CHANNEL).set(null);
        channel.writeAndFlush(registration);

        ReferenceCountUtil.release(registration);
        logger.trace("Sent registration");

        ConnectionImpl connection = registrationWrapper.connection0(metaChannel, null);

        if (connection != null) {
            // have to setup connection handler
            pipeline.addLast(CONNECTION_HANDLER, connection);
            registrationWrapper.connectionConnected0(connection);
        }
    }
}

