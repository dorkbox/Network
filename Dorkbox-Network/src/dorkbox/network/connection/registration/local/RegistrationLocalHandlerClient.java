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
import dorkbox.network.connection.registration.Registration;
import dorkbox.util.collections.IntMap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;

public
class RegistrationLocalHandlerClient extends RegistrationLocalHandler {

    public
    RegistrationLocalHandlerClient(String name, RegistrationWrapper registrationWrapper) {
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
        if (logger.isDebugEnabled()) {
            super.channelActive(context);
        }

        // client starts the registration process
        Channel channel = context.channel();
        channel.writeAndFlush(new Registration());
    }

    /**
     * @return the direction that traffic is going to this handler (" <== " or " ==> ")
     */
    @Override
    protected
    String getConnectionDirection() {
        return " ==> ";
    }

    @Override
    public
    void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        ReferenceCountUtil.release(message);

        Channel channel = context.channel();

        MetaChannel metaChannel = null;
        try {
            IntMap<MetaChannel> channelMap = registrationWrapper.getAndLockChannelMap();
            metaChannel = channelMap.remove(channel.hashCode());
        } finally {
            registrationWrapper.releaseChannelMap();
        }

        // have to setup new listeners
        if (metaChannel != null) {
            channel.pipeline()
                   .remove(this);

            // Event though a local channel is XOR with everything else, we still have to make the client clean up it's state.
            registrationWrapper.registerNextProtocol0();

            ConnectionImpl connection = metaChannel.connection;
            registrationWrapper.connectionConnected0(connection);
        }
        else {
            // this should NEVER happen!
            logger.error("Error registering LOCAL channel! MetaChannel is null!");
            shutdown(registrationWrapper, channel);
        }
    }
}

