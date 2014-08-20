package dorkbox.network.pipeline;

import dorkbox.network.connection.Connection;
import dorkbox.network.util.NetException;
import dorkbox.network.util.SerializationManager;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;

@Sharable
public class KryoEncoderCrypto extends KryoEncoder {

    public KryoEncoderCrypto(SerializationManager kryoWrapper) {
        super(kryoWrapper);
    }

    @Override
    protected void writeObject(SerializationManager kryoWrapper, ChannelHandlerContext ctx, Object msg, ByteBuf buffer) {
        ChannelHandler last = ctx.pipeline().last();
        if (last instanceof Connection) {
            kryoWrapper.writeWithCryptoTcp((Connection) last, buffer, msg);
        } else {
            // SHOULD NEVER HAPPEN!
            throw new NetException("Tried to use kryo to WRITE an object with NO network connection (or wrong connection type!)!");
        }
    }
}
