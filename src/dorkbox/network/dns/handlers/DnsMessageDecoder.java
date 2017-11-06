package dorkbox.network.dns.handlers;

import java.util.List;

import org.handwerkszeug.dns.DNSMessage;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;


public
class DnsMessageDecoder extends MessageToMessageDecoder<DatagramPacket> {
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

    @Override
    protected
    void decode(ChannelHandlerContext ctx, DatagramPacket packet, List<Object> out) throws Exception {
        System.err.println("READING MESSAGE");
        final ByteBuf buf = packet.content();

        boolean success = false;
        try {
            DNSMessage dnsMessage = new DNSMessage(buf);
            out.add(dnsMessage);
            success = true;
        } finally {
            if (!success) {
                buf.release();
            }
        }
    }
}
