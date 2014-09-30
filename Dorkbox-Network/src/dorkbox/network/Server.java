package dorkbox.network;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.oio.OioDatagramChannel;
import io.netty.channel.socket.oio.OioServerSocketChannel;

import org.slf4j.Logger;

import dorkbox.network.connection.EndPointServer;
import dorkbox.network.connection.registration.local.RegistrationLocalHandlerServer;
import dorkbox.network.connection.registration.remote.RegistrationRemoteHandlerServerTCP;
import dorkbox.network.connection.registration.remote.RegistrationRemoteHandlerServerUDP;
import dorkbox.network.connection.registration.remote.RegistrationRemoteHandlerServerUDT;
import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.SecurityException;
import dorkbox.network.util.udt.UdtEndpointProxy;
import dorkbox.util.NamedThreadFactory;
import dorkbox.util.OS;


/**
 * The server can only be accessed in an ASYNC manner. This means that the server can only be used in RESPONSE
 * to events. If you access the server OUTSIDE of events, you will get inaccurate information from the server (such as getConnections())
 * <p>
 *  To put it bluntly, ONLY have the server do work inside of a listener!
 */
public class Server extends EndPointServer {

    /**
     * The maximum queue length for incoming connection indications (a request to connect). If a connection indication arrives when
     * the queue is full, the connection is refused.
     */
    public static int backlogConnectionCount = 50;

    private final ServerBootstrap localBootstrap;
    private final ServerBootstrap tcpBootstrap;
    private final Bootstrap udpBootstrap;
    private final ServerBootstrap udtBootstrap;

    private final int tcpPort;
    private final int udpPort;
    private final int udtPort;

    private final String localChannelName;

    /**
     * Starts a LOCAL <b>only</b> server, with the default serialization scheme
     */
    public Server() throws InitializationException, SecurityException {
        this(new ConnectionOptions(LOCAL_CHANNEL));
    }

