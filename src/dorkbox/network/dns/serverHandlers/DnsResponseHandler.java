package dorkbox.network.dns.serverHandlers;

import dorkbox.network.dns.DnsResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.concurrent.Promise;

public class DnsResponseHandler extends SimpleChannelInboundHandler<DnsResponse> {
    private Promise<Object> promise;

    public DnsResponseHandler(Promise<Object> promise) {
        this.promise = promise;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DnsResponse msg) throws Exception {
        System.err.println("YOP! " + msg);
        // DnsResponseCode errorCode = msg.header().responseCode();
        //
        // if (errorCode == DnsResponseCode.NOERROR) {
        //     RecordDecoderFactory factory = RecordDecoderFactory.getFactory();
        //
        //     List<DnsResource> resources = msg.answers();
        //     List<Object> records = new ArrayList<>(resources.size());
        //     for (DnsResource resource : resources) {
        //         Object record = factory.decode(msg, resource);
        //         records.add(record);
        //     }
        //     this.promise.setSuccess(records);
        // } else {
        //     this.promise.setFailure(new DnsException(errorCode));
        // }
        // ctx.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        this.promise.setFailure(cause);
        ctx.close();
    }
}
