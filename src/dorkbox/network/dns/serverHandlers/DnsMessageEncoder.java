package dorkbox.network.dns.serverHandlers;

import java.io.IOException;

import org.slf4j.Logger;

import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.DnsServerResponse;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 *
 */
@ChannelHandler.Sharable
public
class DnsMessageEncoder extends MessageToByteEncoder<DnsServerResponse> {
    private final Logger logger;

    public
    DnsMessageEncoder(final Logger logger) {
        this.logger = logger;
    }


    // public
    // class ObjectEncoder extends MessageToByteEncoder<Serializable> {
    //     private static final byte[] LENGTH_PLACEHOLDER = new byte[4];

        // @Override
        // protected
        // void encode(ChannelHandlerContext ctx, Serializable msg, ByteBuf out) throws Exception {
        //     int startIdx = out.writerIndex();
        //
        //     ByteBufOutputStream bout = new ByteBufOutputStream(out);
        //     ObjectOutputStream oout = null;
        //     try {
        //         bout.write(LENGTH_PLACEHOLDER);
        //         oout = new CompactObjectOutputStream(bout);
        //         oout.writeObject(msg);
        //         oout.flush();
        //     } finally {
        //         if (oout != null) {
        //             oout.close();
        //         }
        //         else {
        //             bout.close();
        //         }
        //     }
        //
        //     int endIdx = out.writerIndex();
        //
        //     out.setInt(startIdx, endIdx - startIdx - 4);
        // }

    @Override
    protected
    void encode(final ChannelHandlerContext context, final DnsServerResponse message, final ByteBuf out) throws Exception {
        try {
            DnsOutput dnsOutput = new DnsOutput(out);
            out.retain();
            message.toWire(dnsOutput);

            DatagramPacket packet = new DatagramPacket(out, message.recipient(), message.sender());
            context.channel()
                   .writeAndFlush(packet);
        } catch (Exception e) {
            context.fireExceptionCaught(new IOException("Unable to write dns message: " + message, e));
        }
    }

    @Override
    public
    void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) throws Exception {
        logger.error("DnsMessageEncoder#exceptionCaught", cause);
        super.exceptionCaught(context, cause);
    }
}
