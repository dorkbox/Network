package dorkbox.network.dns.serverHandlers;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.concurrent.Promise;

public class SuccessHandler implements ChannelFutureListener {
    private Promise<Object> promise;

    public SuccessHandler(Promise<Object> promise) {
        this.promise = promise;
    }

    @Override
    public void operationComplete(ChannelFuture future) throws Exception {
        System.err.println("SUCCESS COMPLETE");
        if (future.isSuccess()) {
            future.channel().pipeline().addLast(new DnsResponseHandler(this.promise));
        } else {
            if (!future.isDone()) {
                this.promise.setFailure(future.cause());
            }
        }
    }
}
