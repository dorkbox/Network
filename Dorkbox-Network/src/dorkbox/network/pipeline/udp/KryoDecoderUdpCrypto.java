package dorkbox.network.pipeline.udp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

import dorkbox.network.connection.Connection;
import dorkbox.network.util.NetException;
import dorkbox.network.util.SerializationManager;

@Sharable
public class KryoDecoderUdpCrypto extends MessageToMessageDecoder<DatagramPacket> {

    private final SerializationManager kryoWrapper;

    public KryoDecoderUdpCrypto(SerializationManager kryoWrapper) {
        this.kryoWrapper = kryoWrapper;
    }

    @Override
    public void decode(ChannelHandlerContext ctx, DatagramPacket in, List<Object> out) throws Exception {
        ChannelHandler last = ctx.pipeline().last();
        if (last instanceof Connection) {
            ByteBuf data = in.content();
            Object object = kryoWrapper.readWithCryptoUdp((Connection) last, data, data.readableBytes());
            out.add(object);
        } else {
            // SHOULD NEVER HAPPEN!
            throw new NetException("Tried to use kryo to READ an object with NO network connection!");
        }
    }
}
