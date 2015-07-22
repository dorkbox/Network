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
package dorkbox.network.pipeline;

import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.util.CryptoSerializationManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;

// on client this is MessageToMessage *because of the UdpDecoder in the pipeline!)


public
class KryoDecoderCrypto extends KryoDecoder {

    public
    KryoDecoderCrypto(CryptoSerializationManager serializationManager) {
        super(serializationManager);
    }

    @Override
    protected
    Object readObject(CryptoSerializationManager serializationManager, ChannelHandlerContext ctx, ByteBuf in, int length) {
        ChannelHandler last = ctx.pipeline()
                                 .last();
        return serializationManager.readWithCryptoTcp((ConnectionImpl) last, in, length);
    }
}
