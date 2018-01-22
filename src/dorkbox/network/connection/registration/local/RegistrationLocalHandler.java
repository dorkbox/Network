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

import static dorkbox.network.connection.EndPointBase.maxShutdownWaitTimeInMilliSeconds;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.RegisterRmiLocalHandler;
import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.RegistrationHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

public abstract
class RegistrationLocalHandler<C extends Connection> extends RegistrationHandler<C> {
    static final String LOCAL_RMI_HANDLER = "localRmiHandler";
    final RegisterRmiLocalHandler rmiLocalHandler = new RegisterRmiLocalHandler();

    RegistrationLocalHandler(String name, RegistrationWrapper<C> registrationWrapper) {
        super(name, registrationWrapper);
    }

    /**
     * STEP 1: Channel is first created
     */
    @Override
    protected
    void initChannel(Channel channel) {
        MetaChannel metaChannel = new MetaChannel();
        metaChannel.localChannel = channel;

        registrationWrapper.addChannel(channel.hashCode(), metaChannel);

        logger.trace("New LOCAL connection.");

        registrationWrapper.connection0(metaChannel);
    }

    @Override
    public
    void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception {
        Channel channel = context.channel();

        logger.error("Unexpected exception while trying to receive data on LOCAL channel.  ({})" + System.getProperty("line.separator"),
                     channel.remoteAddress(),
                     cause);

        if (channel.isOpen()) {
            channel.close();
        }
    }

    @Override
    public
    void channelActive(ChannelHandlerContext context) throws Exception {
        // not used (so we prevent the warnings from the super class)
    }

    // this SHOULDN'T ever happen, but we might shutdown in the middle of registration
    @Override
    public final
    void channelInactive(ChannelHandlerContext context) throws Exception {
        Channel channel = context.channel();

        logger.info("Closed LOCAL connection: {}", channel.remoteAddress());

        // also, once we notify, we unregister this.
        registrationWrapper.closeChannel(channel, maxShutdownWaitTimeInMilliSeconds);

        super.channelInactive(context);
    }
}

