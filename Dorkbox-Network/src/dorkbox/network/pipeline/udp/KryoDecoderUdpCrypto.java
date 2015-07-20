package dorkbox.network.pipeline.udp;

import dorkbox.network.connection.Connection;
import dorkbox.network.util.CryptoSerializationManager;
import dorkbox.network.util.exceptions.NetException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

@Sharable
public
class KryoDecoderUdpCrypto extends MessageToMessageDecoder<DatagramPacket> {

    private final CryptoSerializationManager kryoWrapper;

    public
    KryoDecoderUdpCrypto(CryptoSerializationManager kryoWrapper) {
        this.kryoWrapper = kryoWrapper;
    }

    @Override
    public
    void decode(ChannelHandlerContext ctx, DatagramPacket in, List<Object> out) throws Exception {
        ChannelHandler last = ctx.pipeline()
                                 .last();

        if (last instanceof Connection) {
            ByteBuf data = in.content();
            Object object = kryoWrapper.readWithCryptoUdp((Connection) last, data, data.readableBytes());
            out.add(object);
        }
        else {
            // SHOULD NEVER HAPPEN!
            throw new NetException("Tried to use kryo to READ an object with NO network connection!");
        }
    }
}
