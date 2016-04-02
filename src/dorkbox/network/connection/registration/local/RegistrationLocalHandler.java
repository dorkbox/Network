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
import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.RegistrationHandler;
import dorkbox.network.pipeline.LocalRmiDecoder;
import dorkbox.network.pipeline.LocalRmiEncoder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;

import static dorkbox.network.connection.EndPoint.maxShutdownWaitTimeInMilliSeconds;

public abstract
class RegistrationLocalHandler<C extends Connection> extends RegistrationHandler<C> {
    protected static final String LOCAL_RMI_ENCODER = "localRmiEncoder";
    protected static final String LOCAL_RMI_DECODER = "localRmiDecoder";

    protected final LocalRmiEncoder encoder = new LocalRmiEncoder();
    protected final LocalRmiDecoder decoder = new LocalRmiDecoder();

    public
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

        this.registrationWrapper.addChannel(channel.hashCode(), metaChannel);

        Logger logger2 = this.logger;
        if (logger2.isTraceEnabled()) {
            logger2.trace("New LOCAL connection.");
        }

        this.registrationWrapper.connection0(metaChannel);
    }

    @Override
    public
    void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception {
        Channel channel = context.channel();

        this.logger.error(
                        "Unexpected exception while trying to receive data on LOCAL channel.  ({})" + System.getProperty("line.separator"),
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

        this.logger.info("Closed LOCAL connection: {}", channel.remoteAddress());

        // also, once we notify, we unregister this.
        registrationWrapper.closeChannel(channel, maxShutdownWaitTimeInMilliSeconds);

        super.channelInactive(context);
    }
}

