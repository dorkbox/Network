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

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.util.ReferenceCountUtil;

public
class RegistrationLocalHandlerClient<C extends Connection> extends RegistrationLocalHandler<C> {

    public
    RegistrationLocalHandlerClient(String name, RegistrationWrapper<C> registrationWrapper) {
        super(name, registrationWrapper);
    }

    /**
     * STEP 1: Channel is first created
     */
//  Calls the super class to init the local channel
//  @Override
//  protected void initChannel(Channel channel) {
//  }

    /**
     * STEP 2: Channel is now active. Start the registration process
     */
    @Override
    public
    void channelActive(ChannelHandlerContext context) throws Exception {
        super.channelActive(context);

        Channel channel = context.channel();
        logger.info("Connected to LOCAL connection. [{} ==> {}]",
                    context.channel()
                           .localAddress(),
                    channel.remoteAddress());

        // client starts the registration process
        channel.writeAndFlush(new Registration());
    }

    @Override
    public
    void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        ReferenceCountUtil.release(message);

        Channel channel = context.channel();

        MetaChannel metaChannel = this.registrationWrapper.removeChannel(channel.hashCode());

        // have to setup new listeners
        if (metaChannel != null) {
            ChannelPipeline pipeline = channel.pipeline();
            pipeline.remove(this);

            // Event though a local channel is XOR with everything else, we still have to make the client clean up it's state.
            registrationWrapper.registerNextProtocol0();

            ConnectionImpl connection = metaChannel.connection;


            // add our RMI handlers
            if (registrationWrapper.rmiEnabled()) {
                ///////////////////////
                // DECODE (or upstream)
                ///////////////////////
                pipeline.addFirst(LOCAL_RMI_HANDLER, rmiLocalHandler);
            }

            // have to setup connection handler
            pipeline.addLast(CONNECTION_HANDLER, connection);

            registrationWrapper.connectionConnected0(connection);
        }
        else {
            // this should NEVER happen!
            logger.error("Error registering LOCAL channel! MetaChannel is null!");
            shutdown(registrationWrapper, channel);
        }
    }
}

