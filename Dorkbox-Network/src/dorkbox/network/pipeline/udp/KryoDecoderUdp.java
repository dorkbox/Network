package dorkbox.network.pipeline.udp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

import dorkbox.network.util.NetException;
import dorkbox.network.util.SerializationManager;

@Sharable
public class KryoDecoderUdp extends MessageToMessageDecoder<DatagramPacket> {

    private final SerializationManager kryoWrapper;

    public KryoDecoderUdp(SerializationManager kryoWrapper) {
        this.kryoWrapper = kryoWrapper;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        if (msg != null) {
            ByteBuf data = msg.content();

            if (data != null) {
                // there is a REMOTE possibility that UDP traffic BEAT the TCP registration traffic, which means that THIS packet
                // COULD be encrypted!

                if (kryoWrapper.isEncrypted(data)) {
                    throw new NetException("Encrypted UDP packet recieved before registration complete. WHOOPS!");
                }

                // no connection here because we haven't created one yet. When we do, we replace this handler with a new one.
                Object read = kryoWrapper.read(data, data.writerIndex());
                out.add(read);
            }
        }
    }
}
