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

import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.serialization.CryptoSerializationManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;

@Sharable
public
class KryoDecoderUdpCrypto extends MessageToMessageDecoder<DatagramPacket> {

    private final CryptoSerializationManager serializationManager;

    public
    KryoDecoderUdpCrypto(CryptoSerializationManager serializationManager) {
        this.serializationManager = serializationManager;
    }

    @Override
    public
    void decode(ChannelHandlerContext ctx, DatagramPacket in, List<Object> out) throws Exception {
        ChannelHandler last = ctx.pipeline()
                                 .last();

        try {
            ByteBuf data = in.content();
            Object object = serializationManager.readWithCrypto((ConnectionImpl) last, data, data.readableBytes());
            out.add(object);
        } catch (IOException e) {
            String message = "Unable to deserialize object";
            LoggerFactory.getLogger(this.getClass()).error(message, e);
            throw new IOException(message, e);
        }
    }
}
