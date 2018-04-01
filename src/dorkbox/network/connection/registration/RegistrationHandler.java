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

import dorkbox.network.connection.RegistrationWrapper;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;

@Sharable
public abstract
class RegistrationHandler extends ChannelInboundHandlerAdapter {
    protected static final String CONNECTION_HANDLER = "connection";

    protected final RegistrationWrapper registrationWrapper;
    protected final org.slf4j.Logger logger;
    protected final String name;
    protected final EventLoopGroup workerEventLoop;

    public
    RegistrationHandler(final String name, RegistrationWrapper registrationWrapper, final EventLoopGroup workerEventLoop) {
        this.name = name;
        this.workerEventLoop = workerEventLoop;
        this.logger = org.slf4j.LoggerFactory.getLogger(this.name);
        this.registrationWrapper = registrationWrapper;
    }

    @SuppressWarnings("unused")
    protected
    void initChannel(final Channel channel) {
    }

    @Override
    public final
    void channelRegistered(final ChannelHandlerContext context) throws Exception {
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
    void channelActive(final ChannelHandlerContext context) throws Exception {
        this.logger.error("ChannelActive NOT IMPLEMENTED!");
    }

    @Override
    public
    void channelRead(final ChannelHandlerContext context, Object message) throws Exception {
        this.logger.error("MessageReceived NOT IMPLEMENTED!");
    }

    @Override
    public
    void channelReadComplete(final ChannelHandlerContext context) throws Exception {
    }

    @Override
    public abstract
    void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) throws Exception;

    /**
     * shutdown. Something messed up or was incorrect
     */
    protected final
    void shutdown(final Channel channel, final int sessionId) {
        // properly shutdown the TCP/UDP channels.
        if (sessionId == 0 && channel.isOpen()) {
            channel.close();
        }

        // also, once we notify, we unregister this.
        if (registrationWrapper != null) {
            registrationWrapper.closeSession(sessionId);
        }
    }
}

