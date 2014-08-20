package dorkbox.network.pipeline;

import dorkbox.network.connection.Connection;
import dorkbox.network.util.NetException;
import dorkbox.network.util.SerializationManager;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;

// on client this is MessageToMessage *because of the UdpDecoder in the pipeline!)

public class KryoDecoderCrypto extends KryoDecoder {

    public KryoDecoderCrypto(SerializationManager kryoWrapper) {
        super(kryoWrapper);
    }

    @Override
    protected Object readObject(SerializationManager kryoWrapper, ChannelHandlerContext ctx, ByteBuf in, int length) {
        ChannelHandler last = ctx.pipeline().last();
        if (last instanceof Connection) {
            return kryoWrapper.readWithCryptoTcp((Connection) last, in, length);
        } else {
            // SHOULD NEVER HAPPEN!
            throw new NetException("Tried to use kryo to READ an object with NO network connection!");
        }
    }
}
