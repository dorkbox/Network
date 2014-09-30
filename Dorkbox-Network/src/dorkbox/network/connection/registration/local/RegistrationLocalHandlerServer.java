package dorkbox.network.connection.registration.local;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.util.ReferenceCountUtil;

import org.slf4j.Logger;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.util.collections.IntMap;

public class RegistrationLocalHandlerServer extends RegistrationLocalHandler {

    public RegistrationLocalHandlerServer(String name, RegistrationWrapper registrationWrapper) {
        super(name, registrationWrapper);
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        Channel channel = context.channel();
        ChannelPipeline pipeline = channel.pipeline();

        // have to remove the pipeline FIRST, since if we don't, and we expect to receive a message --- when we REMOVE "this" from the pipeline,
        // we will ALSO REMOVE all it's messages, which we want to receive!
        pipeline.remove(this);

        channel.writeAndFlush(message);

        ReferenceCountUtil.release(message);
        Logger logger2 = this.logger;
        if (logger2.isTraceEnabled()) {
            logger2.trace("Sent registration");
        }

        Connection connection = null;
        try {
            IntMap<MetaChannel> channelMap = this.registrationWrapper.getAndLockChannelMap();
            MetaChannel metaChannel = channelMap.remove(channel.hashCode());
            if (metaChannel != null) {
                connection = metaChannel.connection;
            }
        } finally {
            this.registrationWrapper.releaseChannelMap();
        }

        if (connection != null) {
            this.registrationWrapper.connectionConnected0(connection);
        }
    }

    /**
     * @return the direction that traffic is going to this handler (" <== " or " ==> ")
     */
    @Override
    protected String getConnectionDirection() {
        return " <== ";
    }
}

