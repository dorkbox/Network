/*
 * Copyright 2018 dorkbox, llc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.netty.bootstrap;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import dorkbox.network.pipeline.discovery.BroadcastServer;
import dorkbox.util.bytes.BigEndian.Long_;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.AttributeKey;
import io.netty.util.collection.LongObjectHashMap;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 *
 */
public
class SessionManager extends ChannelInboundHandlerAdapter {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(SessionBootstrap.class);

    private static
    void forceClose(DatagramSessionChannel child, Throwable t) throws Exception {
        child.unsafe()
             .closeForcibly();

        child.doClose();

        logger.warn("Failed to register an accepted channel: {}", child, t);
    }

    private static
    long getChannelId(final InetSocketAddress remoteAddress) {
        int address = remoteAddress.getAddress()
                                   .hashCode(); // we want it as an int

        int port = remoteAddress.getPort(); // this is really just 2 bytes

        byte[] combined = new byte[8];
        combined[0] = (byte) ((port >>> 24) & 0xFF);
        combined[1] = (byte) ((port >>> 16) & 0xFF);
        combined[2] = (byte) ((port >>> 8) & 0xFF);
        combined[3] = (byte) ((port) & 0xFF);
        combined[4] = (byte) ((address >>> 24) & 0xFF);
        combined[5] = (byte) ((address >>> 16) & 0xFF);
        combined[6] = (byte) ((address >>> 8) & 0xFF);
        combined[7] = (byte) ((address) & 0xFF);

        return Long_.from(combined);
    }

    private final BroadcastServer broadcastServer;
    // Does not need to be thread safe, because access only happens in the event loop
    private final LongObjectHashMap<DatagramSessionChannel> datagramChannels = new LongObjectHashMap<DatagramSessionChannel>();


    private final EventLoopGroup childGroup;
    private final ChannelHandler childHandler;

    private final Entry<ChannelOption<?>, Object>[] childOptions;
    private final Entry<AttributeKey<?>, Object>[] childAttrs;

    private final Runnable enableAutoReadTask;
    private final DatagramSessionChannelConfig sessionConfig;


    SessionManager(final int tcpPort, final int udpPort,
                   final Channel channel,
                   EventLoopGroup childGroup,
                   ChannelHandler childHandler,
                   Entry<ChannelOption<?>, Object>[] childOptions,
                   Entry<AttributeKey<?>, Object>[] childAttrs) {

        this.sessionConfig = new DatagramSessionChannelConfig(channel);

        this.childGroup = childGroup;
        this.childHandler = childHandler;
        this.childOptions = childOptions;
        this.childAttrs = childAttrs;

        // Task which is scheduled to re-enable auto-read.
        // It's important to create this Runnable before we try to submit it as otherwise the URLClassLoader may
        // not be able to load the class because of the file limit it already reached.
        //
        // See https://github.com/netty/netty/issues/1328
        enableAutoReadTask = new Runnable() {
            @Override
            public
            void run() {
                channel.config()
                       .setAutoRead(true);
            }
        };

        broadcastServer = new BroadcastServer(tcpPort, udpPort);
    }

    @Override
    public
    void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);

        // have to close all of the "fake" DatagramChannels as well. Each one will remove itself from the channel map.

        // We make a copy of this b/c of concurrent modification, in the event this is closed BEFORE the child-channels are closed
        ArrayList<DatagramSessionChannel> channels = new ArrayList<DatagramSessionChannel>(datagramChannels.values());
        for (DatagramSessionChannel datagramSessionChannel : channels) {
            if (datagramSessionChannel.isActive()) {
                datagramSessionChannel.close();
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public
    void channelRead(ChannelHandlerContext context, Object msg) {
        Channel channel = context.channel();

        DatagramPacket packet = ((DatagramPacket) msg);

        ByteBuf content = packet.content();
        InetSocketAddress localAddress = packet.recipient();
        InetSocketAddress remoteAddress = packet.sender();

        // check to see if it's a broadcast packet or not
        if (broadcastServer.isDiscoveryRequest(channel, content, localAddress, remoteAddress)) {
            // don't bother creating channels if this is a broadcast event. Just respond and be finished
            return;
        }


        long channelId = getChannelId(remoteAddress);

        // create a new channel or reuse existing one
        DatagramSessionChannel sessionChannel = datagramChannels.get(channelId);
        ChannelPipeline sessionPipeline;
        if (sessionChannel == null) {
            try {
                sessionChannel = new DatagramSessionChannel(context.channel(), this, sessionConfig, localAddress, remoteAddress);
                datagramChannels.put(channelId, sessionChannel);

                sessionPipeline = sessionChannel.pipeline();

                // add the child handler to the fake channel
                sessionPipeline.addLast(childHandler);

                // setup the channel options
                AbstractBootstrap.setChannelOptions(sessionChannel, childOptions, logger);

                for (Entry<AttributeKey<?>, Object> e : childAttrs) {
                    sessionChannel.attr((AttributeKey<Object>) e.getKey())
                                  .set(e.getValue());
                }

                try {
                    final DatagramSessionChannel finalSessionChannel = sessionChannel;
                    // the childGroup is the HANDSHAKE group. Once handshake is done, this will be passed off to a worker group
                    childGroup.register(sessionChannel)
                              .addListener(new ChannelFutureListener() {
                                  @Override
                                  public
                                  void operationComplete(ChannelFuture future) throws Exception {
                                      if (!future.isSuccess()) {
                                          forceClose(finalSessionChannel, future.cause());
                                      }
                                  }
                              });
                } catch (Throwable t) {
                    forceClose(sessionChannel, t);
                }
            } catch (Throwable t) {
                logger.warn("Failed to create a new datagram channel from a read operation.", t);

                try {
                    if (sessionChannel != null) {
                        sessionChannel.close();
                    }
                } catch (Throwable t2) {
                    logger.warn("Failed to close the datagram channel.", t2);
                }

                return;
            }
        } else {
            sessionPipeline = sessionChannel.pipeline();
        }

        // immediately trigger a read in the session pipeline
        sessionPipeline.fireChannelRead(packet);

        // will flush the pipeline if necessary
        sessionPipeline.fireChannelReadComplete();
    }

    /**
     * ADDED to support closing a DatagramSessionChannel. Always called from the EventLoop
     */
    public
    void doCloseChannel(final DatagramSessionChannel datagramSessionChannel) {
        InetSocketAddress remoteAddress = datagramSessionChannel.remoteAddress();
        long channelId = getChannelId(remoteAddress);

        datagramChannels.remove(channelId);
    }

    @Override
    public
    void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        final ChannelConfig config = ctx.channel()
                                        .config();
        if (config.isAutoRead()) {
            // stop accept new connections for 1 second to allow the channel to recover
            // See https://github.com/netty/netty/issues/1328
            config.setAutoRead(false);
            ctx.channel()
               .eventLoop()
               .schedule(enableAutoReadTask, 1, TimeUnit.SECONDS);
        }
        // still let the exceptionCaught event flow through the pipeline to give the user
        // a chance to do something with it
        ctx.fireExceptionCaught(cause);
    }
}
