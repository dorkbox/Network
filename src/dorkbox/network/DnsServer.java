package dorkbox.network;

import org.slf4j.Logger;

import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.Shutdownable;
import dorkbox.network.dns.serverHandlers.DnsServerHandler;
import dorkbox.util.NamedThreadFactory;
import dorkbox.util.OS;
import dorkbox.util.Property;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.oio.OioDatagramChannel;
import io.netty.channel.socket.oio.OioServerSocketChannel;

/**
 * from: https://blog.cloudflare.com/how-the-consumer-product-safety-commission-is-inadvertently-behind-the-internets-largest-ddos-attacks/
 *
 * NOTE: CloudFlare has anti-DNS reflection protections in place. Specifically, we automatically upgrade from UDP to TCP when a DNS response
 * is particularly large (generally, over 512 bytes). Since TCP requires a handshake, it prevents source IP address spoofing which is
 * necessary for a DNS amplification attack.
 *
 * In addition, we rate limit unknown resolvers. Again, this helps ensure that our infrastructure can't be abused to amplify attacks.
 *
 * Finally, across our DNS infrastructure we have deprecated ANY queries and have proposed to the IETF to restrict ANY queries to only
 * authorized parties. By neutering ANY, we've significantly reduced the maximum size of responses even for zone files that need to be
 * large due to a large number of records.
 */
public
class DnsServer extends Shutdownable {

    /**
     * The maximum queue length for incoming connection indications (a request to connect). If a connection indication arrives when the
     * queue is full, the connection is refused.
     */
    @Property
    public static int backlogConnectionCount = 50;

    private final ServerBootstrap tcpBootstrap;
    private final Bootstrap udpBootstrap;

    private final int tcpPort;
    private final int udpPort;
    private final String hostName;


    public
    DnsServer(String host, int port) {
        super(DnsServer.class);

        tcpPort = port;
        udpPort = port;

        if (host == null) {
            hostName = "0.0.0.0";
        }
        else {
            hostName = host;
        }

        String threadName = DnsServer.class.getSimpleName();


        final EventLoopGroup boss;
        final EventLoopGroup worker;

        if (OS.isAndroid()) {
            // android ONLY supports OIO (not NIO)
            boss = new OioEventLoopGroup(0, new NamedThreadFactory(threadName + "-boss", threadGroup));
            worker = new OioEventLoopGroup(0, new NamedThreadFactory(threadName, threadGroup));
        }
        else if (OS.isLinux()) {
            // JNI network stack is MUCH faster (but only on linux)
            boss = new EpollEventLoopGroup(EndPoint.DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(threadName + "-boss", threadGroup));
            worker = new EpollEventLoopGroup(EndPoint.DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(threadName, threadGroup));
        }
        else {
            boss = new NioEventLoopGroup(EndPoint.DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(threadName + "-boss", threadGroup));
            worker = new NioEventLoopGroup(EndPoint.DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(threadName, threadGroup));
        }


        manageForShutdown(boss);
        manageForShutdown(worker);


        tcpBootstrap = new ServerBootstrap();
        udpBootstrap = new Bootstrap();


            if (OS.isAndroid()) {
                // android ONLY supports OIO (not NIO)
                tcpBootstrap.channel(OioServerSocketChannel.class);
            }
            else if (OS.isLinux()) {
                // JNI network stack is MUCH faster (but only on linux)
                tcpBootstrap.channel(EpollServerSocketChannel.class);
            }
            else {
                tcpBootstrap.channel(NioServerSocketChannel.class);
            }

            // TODO: If we use netty for an HTTP server,
            // Beside the usual ChannelOptions the Native Transport allows to enable TCP_CORK which may come in handy if you implement a HTTP Server.

            tcpBootstrap.group(boss, worker)
                        .option(ChannelOption.SO_BACKLOG, backlogConnectionCount)
                        .option(ChannelOption.SO_REUSEADDR, true)
                        .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                        .childOption(ChannelOption.SO_KEEPALIVE, true)
                        .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(EndPoint.WRITE_BUFF_LOW, EndPoint.WRITE_BUFF_HIGH))
                        .childHandler(new DnsServerHandler());

            // have to check options.host for null. we don't bind to 0.0.0.0, we bind to "null" to get the "any" address!
            if (hostName != null) {
                tcpBootstrap.localAddress(hostName, tcpPort);
            }
            else {
                tcpBootstrap.localAddress(tcpPort);
            }


