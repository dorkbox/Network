package dorkbox.network.dns.serverHandlers;


import org.slf4j.Logger;

import dorkbox.network.dns.Name;
import dorkbox.network.dns.records.ARecord;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;

/**
 *
 */
public
class DnsServerHandler extends ChannelInboundHandlerAdapter {
    protected final DnsMessageDecoder decoder;
    private final Logger logger;
    private DnsDecisionHandler decisionHandler;
    private DnsMessageEncoder encoder;

    public
    DnsServerHandler(final Logger logger) {
        this.logger = logger;

        decoder = new DnsMessageDecoder(logger);
        decisionHandler = new DnsDecisionHandler(logger);
        encoder = new DnsMessageEncoder(logger);
    }

    /**
     * Adds a domain name query result, so clients that request the domain name will get the ipAddress
     *
     * @param domainName the domain name to have results for
     * @param @param aRecords the A records (can be multiple) to return for the requested domain name
     */
    public
    void addARecord(final Name domainName, final ARecord[] aRecords) {
        decisionHandler.addARecord(domainName, aRecords);
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
            logger.error("Failed to initialize a channel. Closing: {}", context.channel(), t);
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
        pipeline.addLast("decoder", decoder);
        pipeline.addLast("dnsDecision", decisionHandler);

        // ENCODE (or downstream)
        /////////////////////////
        pipeline.addLast("encoder", encoder);
        // pipeline.addLast("fowarder", new ForwardingHandler(logger));
        // pipeline.addLast("fowarder", new ForwardingHandler(this.config, this.clientChannelFactory));
    }
}
