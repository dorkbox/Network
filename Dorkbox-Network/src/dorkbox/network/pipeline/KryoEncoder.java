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

import com.esotericsoftware.kryo.KryoException;
import dorkbox.network.util.CryptoSerializationManager;
import dorkbox.util.bytes.OptimizeUtilsByteBuf;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.io.IOException;

@Sharable
public
class KryoEncoder extends MessageToByteEncoder<Object> {
    private static final int reservedLengthIndex = 4;
    private final CryptoSerializationManager serializationManager;
    private final OptimizeUtilsByteBuf optimize;


    public
    KryoEncoder(final CryptoSerializationManager serializationManager) {
        super();
        this.serializationManager = serializationManager;
        this.optimize = OptimizeUtilsByteBuf.get();
    }

    // the crypto writer will override this
    @SuppressWarnings("unused")
    protected
    void writeObject(final CryptoSerializationManager kryoWrapper,
                     final ChannelHandlerContext context,
                     final Object msg,
                     final ByteBuf buffer) {
        // no connection here because we haven't created one yet. When we do, we replace this handler with a new one.
        try {
            kryoWrapper.write(buffer, msg);
        } catch (IOException ex) {
            context.fireExceptionCaught(new IOException("Unable to serialize object of type: " + msg.getClass()
                                                                                                    .getName(), ex));
        }
    }

    @Override
    protected
    void encode(final ChannelHandlerContext context, final Object msg, final ByteBuf out) throws Exception {
        // we don't necessarily start at 0!!
        int startIndex = out.writerIndex();

        if (msg != null) {

            // Write data. START at index = 4. This is to make room for the integer placed by the frameEncoder for TCP.
            // DOES NOT SUPPORT NEGATIVE NUMBERS!
            out.writeInt(0);  // put an int in, which is the same size as reservedLengthIndex

            try {
                writeObject(this.serializationManager, context, msg, out);

                // now set the frame (if it's TCP)!
                int length = out.readableBytes() - startIndex -
                             reservedLengthIndex; // (reservedLengthLength) 4 is the reserved space for the integer.

                // specify the header.
                OptimizeUtilsByteBuf optimize = this.optimize;
                int lengthOfTheLength = optimize.intLength(length, true);

                // 4 was the position specified by the kryoEncoder. It was to make room for the integer. DOES NOT SUPPORT NEGATIVE NUMBERS!
                int newIndex = startIndex + reservedLengthIndex - lengthOfTheLength;
                int oldIndex = out.writerIndex();

                out.writerIndex(newIndex);

                // do the optimized length thing!
                optimize.writeInt(out, length, true);
                out.setIndex(newIndex, oldIndex);
            } catch (KryoException ex) {
                context.fireExceptionCaught(new IOException("Unable to serialize object of type: " + msg.getClass()
                                                                                                        .getName(), ex));
            }
        }
    }
}
