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
package dorkbox.network.connection.registration;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.util.collections.IntMap;
import dorkbox.util.collections.IntMap.Entries;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

@Sharable
public abstract
class RegistrationHandler<C extends Connection> extends ChannelInboundHandlerAdapter {
    protected static final String CONNECTION_HANDLER = "connectionHandler";

    protected final RegistrationWrapper<C> registrationWrapper;
    protected final org.slf4j.Logger logger;
    protected final String name;


    public
    RegistrationHandler(String name, RegistrationWrapper<C> registrationWrapper) {
        this.name = name + " Discovery/Registration";
        this.logger = org.slf4j.LoggerFactory.getLogger(this.name);
        this.registrationWrapper = registrationWrapper;
    }

    @SuppressWarnings("unused")
    protected
    void initChannel(Channel channel) {
    }

    @Override
    public final
    void channelRegistered(ChannelHandlerContext context) throws Exception {
        boolean success = false;
        try {
            initChannel(context.channel());
            context.fireChannelRegistered();
            success = true;
        } catch (Throwable t) {
            this.logger.error("Failed to initialize a channel. Closing: {}", context.channel(), t);
        } finally {
            if (!success) {
                context.close();
            }
        }
    }

    @Override
    public
    void channelActive(ChannelHandlerContext context) throws Exception {
        this.logger.error("ChannelActive NOT IMPLEMENTED!");
    }

    @Override
    public
    void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        this.logger.error("MessageReceived NOT IMPLEMENTED!");
    }

    @Override
    public
    void channelReadComplete(ChannelHandlerContext context) throws Exception {
        context.flush();
    }

    @Override
    public abstract
    void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception;

    public
    MetaChannel shutdown(RegistrationWrapper<C> registrationWrapper, Channel channel) {
        this.logger.error("SHUTDOWN HANDLER REACHED! SOMETHING MESSED UP! TRYING TO ABORT");

        // shutdown. Something messed up. Only reach this is something messed up.
        // properly shutdown the TCP/UDP channels.
        if (channel.isOpen()) {
            channel.close();
        }

        // also, once we notify, we unregister this.
        if (registrationWrapper != null) {
            try {
                IntMap<MetaChannel> channelMap = registrationWrapper.getAndLockChannelMap();
                Entries<MetaChannel> entries = channelMap.entries();
                while (entries.hasNext()) {
                    MetaChannel metaChannel = entries.next().value;
                    if (metaChannel.localChannel == channel || metaChannel.tcpChannel == channel || metaChannel.udpChannel == channel) {
                        entries.remove();
                        metaChannel.close();
                        return metaChannel;
                    }
                }

            } finally {
                registrationWrapper.releaseChannelMap();
                registrationWrapper.abortRegistrationIfClient();
            }
        }

        return null;
    }
}

