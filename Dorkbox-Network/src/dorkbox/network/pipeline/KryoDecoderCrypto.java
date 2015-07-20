package dorkbox.network.pipeline;

import dorkbox.network.connection.Connection;
import dorkbox.network.util.CryptoSerializationManager;
import dorkbox.network.util.exceptions.NetException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;

// on client this is MessageToMessage *because of the UdpDecoder in the pipeline!)

public class KryoDecoderCrypto extends KryoDecoder {

    public KryoDecoderCrypto(CryptoSerializationManager kryoWrapper) {
        super(kryoWrapper);
    }

    @Override
    protected Object readObject(CryptoSerializationManager kryoWrapper, ChannelHandlerContext ctx, ByteBuf in, int length) {
        ChannelHandler last = ctx.pipeline().last();
        if (last instanceof Connection) {
            return kryoWrapper.readWithCryptoTcp((Connection) last, in, length);
        } else {
            // SHOULD NEVER HAPPEN!
            throw new NetException("Tried to use kryo to READ an object with NO network connection!");
        }
    }
}
