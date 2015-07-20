package dorkbox.network.pipeline.udp;

import dorkbox.network.connection.Connection;
import dorkbox.network.util.CryptoSerializationManager;
import dorkbox.network.util.exceptions.NetException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;

@Sharable
public
class KryoEncoderUdpCrypto extends KryoEncoderUdp {

    public
    KryoEncoderUdpCrypto(CryptoSerializationManager kryoWrapper) {
        super(kryoWrapper);
    }

    @Override
    protected
    void writeObject(CryptoSerializationManager kryoWrapper, ChannelHandlerContext ctx, Object msg, ByteBuf buffer) {
        ChannelHandler last = ctx.pipeline()
                                 .last();

        if (last instanceof Connection) {
            kryoWrapper.writeWithCryptoUdp((Connection) last, buffer, msg);
        }
        else {
            // SHOULD NEVER HAPPEN!
            throw new NetException("Tried to use kryo to WRITE an object with NO network connection!");
        }
    }
}
