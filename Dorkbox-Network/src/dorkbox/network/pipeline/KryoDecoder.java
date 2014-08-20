package dorkbox.network.pipeline;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

import dorkbox.network.util.SerializationManager;
import dorkbox.util.bytes.OptimizeUtilsByteBuf;

public class KryoDecoder extends ByteToMessageDecoder {
    private final OptimizeUtilsByteBuf optimize;
    private final SerializationManager kryoWrapper;

    public KryoDecoder(SerializationManager kryoWrapper) {
        super();
        this.kryoWrapper = kryoWrapper;
        optimize = OptimizeUtilsByteBuf.get();
    }

    protected Object readObject(SerializationManager kryoWrapper, ChannelHandlerContext ctx, ByteBuf in, int length) {
        // no connection here because we haven't created one yet. When we do, we replace this handler with a new one.
        return kryoWrapper.read(in, length);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        OptimizeUtilsByteBuf optimize = this.optimize;

        // Make sure if the length field was received,
        // and read the length of the next object from the socket.
        int lengthLength = optimize.canReadInt(in);
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
        int length = optimize.readInt(in, true);
        readableBytes = in.readableBytes(); // have to adjust readable bytes, since we just read an int off the buffer.


        if (length == 0) {
            ctx.fireExceptionCaught(new IllegalStateException("Kryo DecoderTCP had a read length of 0"));
            return;
        }


        // we can't test against a single "max size", since objects can back-up on the buffer.
        // we must ABSOLUTELY follow a "max size" rule when encoding objects, however.

        // Make sure if there's enough bytes in the buffer.
        if (length > readableBytes) {
            // The whole bytes were not received yet - return null.
            // This method will be invoked again when more packets are
            // received and appended to the buffer.

            // Reset to the marked position to read the length field again
            // next time.
            in.resetReaderIndex();

            // wait for the rest of the object to come in.
            return;
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
                objectCount++;
                if (optimize.canReadInt(in) > 0) {
                    length = optimize.readInt(in, true);

                    if (length <= 0) {
                        // throw new IllegalStateException("Kryo DecoderTCP had a read length of 0");
                        objectCount--;
                        break;
                    }

                    endOfObjectPosition = in.readerIndex() + length;

                    // position the reader to look for the NEXT object
                    if (endOfObjectPosition <= writerIndex) {
                        in.readerIndex(endOfObjectPosition);
                        readableBytes = in.readableBytes();
                    } else {
                        objectCount--;
                        break;
                    }
                } else {
                    objectCount--;
                    break;
                }
            }
            // readerIndex is currently at the MAX place it can read data off the buffer.
            // reset it to the spot BEFORE we started reading data from the buffer.
            in.resetReaderIndex();


            // System.err.println("Found " + objectCount + " objects in this buffer.");

            // NOW add each one of the NEW objects to the array!

            for (int i=0;i<objectCount;i++) {
                length = optimize.readInt(in, true); // object LENGTH

                // however many we need to
                out.add(readObject(kryoWrapper, ctx, in, length));
            }
            // the buffer reader index will be at the correct location, since the read object method advances it.
        } else {
            // exactly one!
            out.add(readObject(kryoWrapper, ctx, in, length));
        }
    }
}
