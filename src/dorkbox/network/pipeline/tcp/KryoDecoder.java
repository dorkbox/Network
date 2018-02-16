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
import java.util.List;

import dorkbox.network.serialization.CryptoSerializationManager;
import dorkbox.util.bytes.OptimizeUtilsByteBuf;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

public
class KryoDecoder extends ByteToMessageDecoder {
    private final CryptoSerializationManager serializationManager;

    public
    KryoDecoder(CryptoSerializationManager serializationManager) {
        super();
        this.serializationManager = serializationManager;
    }

    @SuppressWarnings("unused")
    protected
    Object readObject(CryptoSerializationManager serializationManager, ChannelHandlerContext context, ByteBuf in, int length) throws IOException {
        // no connection here because we haven't created one yet. When we do, we replace this handler with a new one.
        return serializationManager.read(in, length);
    }

    @Override
    protected
    void decode(ChannelHandlerContext context, ByteBuf in, List<Object> out) throws Exception {

        // Make sure if the length field was received,
        // and read the length of the next object from the socket.
        int lengthLength = OptimizeUtilsByteBuf.canReadInt(in);
        int readableBytes = in.readableBytes();  // full length of available bytes.

        if (lengthLength == 0 || readableBytes < 2 || readableBytes < lengthLength) {
            // The length field was not fully received - do nothing (wait for more...)
            // This method will be invoked again when more packets are
            // received and appended to the buffer.
            return;
        }

        // The length field is in the buffer.

        // save the writerIndex for local access
        int writerIndex = in.writerIndex();

        // Mark the current buffer position before reading the length fields
        // because the whole frame might not be in the buffer yet.
        // We will reset the buffer position to the marked position if
        // there's not enough bytes in the buffer.
        in.markReaderIndex();

        // Read the length field.
        int length = OptimizeUtilsByteBuf.readInt(in, true);
        readableBytes = in.readableBytes(); // have to adjust readable bytes, since we just read an int off the buffer.


        if (length == 0) {
            context.fireExceptionCaught(new IllegalStateException("KryoDecoder had a read length of 0"));
            return;
        }


        // we can't test against a single "max size", since objects can back-up on the buffer.
        // we must ABSOLUTELY follow a "max size" rule when encoding objects, however.

        final CryptoSerializationManager serializationManager = this.serializationManager;

        // Make sure if there's enough bytes in the buffer.
        if (length > readableBytes) {
            // The whole bytes were not received yet - return null.
            // This method will be invoked again when more packets are
            // received and appended to the buffer.

            // Reset to the marked position to read the length field again
            // next time.
            in.resetReaderIndex();

            // wait for the rest of the object to come in.
            // System.err.println(Thread.currentThread().getName() + " waiting for more of the object to arrive");
        }

        // how many objects are on this buffer?
        else if (readableBytes > length) {
            // more than one!
            // read the object off of the buffer. (save parts of the buffer so if it is too big, we can go back to it, and use it later on...)

            // we know we have at least one object
            int objectCount = 1;
            int endOfObjectPosition = in.readerIndex() + length;

            // set us up for the next object.
            in.readerIndex(endOfObjectPosition);

            // how many more objects?? The first time, it can be off, because we already KNOW it's > 0.
            //  (That's how we got here to begin with)
            while (readableBytes > 0) {
                if (OptimizeUtilsByteBuf.canReadInt(in) > 0) {
                    length = OptimizeUtilsByteBuf.readInt(in, true);

                    if (length <= 0) {
                        // throw new IllegalStateException("Kryo DecoderTCP had a read length of 0");
                        break;
                    }

                    endOfObjectPosition = in.readerIndex() + length;

                    // position the reader to look for the NEXT object
                    if (endOfObjectPosition <= writerIndex) {
                        in.readerIndex(endOfObjectPosition);
                        readableBytes = in.readableBytes();
                        objectCount++;
                    }
                    else {
                        break;
                    }
                }
                else {
                    break;
                }
            }
            // readerIndex is currently at the MAX place it can read data off the buffer.
            // reset it to the spot BEFORE we started reading data from the buffer.
            in.resetReaderIndex();


            // System.err.println("Found " + objectCount + " objects in this buffer.");

            // NOW add each one of the NEW objects to the array!

            for (int i = 0; i < objectCount; i++) {
                length = OptimizeUtilsByteBuf.readInt(in, true); // object LENGTH

                // however many we need to
                Object object;
                try {
                    object = readObject(serializationManager, context, in, length);
                    out.add(object);
                } catch (Exception ex) {
                    context.fireExceptionCaught(new IOException("Unable to deserialize object!", ex));
                }
            }
            // the buffer reader index will be at the correct location, since the read object method advances it.
        }
        else {
            // exactly one!
            Object object;
            try {
                object = readObject(serializationManager, context, in, length);
                out.add(object);
            } catch (Exception ex) {
                context.fireExceptionCaught(new IOException("Unable to deserialize object!", ex));
            }
        }
    }
}
