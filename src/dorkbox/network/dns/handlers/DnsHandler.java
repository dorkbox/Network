package dorkbox.network.dns.handlers;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.DatagramChannel;

public
class DnsHandler extends ChannelInitializer<DatagramChannel> {
    @Override
    protected
    void initChannel(DatagramChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast(new DnsMessageEncoder());
        pipeline.addLast(new DnsMessageDecoder());
        // pipeline.addLast(new DNSMessageDecoder());
    }
}
