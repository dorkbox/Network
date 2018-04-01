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
package dorkbox.network.pipeline.discovery;

import java.net.InetAddress;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.AttributeKey;

public
class ClientDiscoverHostHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    // This uses CHANNEL LOCAL DATA to save the data.

    public static final AttributeKey<BroadcastResponse> STATE = AttributeKey.valueOf(ClientDiscoverHostHandler.class, "Discover.state");

    ClientDiscoverHostHandler() {
    }

    @Override
    protected
    void channelRead0(final ChannelHandlerContext context, final DatagramPacket message) throws Exception {
        ByteBuf byteBuf = message.content();

        InetAddress remoteAddress = message.sender()
                                           .getAddress();

        if (BroadcastServer.isDiscoveryResponse(byteBuf, remoteAddress, context.channel())) {
            // the state/ports/etc are set inside the isDiscoveryResponse() method...
            context.channel()
                   .close();
        }
    }

    @Override
    public
    void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception {
        context.channel()
               .close();
    }
}

