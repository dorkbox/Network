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

import dorkbox.network.util.CryptoSerializationManager;
import dorkbox.util.exceptions.NetException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

@Sharable
public
class KryoDecoderUdp extends MessageToMessageDecoder<DatagramPacket> {

    private final CryptoSerializationManager kryoWrapper;

    public
    KryoDecoderUdp(CryptoSerializationManager kryoWrapper) {
        this.kryoWrapper = kryoWrapper;
    }

    @Override
    protected
    void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        if (msg != null) {
            ByteBuf data = msg.content();

            if (data != null) {
                // there is a REMOTE possibility that UDP traffic BEAT the TCP registration traffic, which means that THIS packet
                // COULD be encrypted!

                if (kryoWrapper.isEncrypted(data)) {
                    throw new NetException("Encrypted UDP packet received before registration complete. WHOOPS!");
                }

                // no connection here because we haven't created one yet. When we do, we replace this handler with a new one.
                Object read = kryoWrapper.read(data, data.writerIndex());
                out.add(read);
            }
        }
    }
}
