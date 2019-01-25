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

import dorkbox.network.connection.Connection_;
import dorkbox.network.serialization.NetworkSerializationManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;

@Sharable
public
class KryoEncoderUdpCrypto extends KryoEncoderUdp {

    public
    KryoEncoderUdpCrypto(NetworkSerializationManager serializationManager) {
        super(serializationManager);
    }

    @Override
    void writeObject(NetworkSerializationManager serializationManager, ChannelHandlerContext ctx, Object msg, ByteBuf buffer) throws IOException {

        Connection_ last = (Connection_) ctx.pipeline()
                                            .last();
        serializationManager.writeWithCrypto(last, buffer, msg);
    }
}
