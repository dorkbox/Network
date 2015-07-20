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

import dorkbox.network.connection.Connection;
import dorkbox.network.util.CryptoSerializationManager;
import dorkbox.util.exceptions.NetException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;

@Sharable
public
class KryoEncoderUdpCrypto extends KryoEncoderUdp {

    public
    KryoEncoderUdpCrypto(CryptoSerializationManager kryoWrapper) {
        super(kryoWrapper);
    }

    @Override
    protected
    void writeObject(CryptoSerializationManager kryoWrapper, ChannelHandlerContext ctx, Object msg, ByteBuf buffer) {
        ChannelHandler last = ctx.pipeline()
                                 .last();

        if (last instanceof Connection) {
            kryoWrapper.writeWithCryptoUdp((Connection) last, buffer, msg);
        }
        else {
            // SHOULD NEVER HAPPEN!
            throw new NetException("Tried to use kryo to WRITE an object with NO network connection!");
        }
    }
}
