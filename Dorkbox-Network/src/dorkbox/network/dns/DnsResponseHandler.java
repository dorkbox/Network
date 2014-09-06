package dorkbox.network.dns;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.DnsResource;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.util.concurrent.Promise;

import java.util.ArrayList;
import java.util.List;

import dorkbox.network.dns.decoder.DnsException;

public class DnsResponseHandler extends SimpleChannelInboundHandler<DnsResponse> {
    private Promise<Object> promise;

    public DnsResponseHandler(Promise<Object> promise) {
        this.promise = promise;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DnsResponse msg) throws Exception {
        DnsResponseCode errorCode = msg.header().responseCode();

        if (errorCode == DnsResponseCode.NOERROR) {
            RecordDecoderFactory factory = RecordDecoderFactory.getFactory();

            List<DnsResource> resources = msg.answers();
            List<Object> records = new ArrayList<>(resources.size());
            for (DnsResource resource : resources) {
                Object record = factory.decode(msg, resource);
                records.add(record);
            }
            this.promise.setSuccess(records);
        } else {
            this.promise.setFailure(new DnsException(errorCode));
        }
        ctx.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        this.promise.setFailure(cause);
        ctx.close();
    }
}
