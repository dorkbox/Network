package dorkbox.network.dns.serverHandlers;


import org.slf4j.Logger;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;

/**
 *
 */
public
class DnsServerHandler extends ChannelInboundHandlerAdapter {
    private static final Logger LOG = org.slf4j.LoggerFactory.getLogger(DnsServerHandler.class);

    protected final DnsMessageDecoder decoder = new DnsMessageDecoder();

    public
    DnsServerHandler() {
    }

    @Override
    public final
    void channelRegistered(final ChannelHandlerContext context) {
        boolean success = false;
        try {
            initChannel(context.channel());
            context.fireChannelRegistered();
            success = true;
        } catch (Throwable t) {
            LOG.error("Failed to initialize a channel. Closing: {}", context.channel(), t);
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
