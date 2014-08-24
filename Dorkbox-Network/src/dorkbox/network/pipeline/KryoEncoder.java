package dorkbox.network.pipeline;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import com.esotericsoftware.kryo.KryoException;

import dorkbox.network.util.SerializationManager;
import dorkbox.network.util.exceptions.NetException;
import dorkbox.util.bytes.OptimizeUtilsByteBuf;

@Sharable
public class KryoEncoder extends MessageToByteEncoder<Object> {
    private static final int reservedLengthIndex = 4;
    private final SerializationManager kryoWrapper;
    private final OptimizeUtilsByteBuf optimize;


    public KryoEncoder(SerializationManager kryoWrapper) {
        super();
        this.kryoWrapper = kryoWrapper;
        optimize = OptimizeUtilsByteBuf.get();
    }

    // the crypto writer will override this
    protected void writeObject(SerializationManager kryoWrapper, ChannelHandlerContext ctx, Object msg, ByteBuf buffer) {
        // no connection here because we haven't created one yet. When we do, we replace this handler with a new one.
        kryoWrapper.write(buffer, msg);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) throws Exception {
        // we don't necessarily start at 0!!
        int startIndex = out.writerIndex();

        if (msg != null) {

            // Write data. START at index = 4. This is to make room for the integer placed by the frameEncoder for TCP.
            // DOES NOT SUPPORT NEGATIVE NUMBERS!
            out.writeInt(0);  // put an int in, which is the same size as reservedLengthIndex

            try {
                writeObject(kryoWrapper, ctx, msg, out);

                // now set the frame (if it's TCP)!
                int length = out.readableBytes() - startIndex - reservedLengthIndex; // (reservedLengthLength) 4 is the reserved space for the integer.

                // specify the header.
                OptimizeUtilsByteBuf optimize = this.optimize;
                int lengthOfTheLength = optimize.intLength(length, true);

                // 4 was the position specified by the kryoEncoder. It was to make room for the integer. DOES NOT SUPPORT NEGATIVE NUMBERS!
                int newIndex = startIndex+reservedLengthIndex-lengthOfTheLength;
                int oldIndex = out.writerIndex();

                out.writerIndex(newIndex);

                // do the optimized length thing!
                optimize.writeInt(out, length, true);
                out.setIndex(newIndex, oldIndex);
            } catch (KryoException ex) {
                ctx.fireExceptionCaught(new NetException("Unable to serialize object of type: " + msg.getClass().getName(), ex));
            }
        }
    }
}
