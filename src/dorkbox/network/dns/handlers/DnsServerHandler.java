package dorkbox.network.dns.handlers;

import org.handwerkszeug.dns.server.DNSMessageDecoder;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;

/**
 *
 */
public
class DnsServerHandler extends ChannelInboundHandlerAdapter {

    protected DNSMessageDecoder decoder = new DNSMessageDecoder();

    public
    DnsServerHandler(final String threadName) {
    }

    @Override
    public final
    void channelRegistered(final ChannelHandlerContext context) throws Exception {
        boolean success = false;
        try {
            initChannel(context.channel());
            context.fireChannelRegistered();
            success = true;
        } catch (Throwable t) {
            // this.logger.error("Failed to initialize a channel. Closing: {}", context.channel(), t);
        } finally {
            if (!success) {
                context.close();
            }
        }
    }

    /**
     * STEP 1: Channel is first created
     */
    protected
    void initChannel(final Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();

        ///////////////////////
        // DECODE (or upstream)
        ///////////////////////
        pipeline.addLast("decoder", this.decoder);

        // ENCODE (or downstream)
        /////////////////////////
        pipeline.addLast("fowarder", new ForwardingHandler());
        // pipeline.addLast("fowarder", new ForwardingHandler(this.config, this.clientChannelFactory));
    }
}
