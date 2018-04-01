/*
 * Copyright 2018 dorkbox, llc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package dorkbox.network.pipeline.tcp;

import java.io.IOException;

import dorkbox.network.serialization.CryptoSerializationManager;
import dorkbox.util.bytes.OptimizeUtilsByteBuf;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

@Sharable
public
class KryoEncoder extends MessageToByteEncoder<Object> {
    // maximum size of length field. Un-optimized will always be 4, but optimized version can take from 1 - 4 (for 0-Integer.MAX_VALUE).
    private static final int reservedLengthIndex = 4;
    private final CryptoSerializationManager serializationManager;

    // When this is a UDP encode, there are ALREADY size limits placed on the buffer, so any extra checks are unnecessary
    public
    KryoEncoder(final CryptoSerializationManager serializationManager) {
        super(true); // just use direct buffers anyways. When using Heap buffers, they because chunked and the backing array is invalid.
        this.serializationManager = serializationManager;
    }

    // the crypto writer will override this
    @SuppressWarnings("unused")
    protected
    void writeObject(final CryptoSerializationManager kryoWrapper,
                     final ChannelHandlerContext context,
                     final Object msg,
                     final ByteBuf buffer) throws IOException {

        // no connection here because we haven't created one yet. When we do, we replace this handler with a new one.
        kryoWrapper.write(buffer, msg);
    }

    @Override
    protected
    void encode(final ChannelHandlerContext context, final Object msg, final ByteBuf out) throws Exception {
        // we don't necessarily start at 0!!
        // START at index = 4. This is to make room for the integer placed by the frameEncoder for TCP.
        int startIndex = out.writerIndex() + reservedLengthIndex;

        if (msg != null) {
            out.writerIndex(startIndex);

            try {
                writeObject(this.serializationManager, context, msg, out);
                int index = out.writerIndex();

                // now set the frame length
                // (reservedLengthLength) 4 is the reserved space for the integer.
                int length = index - startIndex;

                // specify the header.
                int lengthOfTheLength = OptimizeUtilsByteBuf.intLength(length, true);

                // make it so the location we write out our length is aligned to the end.
                int indexForLength = startIndex - lengthOfTheLength;
                out.writerIndex(indexForLength);

                // do the optimized length thing!
                OptimizeUtilsByteBuf.writeInt(out, length, true);

                // newIndex is actually where we want to start reading the data as well when written to the socket
                out.setIndex(indexForLength, index);
            } catch (Exception ex) {
                context.fireExceptionCaught(new IOException("Unable to serialize object of type: " + msg.getClass().getName(), ex));
            }
        }
    }
}
