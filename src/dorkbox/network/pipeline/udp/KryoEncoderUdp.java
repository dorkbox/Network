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
import java.net.InetSocketAddress;
import java.util.List;

import org.slf4j.LoggerFactory;

import dorkbox.network.connection.EndPoint;
import dorkbox.network.serialization.CryptoSerializationManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageEncoder;

@Sharable
public
class KryoEncoderUdp extends MessageToMessageEncoder<Object> {

    private static final int maxSize = EndPoint.udpMaxSize;
    private final CryptoSerializationManager serializationManager;


    public
    KryoEncoderUdp(CryptoSerializationManager serializationManager) {
        super();
        this.serializationManager = serializationManager;
    }

    @Override
    protected
    void encode(ChannelHandlerContext context, Object message, List<Object> out) throws Exception {
        if (message != null) {
            try {
                ByteBuf outBuffer = context.alloc()
                                           .buffer(maxSize);

                // no size info, since this is UDP, it is not segmented
                writeObject(this.serializationManager, context, message, outBuffer);

                // have to check to see if we are too big for UDP!
                if (outBuffer.readableBytes() > maxSize) {
                    String msg =
                            "Object is TOO BIG FOR UDP! " + message.toString() + " (Max " + maxSize + ", was " + outBuffer.readableBytes() +
                            ")";
                    LoggerFactory.getLogger(this.getClass())
                                 .error(msg);
                    throw new IOException(msg);
                }

                DatagramPacket packet = new DatagramPacket(outBuffer,
                                                           (InetSocketAddress) context.channel()
                                                                                      .remoteAddress());
                out.add(packet);
            } catch (Exception e) {
                String msg = "Unable to serialize object of type: " + message.getClass()
                                                                             .getName();
                LoggerFactory.getLogger(this.getClass())
                             .error(msg, e);
                throw new IOException(msg, e);
            }
        }
    }

    // the crypto writer will override this
    void writeObject(CryptoSerializationManager serializationManager, ChannelHandlerContext context, Object msg, ByteBuf buffer)
            throws IOException {
        // no connection here because we haven't created one yet. When we do, we replace this handler with a new one.
        serializationManager.write(buffer, msg);
    }
}
