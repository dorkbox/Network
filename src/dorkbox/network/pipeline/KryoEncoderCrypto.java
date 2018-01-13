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

import java.io.IOException;

import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.serialization.CryptoSerializationManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;

@Sharable
public
class KryoEncoderCrypto extends KryoEncoder {

    public
    KryoEncoderCrypto(final CryptoSerializationManager serializationManager) {
        super(serializationManager);
    }

    @Override
    protected
    void writeObject(final CryptoSerializationManager serializationManager,
                     final ChannelHandlerContext context,
                     final Object msg,
                     final ByteBuf buffer) throws IOException {

        ConnectionImpl connection = (ConnectionImpl) context.pipeline()
                                                            .last();
        serializationManager.writeWithCrypto(connection, buffer, msg);
    }
}
