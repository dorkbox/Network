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
package dorkbox.network.pipeline.udp;

import java.io.IOException;
import java.util.List;

import org.slf4j.LoggerFactory;

import dorkbox.network.connection.Connection_;
import dorkbox.network.serialization.NetworkSerializationManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

@Sharable
public
class KryoDecoderUdpCrypto extends MessageToMessageDecoder<DatagramPacket> {

    private final NetworkSerializationManager serializationManager;

    public
    KryoDecoderUdpCrypto(NetworkSerializationManager serializationManager) {
        this.serializationManager = serializationManager;
    }

    /**
     * Invoked when a {@link Channel} has been idle for a while.
     */
    @Override
    public
    void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
        //      if (e.getState() == IdleState.READER_IDLE) {
        //      e.getChannel().close();
        //  } else if (e.getState() == IdleState.WRITER_IDLE) {
        //      e.getChannel().write(new Object());
        //  } else
        if (event instanceof IdleStateEvent) {
            if (((IdleStateEvent) event).state() == IdleState.ALL_IDLE) {
                // will auto-flush if necessary
                // TODO: if we have been idle TOO LONG, then we close this channel!
                // if we are idle for a much smaller amount of time, then we pass the idle message up to the connection?

                // this.sessionManager.onIdle(this);
            }
        }

        super.userEventTriggered(context, event);
    }

    @Override
    public
    void decode(ChannelHandlerContext context, DatagramPacket in, List<Object> out) throws Exception {
        try {
            Connection_ last = (Connection_) context.pipeline()
                                                    .last();
            ByteBuf data = in.content();
            Object object = serializationManager.readWithCrypto(last, data, data.readableBytes());
            out.add(object);
        } catch (IOException e) {
            String message = "Unable to deserialize object";
            LoggerFactory.getLogger(this.getClass())
                         .error(message, e);
            throw new IOException(message, e);
        }
    }
}
