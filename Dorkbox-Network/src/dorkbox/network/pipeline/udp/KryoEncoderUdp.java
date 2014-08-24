package dorkbox.network.pipeline.udp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.net.InetSocketAddress;
import java.util.List;

import com.esotericsoftware.kryo.KryoException;

import dorkbox.network.connection.EndPoint;
import dorkbox.network.util.SerializationManager;
import dorkbox.network.util.exceptions.NetException;

@Sharable
// UDP uses messages --- NOT bytebuf!
// ONLY USED BY THE CLIENT (the server has it's own handler!)
public class KryoEncoderUdp extends MessageToMessageEncoder<Object> {

    private final static int maxSize = EndPoint.udpMaxSize;
    private SerializationManager kryoWrapper;


    public KryoEncoderUdp(SerializationManager kryoWrapper) {
        super();
        this.kryoWrapper = kryoWrapper;
    }

    // the crypto writer will override this
    protected void writeObject(SerializationManager kryoWrapper, ChannelHandlerContext ctx, Object msg, ByteBuf buffer) {
        // no connection here because we haven't created one yet. When we do, we replace this handler with a new one.
        kryoWrapper.write(buffer, msg);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, List<Object> out) throws Exception {
        if (msg != null) {
            try {
                ByteBuf outBuffer = Unpooled.buffer(maxSize);

                // no size info, since this is UDP, it is not segmented
                writeObject(kryoWrapper, ctx, msg, outBuffer);


                // have to check to see if we are too big for UDP!
                if (outBuffer.readableBytes() > EndPoint.udpMaxSize) {
                    System.err.println("Object larger than MAX udp size!  " + EndPoint.udpMaxSize + "/" + outBuffer.readableBytes());
                    throw new NetException("Object is TOO BIG FOR UDP! " + msg.toString() + " (" + EndPoint.udpMaxSize + "/" + outBuffer.readableBytes() + ")");
                }

                DatagramPacket packet = new DatagramPacket(outBuffer, (InetSocketAddress) ctx.channel().remoteAddress());
                out.add(packet);
            } catch (KryoException ex) {
                throw new NetException("Unable to serialize object of type: " + msg.getClass().getName(), ex);
            }
        }
    }
}
