package dorkbox.network;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.oio.OioDatagramChannel;
import io.netty.handler.codec.dns.DnsQuery;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsType;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ThreadLocalRandom;

import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.security.AccessControlException;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;

import dorkbox.network.dns.DnsHandler;
import dorkbox.network.dns.SuccessHandler;
import dorkbox.network.dns.decoder.DnsException;
import dorkbox.util.NamedThreadFactory;


public class DnsClient {
    private final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(getClass());

    static {
        try {
            // doesn't work in eclipse.
            // Needed for NIO selectors on Android 2.2, and to force IPv4.
            System.setProperty("java.net.preferIPv4Stack", Boolean.TRUE.toString());
            System.setProperty("java.net.preferIPv6Addresses", Boolean.FALSE.toString());
        } catch (AccessControlException ignored) {}
    }

    private final Bootstrap dnsBootstrap;
    private final ChannelFuture future;
    private InetSocketAddress dnsServer = null;


    /**
     * Retrieve the public facing IP address of this system using DNS.
     * <p>
     * Same command as
     * <p>
     *  dig +short myip.opendns.com @resolver1.opendns.com
     *
     * @return the public IP address if found, or null if it didn't find it
     */
    public static String getPublicIp() {
        final InetSocketAddress dnsServer = new InetSocketAddress("resolver1.opendns.com", 53);

        DnsClient dnsClient = new DnsClient(dnsServer);
        List<Object> submitQuestion = dnsClient.submitQuestion(new DnsQuestion("myip.opendns.com", DnsType.A));
        dnsClient.stop();

        if (!submitQuestion.isEmpty()) {
            Object object = submitQuestion.get(0);
            if (object instanceof Inet4Address) {
                String hostAddress = ((Inet4Address) object).getHostAddress();
                return hostAddress;
            }
        }

        return null;
    }


    /**
     * Creates a new DNS client.
     * @param dnsServer the server to receive your DNS questions.
     */
    public DnsClient(final InetSocketAddress dnsServer) {
        this.dnsBootstrap = new Bootstrap();

        // setup the thread group to easily ID what the following threads belong to (and their spawned threads...)
        SecurityManager s = System.getSecurityManager();
        ThreadGroup nettyGroup = new ThreadGroup(s != null ? s.getThreadGroup() : Thread.currentThread().getThreadGroup(), "DnsClient (Netty)");

        EventLoopGroup group;
        if (PlatformDependent.isAndroid()) {
            group = new OioEventLoopGroup(0, new NamedThreadFactory("DnsClient-boss-UDP", nettyGroup));
            this.dnsBootstrap.channel(OioDatagramChannel.class);
        } else {
            group = new NioEventLoopGroup(2, new NamedThreadFactory("DnsClient-boss-UDP", nettyGroup));
            this.dnsBootstrap.channel(NioDatagramChannel.class);
        }

        this.dnsBootstrap.group(group);
        this.dnsBootstrap.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        this.dnsBootstrap.handler(new DnsHandler());



        this.future = this.dnsBootstrap.connect(dnsServer);
        try {
            this.future.await();

            if (this.future.isSuccess()) {
                this.dnsServer = dnsServer;
            } else {
                this.dnsServer = null;
                Logger logger2 = this.logger;
                if (logger2.isDebugEnabled()) {
                    logger2.error("Could not connect to the DNS server.", this.future.cause());
                } else {
                    logger2.error("Could not connect to the DNS server.");
                }
            }

        } catch (Exception e) {
            Logger logger2 = this.logger;
            if (logger2.isDebugEnabled()) {
                logger2.error("Could not connect to the DNS server on port {}.", dnsServer.getPort(), e.getCause());
            } else {
                logger2.error("Could not connect to the DNS server on port {}.", dnsServer.getPort());
            }
        }
    }

    /**
     * Submits a question to the DNS server
     * @return always non-null, a list of answers from the server. Am empty list can also mean there was an error.
     */
    @SuppressWarnings("unchecked")
    public synchronized List<Object> submitQuestion(final DnsQuestion question) {
        if (this.dnsServer == null) {
            this.logger.error("Cannot submit query. There was no connection to the DNS server.");
            return Collections.EMPTY_LIST;
        }

        DnsQuery query = new DnsQuery(ThreadLocalRandom.current().nextInt(), this.dnsServer).addQuestion(question);

        final Promise<Object> promise = GlobalEventExecutor.INSTANCE.newPromise();

        ChannelFuture writeAndFlush = this.future.channel().writeAndFlush(query);
        writeAndFlush.addListener(new SuccessHandler(promise));

        Promise<Object> result = promise.awaitUninterruptibly();

        // now return whatever value we had
        if (result.isSuccess() && result.isDone()) {
            return (List<Object>) result.getNow();
        } else {
            Throwable cause = result.cause();

            Logger logger2 = this.logger;
            if (cause instanceof DnsException || logger2.isDebugEnabled() && cause != null) {
                logger2.error("Could not ask question to DNS server.", cause);
            } else {
                logger2.error("Could not ask question to DNS server.");
            }
        }

        return Collections.EMPTY_LIST;
    }

    /**
     * Safely closes all associated resources/threads/connections
     */
    public void stop() {
        // now we stop all of our channels
        Channel channel = this.future.channel();
        channel.close().awaitUninterruptibly(2000L);

        // we want to WAIT until after the event executors have completed shutting down.
        Future<?> shutdownThread = this.dnsBootstrap.group().shutdownGracefully();
        shutdownThread.awaitUninterruptibly(2000L);
    }
}
