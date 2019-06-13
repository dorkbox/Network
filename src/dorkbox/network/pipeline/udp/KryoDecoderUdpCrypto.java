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

import dorkbox.network.connection.Connection_;
import dorkbox.network.serialization.NetworkSerializationManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;

@Sharable
public
class KryoDecoderUdpCrypto extends KryoDecoderUdp {

    public
    KryoDecoderUdpCrypto(NetworkSerializationManager serializationManager) {
        super(serializationManager);
    }

    protected
    Object readObject(NetworkSerializationManager serializationManager, ChannelHandlerContext context, ByteBuf in, int length) throws Exception {
        Connection_ connection = (Connection_) context.pipeline().last();
        return serializationManager.readWithCrypto(connection, in, length);
    }
}
