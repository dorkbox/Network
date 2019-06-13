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

import dorkbox.network.serialization.NetworkSerializationManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.AddressedEnvelope;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

@Sharable
public
class KryoDecoderUdp extends MessageToMessageDecoder<Object> {
    private final NetworkSerializationManager serializationManager;

    public
    KryoDecoderUdp(NetworkSerializationManager serializationManager) {
        this.serializationManager = serializationManager;
    }

    protected
    Object readObject(NetworkSerializationManager serializationManager, ChannelHandlerContext context, ByteBuf in, int length) throws Exception {
        // no connection here because we haven't created one yet. When we do, we replace this handler with a new one.
        return serializationManager.read(in, length);
    }

    @Override
    public
    boolean acceptInboundMessage(final Object msg) throws Exception {
        return msg instanceof AddressedEnvelope;
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
                // if we are idle for a much smaller amount of time, then we pass the idle message up to the connection

                // this.sessionManager.onIdle(this);
            }
        }

        super.userEventTriggered(context, event);
    }

    @Override
    protected
    void decode(ChannelHandlerContext context, Object message, List<Object> out) throws Exception {
        ByteBuf data = (ByteBuf) ((AddressedEnvelope) message).content();

        try {
            out.add(readObject(serializationManager, context, data, data.writerIndex()));
        } catch (IOException e) {
            context.fireExceptionCaught(new IOException("Unable to deserialize object!", e));
        }
    }
}
