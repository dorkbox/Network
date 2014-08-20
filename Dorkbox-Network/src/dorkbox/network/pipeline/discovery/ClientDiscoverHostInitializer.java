package dorkbox.network.pipeline.discovery;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.nio.NioDatagramChannel;

/**
 * Creates a newly configured {@link ChannelPipeline} for a new channel.
 */
public class ClientDiscoverHostInitializer extends ChannelInitializer<NioDatagramChannel> {
    private ClientDiscoverHostHandler clientDiscoverHostHandler;

    public ClientDiscoverHostInitializer() {
        clientDiscoverHostHandler = new ClientDiscoverHostHandler();
    }

    @Override
    public void initChannel(NioDatagramChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();

        pipeline.addLast("discoverHostHandler", clientDiscoverHostHandler);
    }
}
