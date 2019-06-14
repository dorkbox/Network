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

import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.RegistrationHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;

public abstract
class RegistrationLocalHandler<T extends RegistrationWrapper> extends RegistrationHandler<T> {
    public static final AttributeKey<MetaChannel> META_CHANNEL = AttributeKey.valueOf(RegistrationLocalHandler.class, "MetaChannel.local");

    RegistrationLocalHandler(String name, T registrationWrapper) {
        super(name, registrationWrapper, null);
    }

    @Override
    public
    void channelActive(final ChannelHandlerContext context) throws Exception {
        // to suppress warnings in the super class
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

    // this SHOULDN'T ever happen, but we might shutdown in the middle of registration
    @Override
    public final
    void channelInactive(ChannelHandlerContext context) throws Exception {
        Channel channel = context.channel();

        logger.info("Closed LOCAL connection: {}", channel.remoteAddress());

        super.channelInactive(context);
    }
}

