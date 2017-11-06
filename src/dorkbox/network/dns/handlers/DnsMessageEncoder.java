package dorkbox.network.dns.handlers;

import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.records.DnsMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * An encoder which serializes a Java object into a {@link ByteBuf}.
 * <p>
 * Please note that the serialized form this encoder produces is not
 * compatible with the standard {@link ObjectInputStream}.  Please use
 * {@link ObjectDecoder} or {@link ObjectDecoderInputStream} to ensure the
 * interoperability with this encoder.
 */


@ChannelHandler.Sharable
public
class DnsMessageEncoder extends MessageToByteEncoder<DnsMessage> {


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
    void encode(final ChannelHandlerContext ctx, final DnsMessage msg, final ByteBuf out) throws Exception {
        System.err.println("WRITING MESSAGE");
        final DnsOutput outd = new DnsOutput(out);
        msg.toWire(outd);
    }

    @Override
    public
    void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception {
        // Channel channel = context.channel();

        System.err.println("POW! ");
        cause.printStackTrace();
        // this.logger.error("Unexpected exception while trying to send/receive data on Client remote (network) channel.  ({})" +
        //                   System.getProperty("line.separator"), channel.remoteAddress(), cause);
        // if (channel.isOpen()) {
        //     channel.close();
        // }
    }
}
