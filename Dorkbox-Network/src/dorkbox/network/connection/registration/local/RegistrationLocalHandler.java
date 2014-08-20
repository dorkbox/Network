package dorkbox.network.connection.registration.local;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.RegistrationHandler;
import dorkbox.network.util.primativeCollections.IntMap;
import dorkbox.network.util.primativeCollections.IntMap.Entries;

public abstract class RegistrationLocalHandler extends RegistrationHandler {

    public RegistrationLocalHandler(String name, RegistrationWrapper registrationWrapper) {
        super(name, registrationWrapper);
    }

    /**
     * STEP 1: Channel is first created
     */
    @Override
    protected void initChannel(Channel channel) {
        MetaChannel metaChannel = new MetaChannel();
        metaChannel.localChannel = channel;

        try {
            IntMap<MetaChannel> channelMap = registrationWrapper.getAndLockChannelMap();
            channelMap.put(channel.hashCode(), metaChannel);
        } finally {
            registrationWrapper.releaseChannelMap();
        }

        logger.trace("New LOCAL connection.");

        registrationWrapper.connection0(metaChannel);

        // have to setup connection handler
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast(CONNECTION_HANDLER, metaChannel.connection);
    }

    /**
     * STEP 2: Channel is now active. Start the registration process
     */
    @Override
    public void channelActive(ChannelHandlerContext context) throws Exception {
        if (logger.isDebugEnabled()) {
            Channel channel = context.channel();

            StringBuilder builder = new StringBuilder(76);
            builder.append("Connected to LOCAL connection. [");
            builder.append(context.channel().localAddress());
            builder.append(getConnectionDirection());
            builder.append(channel.remoteAddress());
            builder.append("]");

            logger.debug(builder.toString());
        }
    }

    /**
     * @return the direction that traffic is going to this handler (" <== " or " ==> ")
     */
    protected abstract String getConnectionDirection();


    // this SHOULDN'T ever happen, but we might shutdown in the middle of registration
    @Override
    public final void channelInactive(ChannelHandlerContext context) throws Exception {
        Channel channel = context.channel();

        logger.info("Closed LOCAL connection: {}", channel.remoteAddress());

        long maxShutdownWaitTimeInMilliSeconds = EndPoint.maxShutdownWaitTimeInMilliSeconds;

        // also, once we notify, we unregister this.

        try {
            IntMap<MetaChannel> channelMap = registrationWrapper.getAndLockChannelMap();
            Entries<MetaChannel> entries = channelMap.entries();
            while (entries.hasNext()) {
                MetaChannel metaChannel = entries.next().value;

                if (metaChannel.localChannel == channel) {
                    metaChannel.close(maxShutdownWaitTimeInMilliSeconds);
                    entries.remove();
                    break;
                }
            }
        } finally {
            registrationWrapper.releaseChannelMap();
        }

        super.channelInactive(context);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception {
        Channel channel = context.channel();

        logger.error("Unexpected exception while trying to receive data on LOCAL channel.  ({})" + System.getProperty("line.separator"), channel.remoteAddress(), cause);
        if (channel.isOpen()) {
            channel.close();
        }
    }
}

