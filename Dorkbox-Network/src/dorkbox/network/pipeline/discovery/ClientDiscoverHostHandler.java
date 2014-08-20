package dorkbox.network.pipeline.discovery;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.AttributeKey;

import java.net.InetSocketAddress;

import dorkbox.network.Broadcast;


public class ClientDiscoverHostHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    // This uses CHANNEL LOCAL to save the data.

    public static final AttributeKey<InetSocketAddress> STATE = AttributeKey.valueOf(ClientDiscoverHostHandler.class, "Discover.state");

    @Override
    protected void channelRead0(ChannelHandlerContext context, DatagramPacket message) throws Exception {
        ByteBuf data = message.content();
        if (data.readableBytes() == 1 && data.readByte() == Broadcast.broadcastResponseID) {
            context.channel().attr(STATE).set(message.sender());
            context.channel().close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception {
        context.channel().close();
    }
}

