package dorkbox.network.connection.registration.local;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;
import dorkbox.network.connection.Connection;
import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import dorkbox.util.collections.IntMap;

public class RegistrationLocalHandlerClient extends RegistrationLocalHandler {

    public RegistrationLocalHandlerClient(String name, RegistrationWrapper registrationWrapper) {
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
     * STEP 2: Channel is now active. Start the registration process
     */
    @Override
    public void channelActive(ChannelHandlerContext context) throws Exception {
        if (logger.isDebugEnabled()) {
            super.channelActive(context);
        }

        Channel channel = context.channel();
        channel.writeAndFlush(new Registration());
    }

    /**
     * @return the direction that traffic is going to this handler (" <== " or " ==> ")
     */
    @Override
    protected String getConnectionDirection() {
        return " ==> ";
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        ReferenceCountUtil.release(message);

        Channel channel = context.channel();

        MetaChannel metaChannel = null;
        try {
            IntMap<MetaChannel> channelMap = registrationWrapper.getAndLockChannelMap();
            metaChannel = channelMap.remove(channel.hashCode());
        } finally {
            registrationWrapper.releaseChannelMap();
        }

        // have to setup new listeners
        if (metaChannel != null) {
            channel.pipeline().remove(this);

            // Event though a local channel is XOR with everything else, we still have to make the client clean up it's state.
            registrationWrapper.registerNextProtocol0();

            Connection connection = metaChannel.connection;
            registrationWrapper.connectionConnected0(connection);
        } else {
            // this should NEVER happen!
            logger.error("Error registering LOCAL channel! MetaChannel is null!");
            shutdown(registrationWrapper, channel);
        }
    }
}

