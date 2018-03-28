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
import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.util.ReferenceCountUtil;

public
class RegistrationLocalHandlerServer extends RegistrationLocalHandler {

    public
    RegistrationLocalHandlerServer(String name, RegistrationWrapper registrationWrapper) {
        super(name, registrationWrapper);
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

        // have to remove the pipeline FIRST, since if we don't, and we expect to receive a message --- when we REMOVE "this" from the pipeline,
        // we will ALSO REMOVE all it's messages, which we want to receive!
        pipeline.remove(this);

        channel.writeAndFlush(message);

        ReferenceCountUtil.release(message);
        logger.trace("Sent registration");

        MetaChannel metaChannel = channel.attr(META_CHANNEL)
                                         .getAndSet(null);
        if (metaChannel != null) {
            registrationWrapper.connection0(metaChannel, null);
            ConnectionImpl connection = metaChannel.connection;

            if (connection != null) {
                // have to setup connection handler
                pipeline.addLast(CONNECTION_HANDLER, connection);

                registrationWrapper.connectionConnected0(connection);
            }
        }
    }
}

