package dorkbox.network.connection.registration.local;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.util.collections.IntMap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;

public
class RegistrationLocalHandlerServer extends RegistrationLocalHandler {

    public
    RegistrationLocalHandlerServer(String name, RegistrationWrapper registrationWrapper) {
        super(name, registrationWrapper);
    }

    /**
     * STEP 1: Channel is first created
     */
//  Calls the super class to init the local channel
//  @Override
//  protected void initChannel(Channel channel) {
//  }

    /**
     * STEP 2: Channel is now active. Start the registration process (Client starts the process)
     */
    @Override
    public
    void channelActive(ChannelHandlerContext context) throws Exception {
        if (logger.isDebugEnabled()) {
            super.channelActive(context);
        }
    }

    /**
     * @return the direction that traffic is going to this handler (" <== " or " ==> ")
     */
    @Override
    protected
    String getConnectionDirection() {
        return " <== ";
    }

    @Override
    public
    void channelRead(ChannelHandlerContext context, Object message) throws Exception {
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
}

