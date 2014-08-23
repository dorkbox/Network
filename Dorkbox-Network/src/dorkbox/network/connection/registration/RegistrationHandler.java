package dorkbox.network.connection.registration;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import dorkbox.network.connection.RegistrationWrapper;
import dorkbox.network.util.primativeCollections.IntMap;
import dorkbox.network.util.primativeCollections.IntMap.Entries;

@Sharable
public abstract class RegistrationHandler extends ChannelInboundHandlerAdapter {
    protected static final String CONNECTION_HANDLER = "connectionHandler";

    protected final RegistrationWrapper registrationWrapper;
    protected final org.slf4j.Logger logger;
    protected final String name;


    public RegistrationHandler(String name, RegistrationWrapper registrationWrapper) {
        this.name = name + " Discovery/Registration";
        this.logger = org.slf4j.LoggerFactory.getLogger(this.name);
        this.registrationWrapper = registrationWrapper;
    }

    protected void initChannel(Channel channel) {
    }

    @Override
    public final void channelRegistered(ChannelHandlerContext context) throws Exception {
        boolean success = false;
        try {
            initChannel(context.channel());
            context.fireChannelRegistered();
            success = true;
        } catch (Throwable t) {
            this.logger.error("Failed to initialize a channel. Closing: {}", context.channel(), t);
        } finally {
            if (!success) {
                context.close();
            }
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext context) throws Exception {
        this.logger.error("ChannelActive NOT IMPLEMENTED!");
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        this.logger.error("MessageReceived NOT IMPLEMENTED!");
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext context) throws Exception {
        context.flush();
    }

    @Override
    public abstract void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception;

    public MetaChannel shutdown(RegistrationWrapper registrationWrapper, Channel channel) {
        this.logger.error("SHUTDOWN HANDLER REACHED! SOMETHING MESSED UP! TRYING TO ABORT");

        // shutdown. Something messed up. Only reach this is something messed up.
        // properly shutdown the TCP/UDP channels.
        if (channel.isOpen()) {
            channel.close();
        }

        // also, once we notify, we unregister this.
        if (registrationWrapper != null) {
            try {
                IntMap<MetaChannel> channelMap = registrationWrapper.getAndLockChannelMap();
                Entries<MetaChannel> entries = channelMap.entries();
                while (entries.hasNext()) {
                    MetaChannel metaChannel = entries.next().value;
                    if (metaChannel.localChannel == channel || metaChannel.tcpChannel == channel || metaChannel.udpChannel == channel) {
                        entries.remove();
                        metaChannel.close();
                        return metaChannel;
                    }
                }

            } finally {
                registrationWrapper.releaseChannelMap();
                registrationWrapper.abortRegistrationIfClient();
            }
        }

        return null;
    }
}