    /**
     * Convenience method to starts a server with the specified Connection Options
     */
    public Server(ConnectionOptions options) throws InitializationException, SecurityException {
        // watch-out for serialization... it can be NULL incoming. The EndPoint (superclass) sets it, if null, so
        // you have to make sure to use this.serializatino
        super("Server", options);

        Logger logger2 = this.logger;
        if (isAndroid && options.udtPort > 0) {
            // Android does not support UDT.
            if (logger2.isInfoEnabled()) {
                logger2.info("Android does not support UDT.");
            }
            options.udtPort = -1;
        }

        this.tcpPort = options.tcpPort;
        this.udpPort = options.udpPort;
        this.udtPort = options.udtPort;

        this.localChannelName = options.localChannelName;

        if (this.localChannelName != null ) {
            this.localBootstrap = new ServerBootstrap();
        } else {
            this.localBootstrap = null;
        }

        if (this.tcpPort > 0) {
            this.tcpBootstrap = new ServerBootstrap();
        } else {
            this.tcpBootstrap = null;
        }

        if (this.udpPort > 0) {
            this.udpBootstrap = new Bootstrap();
        } else {
            this.udpBootstrap = null;
        }

        if (this.udtPort > 0) {
            // check to see if we have UDT available!
            boolean udtAvailable = false;
            try {
                Class.forName("com.barchart.udt.nio.SelectorProviderUDT");
                udtAvailable = true;
            } catch (Throwable e) {
                logger2.error("Requested a UDT service on port {}, but the barchart UDT libraries are not loaded.", this.udtPort);
            }

            if (udtAvailable) {
                this.udtBootstrap = new ServerBootstrap();
            } else {
                this.udtBootstrap = null;
            }
        } else {
            this.udtBootstrap = null;
        }

        //TODO: do we need to set the snd/rcv buffer?
//        tcpBootstrap.setOption(SO_SNDBUF, 1048576);
//        tcpBootstrap.setOption(SO_RCVBUF, 1048576);

        // setup the thread group to easily ID what the following threads belong to (and their spawned threads...)
        SecurityManager s = System.getSecurityManager();
        ThreadGroup nettyGroup = new ThreadGroup(s != null ? s.getThreadGroup() : Thread.currentThread().getThreadGroup(), this.name + " (Netty)");


        // always use local channels on the server.
        {
            EventLoopGroup boss;
            EventLoopGroup worker;

            if (this.localBootstrap != null) {
                boss = new DefaultEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(this.name + "-boss-LOCAL", nettyGroup));
                worker = new DefaultEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(this.name + "-worker-LOCAL", nettyGroup));

                this.localBootstrap.group(boss, worker)
                                   .channel(LocalServerChannel.class)
                                   .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                                   .localAddress(new LocalAddress(this.localChannelName))
                                   .childHandler(new RegistrationLocalHandlerServer(this.name,
                                                                                    this.registrationWrapper));

                manageForShutdown(boss);
                manageForShutdown(worker);
            }
        }

        if (this.tcpBootstrap != null) {
            EventLoopGroup boss;
            EventLoopGroup worker;

            if (isAndroid) {
                // android ONLY supports OIO (not NIO)
                boss = new OioEventLoopGroup(0, new NamedThreadFactory(this.name + "-boss-TCP", nettyGroup));
                worker = new OioEventLoopGroup(0, new NamedThreadFactory(this.name + "-worker-TCP", nettyGroup));
                this.tcpBootstrap.channel(OioServerSocketChannel.class);
            } else {
                if (OS.isLinux()) {
                    // JNI network stack is MUCH faster (but only on linux)
                    boss = new EpollEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(this.name + "-boss-TCP", nettyGroup));
                    worker = new EpollEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(this.name + "-worker-TCP", nettyGroup));

                    this.tcpBootstrap.channel(EpollServerSocketChannel.class);
                } else {
                    boss = new NioEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(this.name + "-boss-TCP", nettyGroup));
                    worker = new NioEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(this.name + "-worker-TCP", nettyGroup));

                    this.tcpBootstrap.channel(NioServerSocketChannel.class);
                }
            }

            // TODO: If we use netty for an HTTP server,
            // Beside the usual ChannelOptions the Native Transport allows to enable TCP_CORK which may come in handy if you implement a HTTP Server.

            manageForShutdown(boss);
            manageForShutdown(worker);

            this.tcpBootstrap.group(boss, worker)
                             .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                             .option(ChannelOption.SO_BACKLOG, backlogConnectionCount)
                             .option(ChannelOption.SO_REUSEADDR, true)
                             .childHandler(new RegistrationRemoteHandlerServerTCP(this.name,
                                                                                  this.registrationWrapper,
                                                                                  this.serializationManager));

            if (options.host != null) {
                this.tcpBootstrap.localAddress(options.host, this.tcpPort);
            } else {
                this.tcpBootstrap.localAddress(this.tcpPort);
            }

            // android screws up on this!!
            this.tcpBootstrap.option(ChannelOption.TCP_NODELAY, !isAndroid);
            this.tcpBootstrap.childOption(ChannelOption.TCP_NODELAY, !isAndroid);

            this.tcpBootstrap.childOption(ChannelOption.SO_KEEPALIVE, true);
        }


        if (this.udpBootstrap != null) {
            EventLoopGroup worker;

            if (isAndroid) {
                // android ONLY supports OIO (not NIO)
                worker = new OioEventLoopGroup(0, new NamedThreadFactory(this.name + "-worker-UDP", nettyGroup));
                this.udpBootstrap.channel(OioDatagramChannel.class);
            } else {
                if (OS.isLinux()) {
                    // JNI network stack is MUCH faster (but only on linux)
                    worker = new EpollEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(this.name + "-worker-UDP", nettyGroup));

                    this.udpBootstrap.channel(EpollDatagramChannel.class)
                                     .option(EpollChannelOption.SO_REUSEPORT, true);
                } else {
                    worker = new NioEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(this.name + "-worker-UDP", nettyGroup));

                    this.udpBootstrap.channel(NioDatagramChannel.class);
                }
            }

            manageForShutdown(worker);

            this.udpBootstrap.group(worker)
                        .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                         // not binding to specific address, since it's driven by TCP, and that can be bound to a specific address
                        .localAddress(this.udpPort) // if you bind to a specific interface, Linux will be unable to receive broadcast packets!
                        .handler(new RegistrationRemoteHandlerServerUDP(this.name, this.registrationWrapper, this.serializationManager));


            // Enable to READ from MULTICAST data (ie, 192.168.1.0)
            // in order to WRITE: write as normal, just make sure it ends in .255
            // in order to LISTEN:
            //    InetAddress group = InetAddress.getByName("203.0.113.0");
            //    socket.joinGroup(group);
            // THEN once done
            //    socket.leaveGroup(group), close the socket
            // Enable to WRITE to MULTICAST data (ie, 192.168.1.0)
            this.udpBootstrap.option(ChannelOption.SO_BROADCAST, false);
            this.udpBootstrap.option(ChannelOption.SO_SNDBUF, udpMaxSize);
        }


        if (this.udtBootstrap != null) {
            EventLoopGroup boss;
            EventLoopGroup worker;

            // all of this must be proxied to another class, so THIS class doesn't have unmet dependencies.
            // Annoying and abusing the classloader, but it works well.
            boss = UdtEndpointProxy.getServerBoss(DEFAULT_THREAD_POOL_SIZE, this.name, nettyGroup);
            worker = UdtEndpointProxy.getServerWorker(DEFAULT_THREAD_POOL_SIZE, this.name, nettyGroup);

            UdtEndpointProxy.setChannelFactory(this.udtBootstrap);
            this.udtBootstrap.group(boss, worker)
                             .option(ChannelOption.SO_BACKLOG, backlogConnectionCount)
                             .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                             // not binding to specific address, since it's driven by TCP, and that can be bound to a specific address
                             .localAddress(this.udtPort)
                             .childHandler(new RegistrationRemoteHandlerServerUDT(this.name,
                                                                                  this.registrationWrapper,
                                                                                  this.serializationManager));

            manageForShutdown(boss);
            manageForShutdown(worker);
        }
    }

    /**
     * Binds the server to the configured, underlying protocols.
     * <p>
     * This method will also BLOCK until the stop method is called, and if
     * you want to continue running code after this method invocation, bind should be called in a separate, non-daemon thread.
     */
    public void bind() {
        bind(true);
    }

    /**
     * Binds the server to the configured, underlying protocols.
     * <p>
     * This is a more advanced method, and you should consider calling <code>bind()</code> instead.
     *
     * @param blockUntilTerminate will BLOCK until the server stop method is called, and if
     * you want to continue running code after this method invocation, bind should be called in a separate,
     * non-daemon thread - or with false as the parameter.
     */
    public void bind(boolean blockUntilTerminate) {
        // make sure we are not trying to connect during a close or stop event.
        // This will wait until we have finished starting up/shutting down.
        synchronized (this.shutdownInProgress) {
        }


        // Note: The bootstraps will be accessed ONE AT A TIME, in this order!

        ChannelFuture future = null;

        // LOCAL
        Logger logger2 = this.logger;
        if (this.localBootstrap != null) {
            try {
                future = this.localBootstrap.bind();
                future.await();
            } catch (InterruptedException e) {
                String errorMessage = stopWithErrorMessage(logger2, "Could not bind to LOCAL address on the server.", e);
                throw new IllegalArgumentException(errorMessage);
            }

            if (!future.isSuccess()) {
                String errorMessage = stopWithErrorMessage(logger2, "Could not bind to LOCAL address on the server.", future.cause());
                throw new IllegalArgumentException(errorMessage);
            }

            logger2.info("Listening on LOCAL address: '{}'", this.localChannelName);
            manageForShutdown(future);
        }


        // TCP
        if (this.tcpBootstrap != null) {
            // Wait until the connection attempt succeeds or fails.
            try {
                future = this.tcpBootstrap.bind();
                future.await();
            } catch (Exception e) {
                String errorMessage = stopWithErrorMessage(logger2, "Could not bind to TCP port " + this.tcpPort + " on the server.", e);
                throw new IllegalArgumentException(errorMessage);
            }

            if (!future.isSuccess()) {
                String errorMessage = stopWithErrorMessage(logger2, "Could not bind to TCP port " + this.tcpPort + " on the server.", future.cause());
                throw new IllegalArgumentException(errorMessage);
            }

            logger2.info("Listening on TCP port: {}", this.tcpPort);
            manageForShutdown(future);
        }

        // UDP
        if (this.udpBootstrap != null) {
            // Wait until the connection attempt succeeds or fails.
            try {
                future = this.udpBootstrap.bind();
                future.await();
            } catch (Exception e) {
                String errorMessage = stopWithErrorMessage(logger2, "Could not bind to UDP port " + this.udpPort + " on the server.", e);
                throw new IllegalArgumentException(errorMessage);
            }

            if (!future.isSuccess()) {
                String errorMessage = stopWithErrorMessage(logger2, "Could not bind to UDP port " + this.udpPort + " on the server.", future.cause());
                throw new IllegalArgumentException(errorMessage);
            }

            logger2.info("Listening on UDP port: {}", this.udpPort);
            manageForShutdown(future);
        }

        // UDT
        if (this.udtBootstrap != null) {
            // Wait until the connection attempt succeeds or fails.
            try {
                future = this.udtBootstrap.bind();
                future.await();
            } catch (Exception e) {
                String errorMessage = stopWithErrorMessage(logger2, "Could not bind to UDT port " + this.udtPort + " on the server.", e);
                throw new IllegalArgumentException(errorMessage);
            }

            if (!future.isSuccess()) {
                String errorMessage = stopWithErrorMessage(logger2, "Could not bind to UDT port " + this.udtPort + " on the server.", future.cause());
                throw new IllegalArgumentException(errorMessage);
            }

            logger2.info("Listening on UDT port: {}", this.udtPort);
            manageForShutdown(future);
        }

        // we now BLOCK until the stop method is called.
        // if we want to continue running code in the server, bind should be called in a separate, non-daemon thread.
        waitForStop(blockUntilTerminate);
    }
}
