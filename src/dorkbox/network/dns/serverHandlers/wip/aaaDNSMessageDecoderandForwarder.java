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
package dorkbox.network.dns.serverHandlers.wip;

import java.net.InetSocketAddress;

import dorkbox.network.dns.records.DnsMessage;
import dorkbox.util.NamedThreadFactory;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.oio.OioDatagramChannel;
import io.netty.util.internal.PlatformDependent;

@ChannelHandler.Sharable
public
class aaaDNSMessageDecoderandForwarder extends ChannelInboundHandlerAdapter {

    /**
     * This is what is called whenever a DNS packet is received. Currently only support UDP packets.
     *
     * Calls {@link ChannelHandlerContext#fireChannelRead(Object)} to forward
     * to the next {@link ChannelInboundHandler} in the {@link ChannelPipeline}.
     * <p>
     * Sub-classes may override this method to change behavior.
     */
    @Override
    public
    void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof DatagramPacket) {
            ByteBuf content = ((DatagramPacket) msg).content();

            if (content.readableBytes() == 0) {
                // we can't read this message, there's nothing there!
                System.err.println("NO CONTENT ");
                ctx.fireChannelRead(msg);
                return;
            }

            DnsMessage msg1 = new DnsMessage(content);

            // should get one from a pool!

            Bootstrap dnsBootstrap = new Bootstrap();

            // setup the thread group to easily ID what the following threads belong to (and their spawned threads...)
            SecurityManager s = System.getSecurityManager();
            ThreadGroup nettyGroup = new ThreadGroup(s != null ? s.getThreadGroup() : Thread.currentThread().getThreadGroup(),
                                                     "DnsClient (Netty)");

            EventLoopGroup group;
            if (PlatformDependent.isAndroid()) {
                group = new OioEventLoopGroup(0, new NamedThreadFactory("DnsClient-boss-UDP", nettyGroup));
                dnsBootstrap.channel(OioDatagramChannel.class);
            }
            else {
                group = new NioEventLoopGroup(2, new NamedThreadFactory("DnsClient-boss-UDP", nettyGroup));
                dnsBootstrap.channel(NioDatagramChannel.class);
            }

            dnsBootstrap.group(group);
            dnsBootstrap.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
            // dnsBootstrap.handler(new DnsHandler());



            // sending the question
            final ChannelFuture future = dnsBootstrap.connect(new InetSocketAddress("8.8.8.8", 53));
            try {
                future.await();

                if (future.isSuccess()) {
                    // woo, connected!
                    System.err.println("CONNECTED");
                    // this.dnsServer = dnsServer;
                }
                else {
                    System.err.println("CANNOT CONNECT!");
                    // this.dnsServer = null;
                    // Logger logger2 = this.logger;
                    // if (logger2.isDebugEnabled()) {
                    //     logger2.error("Could not connect to the DNS server.", this.future.cause());
                    // }
                    // else {
                    //     logger2.error("Could not connect to the DNS server.");
                    // }
                }

            } catch (Exception e) {
                e.printStackTrace();
                // Logger logger2 = this.logger;
                // if (logger2.isDebugEnabled()) {
                //     logger2.error("Could not connect to the DNS server on port {}.", dnsServer.getPort(), e.getCause());
                // }
                // else {
                //     logger2.error("Could not connect to the DNS server on port {}.", dnsServer.getPort());
                // }
            }







            //
            // ClientBootstrap cb = new ClientBootstrap(this.clientChannelFactory);
            // cb.setOption("broadcast", "false");
            //
            // cb.setPipelineFactory(new ChannelPipelineFactory() {
            //     @Override
            //     public
            //     ChannelPipeline getPipeline() throws Exception {
            //         return Channels.pipeline(new ClientHanler(original, e.getChannel(), e.getRemoteAddress()));
            //     }
            // });
            //
            // List<SocketAddress> newlist = new ArrayList<SocketAddress>(this.config.getForwarders());
            // sendRequest(e, original, cb, newlist);


        }
        else {
            ctx.fireChannelRead(msg);
        }
    }

    // protected
    // void sendRequest(final MessageEvent e, final DNSMessage original, final ClientBootstrap bootstrap, final List<SocketAddress> forwarders) {
    //
    //     if (0 < forwarders.size()) {
    //         SocketAddress sa = forwarders.remove(0);
    //         LOG.debug("send to {}", sa);
    //
    //         ChannelFuture f = bootstrap.connect(sa);
    //         ChannelBuffer newone = ChannelBuffers.buffer(512);
    //         DNSMessage msg = new DNSMessage(original);
    //         msg.write(newone);
    //         newone.resetReaderIndex();
    //         final Channel c = f.getChannel();
    //
    //         if (LOG.isDebugEnabled()) {
    //             LOG.debug("STATUS : [isOpen/isConnected/isWritable {}] {} {}",
    //                       new Object[] {new boolean[] {c.isOpen(), c.isConnected(), c.isWritable()}, c.getRemoteAddress(), c.getClass()});
    //         }
    //
    //         c.write(newone, sa).addListener(new ChannelFutureListener() {
    //             @Override
    //             public
    //             void operationComplete(ChannelFuture future) throws Exception {
    //                 LOG.debug("request complete isSuccess : {}", future.isSuccess());
    //                 if (future.isSuccess() == false) {
    //                     if (0 < forwarders.size()) {
    //                         sendRequest(e, original, bootstrap, forwarders);
    //                     }
    //                     else {
    //                         original.header().rcode(RCode.ServFail);
    //                         ChannelBuffer buffer = ChannelBuffers.buffer(512);
    //                         original.write(buffer);
    //                         // close inbound channel
    //                         e.getChannel().write(buffer).addListener(ChannelFutureListener.CLOSE);
    //                     }
    //                 }
    //             }
    //         });
    //
    //         // f.awaitUninterruptibly(30, TimeUnit.SECONDS);
    //     }
    // }
}
