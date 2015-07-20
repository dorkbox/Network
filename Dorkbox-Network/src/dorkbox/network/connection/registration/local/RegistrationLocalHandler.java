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
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.RegistrationHandler;
import dorkbox.util.collections.IntMap;
import dorkbox.util.collections.IntMap.Entries;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import org.slf4j.Logger;

public abstract
class RegistrationLocalHandler extends RegistrationHandler {

    public
    RegistrationLocalHandler(String name, RegistrationWrapper registrationWrapper) {
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

        try {
            IntMap<MetaChannel> channelMap = this.registrationWrapper.getAndLockChannelMap();
            channelMap.put(channel.hashCode(), metaChannel);
        } finally {
            this.registrationWrapper.releaseChannelMap();
        }

        Logger logger2 = this.logger;
        if (logger2.isTraceEnabled()) {
            logger2.trace("New LOCAL connection.");
        }

        this.registrationWrapper.connection0(metaChannel);

        // have to setup connection handler
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast(CONNECTION_HANDLER, (ConnectionImpl) metaChannel.connection);
    }

    /**
     * STEP 2: Channel is now active. Start the registration process
     */
    @Override
    public
    void channelActive(ChannelHandlerContext context) throws Exception {
        Channel channel = context.channel();

        //noinspection StringBufferReplaceableByString
        StringBuilder builder = new StringBuilder(76);
        builder.append("Connected to LOCAL connection. [");
        builder.append(context.channel()
                              .localAddress());
        builder.append(getConnectionDirection());
        builder.append(channel.remoteAddress());
        builder.append("]");

        this.logger.debug(builder.toString());
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

    /**
     * @return the direction that traffic is going to this handler (" <== " or " ==> ")
     */
    protected abstract
    String getConnectionDirection();

    // this SHOULDN'T ever happen, but we might shutdown in the middle of registration
    @Override
    public final
    void channelInactive(ChannelHandlerContext context) throws Exception {
        Channel channel = context.channel();

        this.logger.info("Closed LOCAL connection: {}", channel.remoteAddress());

        long maxShutdownWaitTimeInMilliSeconds = EndPoint.maxShutdownWaitTimeInMilliSeconds;

        // also, once we notify, we unregister this.

        try {
            IntMap<MetaChannel> channelMap = this.registrationWrapper.getAndLockChannelMap();
            Entries<MetaChannel> entries = channelMap.entries();
            while (entries.hasNext()) {
                MetaChannel metaChannel = entries.next().value;

                if (metaChannel.localChannel == channel) {
                    metaChannel.close(maxShutdownWaitTimeInMilliSeconds);
                    entries.remove();
                    break;
                }
            }
        } finally {
            this.registrationWrapper.releaseChannelMap();
        }

        super.channelInactive(context);
    }
}

