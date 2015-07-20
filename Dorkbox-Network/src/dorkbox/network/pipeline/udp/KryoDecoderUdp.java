package dorkbox.network.pipeline.udp;

import dorkbox.network.util.CryptoSerializationManager;
import dorkbox.network.util.exceptions.NetException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

@Sharable
public
class KryoDecoderUdp extends MessageToMessageDecoder<DatagramPacket> {

    private final CryptoSerializationManager kryoWrapper;

    public
    KryoDecoderUdp(CryptoSerializationManager kryoWrapper) {
        this.kryoWrapper = kryoWrapper;
    }

    @Override
    protected
    void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
        if (msg != null) {
            ByteBuf data = msg.content();

            if (data != null) {
                // there is a REMOTE possibility that UDP traffic BEAT the TCP registration traffic, which means that THIS packet
                // COULD be encrypted!

                if (kryoWrapper.isEncrypted(data)) {
                    throw new NetException("Encrypted UDP packet received before registration complete. WHOOPS!");
                }

                // no connection here because we haven't created one yet. When we do, we replace this handler with a new one.
                Object read = kryoWrapper.read(data, data.writerIndex());
                out.add(read);
            }
        }
    }
}
