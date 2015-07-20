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

import com.esotericsoftware.kryo.KryoException;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.util.CryptoSerializationManager;
import dorkbox.util.exceptions.NetException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.net.InetSocketAddress;
import java.util.List;

@Sharable
// UDP uses messages --- NOT bytebuf!
// ONLY USED BY THE CLIENT (the server has it's own handler!)
public
class KryoEncoderUdp extends MessageToMessageEncoder<Object> {

    private static final int maxSize = EndPoint.udpMaxSize;
    private final CryptoSerializationManager kryoWrapper;


    public
    KryoEncoderUdp(CryptoSerializationManager kryoWrapper) {
        super();
        this.kryoWrapper = kryoWrapper;
    }

    // the crypto writer will override this
    @SuppressWarnings("unused")
    protected
    void writeObject(CryptoSerializationManager kryoWrapper, ChannelHandlerContext context, Object msg, ByteBuf buffer) {
        // no connection here because we haven't created one yet. When we do, we replace this handler with a new one.
        kryoWrapper.write(buffer, msg);
    }

    @Override
    protected
    void encode(ChannelHandlerContext ctx, Object msg, List<Object> out) throws Exception {
        if (msg != null) {
            try {
                ByteBuf outBuffer = Unpooled.buffer(maxSize);

                // no size info, since this is UDP, it is not segmented
                writeObject(this.kryoWrapper, ctx, msg, outBuffer);


                // have to check to see if we are too big for UDP!
                if (outBuffer.readableBytes() > EndPoint.udpMaxSize) {
                    System.err.println("Object larger than MAX udp size!  " + EndPoint.udpMaxSize + "/" + outBuffer.readableBytes());
                    throw new NetException("Object is TOO BIG FOR UDP! " + msg.toString() + " (" + EndPoint.udpMaxSize + "/" +
                                           outBuffer.readableBytes() + ")");
                }

                DatagramPacket packet = new DatagramPacket(outBuffer,
                                                           (InetSocketAddress) ctx.channel()
                                                                                  .remoteAddress());
                out.add(packet);
            } catch (KryoException ex) {
                throw new NetException("Unable to serialize object of type: " + msg.getClass()
                                                                                   .getName(), ex);
            }
        }
    }
}