            // android screws up on this!!
            tcpBootstrap.option(ChannelOption.TCP_NODELAY, !OS.isAndroid())
                        .childOption(ChannelOption.TCP_NODELAY, !OS.isAndroid());


            if (OS.isAndroid()) {
                // android ONLY supports OIO (not NIO)
                udpBootstrap.channel(OioDatagramChannel.class);
            }
            else if (OS.isLinux()) {
                // JNI network stack is MUCH faster (but only on linux)
                udpBootstrap.channel(EpollDatagramChannel.class)
                            .option(EpollChannelOption.SO_REUSEPORT, true);
            }
            else {
                udpBootstrap.channel(NioDatagramChannel.class);
            }

            udpBootstrap.group(worker)
                        .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                        .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(EndPoint.WRITE_BUFF_LOW, EndPoint.WRITE_BUFF_HIGH))

                        // not binding to specific address, since it's driven by TCP, and that can be bound to a specific address
                        .localAddress(udpPort) // if you bind to a specific interface, Linux will be unable to receive broadcast packets!
                        .handler(new DnsServerHandler());
    }

    /**
     * Binds the server to the configured, underlying protocols.
     * <p/>
     * This method will also BLOCK until the stop method is called, and if you want to continue running code after this method invocation,
     * bind should be called in a separate, non-daemon thread.
     */
    public
    void bind() {
        bind(true);
    }

    /**
     * Binds the server to the configured, underlying protocols.
     * <p/>
     * This is a more advanced method, and you should consider calling <code>bind()</code> instead.
     *
     * @param blockUntilTerminate will BLOCK until the server stop method is called, and if you want to continue running code after this method
     *         invocation, bind should be called in a separate, non-daemon thread - or with false as the parameter.
     */
    @SuppressWarnings("AutoBoxing")
    public
    void bind(boolean blockUntilTerminate) {
        // make sure we are not trying to connect during a close or stop event.
        // This will wait until we have finished starting up/shutting down.
        synchronized (shutdownInProgress) {
        }


        // The bootstraps will be accessed ONE AT A TIME, in this order!
        ChannelFuture future;

        Logger logger2 = logger;


        // TCP
        // Wait until the connection attempt succeeds or fails.
        // try {
        //     future = tcpBootstrap.bind();
        //     future.await();
        // } catch (Exception e) {
        //     // String errorMessage = stopWithErrorMessage(logger2,
        //     //                                            "Could not bind to address " + hostName + " TCP port " + tcpPort +
        //     //                                            " on the server.",
        //     //                                            e);
        //     // throw new IllegalArgumentException(errorMessage);
        //     throw new RuntimeException();
        // }
        //
        // if (!future.isSuccess()) {
        //     // String errorMessage = stopWithErrorMessage(logger2,
        //     //                                            "Could not bind to address " + hostName + " TCP port " + tcpPort +
        //     //                                            " on the server.",
        //     //                                            future.cause());
        //     // throw new IllegalArgumentException(errorMessage);
        //     throw new RuntimeException();
        // }
        //
        // // logger2.info("Listening on address {} at TCP port: {}", hostName, tcpPort);
        //
        // manageForShutdown(future);


        // UDP
        // Wait until the connection attempt succeeds or fails.
        try {
            future = udpBootstrap.bind();
            future.await();
        } catch (Exception e) {
            String errorMessage = stopWithErrorMessage(logger2,
                                                       "Could not bind to address " + hostName + " UDP port " + udpPort +
                                                       " on the server.",
                                                       e);
            throw new IllegalArgumentException(errorMessage);
        }

        if (!future.isSuccess()) {
            String errorMessage = stopWithErrorMessage(logger2,
                                                       "Could not bind to address " + hostName + " UDP port " + udpPort +
                                                       " on the server.",
                                                       future.cause());
            throw new IllegalArgumentException(errorMessage);
        }

        // logger2.info("Listening on address {} at UDP port: {}", hostName, udpPort);
        manageForShutdown(future);

        // we now BLOCK until the stop method is called.
        // if we want to continue running code in the server, bind should be called in a separate, non-daemon thread.
        if (blockUntilTerminate) {
            waitForShutdown();
        }
    }
}