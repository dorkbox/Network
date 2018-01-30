package dorkbox.network.dns.serverHandlers;

import java.net.InetSocketAddress;
import java.util.List;

import org.slf4j.Logger;

import dorkbox.network.dns.DnsEnvelope;
import dorkbox.network.dns.exceptions.WireParseException;
import dorkbox.network.dns.records.Header;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;

class DnsMessageDecoder extends MessageToMessageDecoder<DatagramPacket> {
    private final Logger logger;

    DnsMessageDecoder(final Logger logger) {
        this.logger = logger;
    }

    @Override
    public
    void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) throws Exception {
        logger.error("DnsMessageDecoder#exceptionCaught", cause);
        super.exceptionCaught(context, cause);
    }

    @Override
    protected
    void decode(ChannelHandlerContext context, DatagramPacket packet, List<Object> out) throws Exception {
        final ByteBuf buf = packet.content();

        // Check that the response is long enough.
        if (buf.readableBytes() < Header.LENGTH) {
            throw new WireParseException("invalid DNS header - " + "too short");
        }

        boolean success = false;
        try {
            InetSocketAddress localAddress = packet.recipient();
            InetSocketAddress remoteAddress = packet.sender();

            DnsEnvelope dnsEnvelope = new DnsEnvelope(buf, localAddress, remoteAddress);
            dnsEnvelope.retain();


            // send down the pipeline
            out.add(dnsEnvelope);
            success = true;
        } finally {
            if (!success) {
                buf.release();
            }
        }
    }
}
