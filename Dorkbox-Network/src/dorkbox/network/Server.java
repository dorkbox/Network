package dorkbox.network;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.oio.OioDatagramChannel;
import io.netty.channel.socket.oio.OioServerSocketChannel;
import dorkbox.network.connection.EndPointServer;
import dorkbox.network.connection.registration.local.RegistrationLocalHandlerServer;
import dorkbox.network.connection.registration.remote.RegistrationRemoteHandlerServerTCP;
import dorkbox.network.connection.registration.remote.RegistrationRemoteHandlerServerUDP;
import dorkbox.network.connection.registration.remote.RegistrationRemoteHandlerServerUDT;
import dorkbox.network.util.InitializationException;
import dorkbox.network.util.NamedThreadFactory;
import dorkbox.network.util.SecurityException;
import dorkbox.network.util.udt.UdtEndpointProxy;


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

        if (isAndroid && options.udtPort > 0) {
            // Android does not support UDT.
            logger.info("Android does not support UDT.");
            options.udtPort = -1;
        }

        this.tcpPort = options.tcpPort;
        this.udpPort = options.udpPort;
        this.udtPort = options.udtPort;

        this.localChannelName = options.localChannelName;

        if (localChannelName != null ) {
            localBootstrap = new ServerBootstrap();
        } else {
            localBootstrap = null;
        }

        if (tcpPort > 0) {
            tcpBootstrap = new ServerBootstrap();
        } else {
            tcpBootstrap = null;
        }

        if (udpPort > 0) {
            udpBootstrap = new Bootstrap();
        } else {
            udpBootstrap = null;
        }

        if (udtPort > 0) {
            // check to see if we have UDT available!
            boolean udtAvailable = false;
            try {
                Class.forName("com.barchart.udt.nio.SelectorProviderUDT");
                udtAvailable = true;
            } catch (Throwable e) {
                logger.error("Requested a UDT service on port {}, but the barchart UDT libraries are not loaded.", udtPort);
            }

            if (udtAvailable) {
                udtBootstrap = new ServerBootstrap();
            } else {
                udtBootstrap = null;
            }
        } else {
            udtBootstrap = null;
        }

        //TODO: do we need to set the snd/rcv buffer?
//        tcpBootstrap.setOption(SO_SNDBUF, 1048576);
//        tcpBootstrap.setOption(SO_RCVBUF, 1048576);

        // setup the thread group to easily ID what the following threads belong to (and their spawned threads...)
        SecurityManager s = System.getSecurityManager();
        ThreadGroup nettyGroup = new ThreadGroup(s != null ? s.getThreadGroup() : Thread.currentThread().getThreadGroup(), name + " (Netty)");


        // always use local channels on the server.
        {
            EventLoopGroup boss;
            EventLoopGroup worker;

            if (localBootstrap != null) {
                boss = new DefaultEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(name + "-boss-LOCAL", nettyGroup));
                worker = new DefaultEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(name + "-worker-LOCAL", nettyGroup));

                localBootstrap.group(boss, worker)
                              .channel(LocalServerChannel.class)
                              .localAddress(new LocalAddress(this.localChannelName))
                              .childHandler(new RegistrationLocalHandlerServer(name, registrationWrapper));

                manageForShutdown(boss);
                manageForShutdown(worker);
            }
        }

        if (tcpBootstrap != null) {
            EventLoopGroup boss;
            EventLoopGroup worker;

            if (isAndroid) {
                // android ONLY supports OIO (not NIO)
                boss = new OioEventLoopGroup(0, new NamedThreadFactory(name + "-boss-TCP", nettyGroup));
                worker = new OioEventLoopGroup(0, new NamedThreadFactory(name + "-worker-TCP", nettyGroup));
                tcpBootstrap.channel(OioServerSocketChannel.class);
            } else {
                boss = new NioEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(name + "-boss-TCP", nettyGroup));
                worker = new NioEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(name + "-worker-TCP", nettyGroup));
                tcpBootstrap.channel(NioServerSocketChannel.class);
            }

            manageForShutdown(boss);
            manageForShutdown(worker);

            tcpBootstrap.group(boss, worker)
                        .option(ChannelOption.SO_BACKLOG, backlogConnectionCount)
                        .childHandler(new RegistrationRemoteHandlerServerTCP(name, registrationWrapper, serializationManager));

            if (options.host != null) {
                tcpBootstrap.localAddress(options.host, tcpPort);
            } else {
                tcpBootstrap.localAddress(tcpPort);
            }

            // android screws up on this!!
            tcpBootstrap.option(ChannelOption.TCP_NODELAY, !isAndroid);
            tcpBootstrap.childOption(ChannelOption.TCP_NODELAY, !isAndroid);

            tcpBootstrap.childOption(ChannelOption.SO_KEEPALIVE, true);
        }


        if (udpBootstrap != null) {
            EventLoopGroup worker;

            if (isAndroid) {
                // android ONLY supports OIO (not NIO)
                worker = new OioEventLoopGroup(0, new NamedThreadFactory(name + "-worker-UDP", nettyGroup));
                udpBootstrap.channel(OioDatagramChannel.class);
            } else {
                worker = new NioEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(name + "-worker-UDP", nettyGroup));
                udpBootstrap.channel(NioDatagramChannel.class);
            }

            manageForShutdown(worker);

            udpBootstrap.group(worker)
                         // not binding to specific address, since it's driven by TCP, and that can be bound to a specific address
                        .localAddress(udpPort) // if you bind to a specific interface, Linux will be unable to receive broadcast packets!
                        .handler(new RegistrationRemoteHandlerServerUDP(name, registrationWrapper, serializationManager));


            // Enable to READ from MULTICAST data (ie, 192.168.1.0)
            // in order to WRITE: write as normal, just make sure it ends in .255
            // in order to LISTEN:
            //    InetAddress group = InetAddress.getByName("203.0.113.0");
            //    socket.joinGroup(group);
            // THEN once done
            //    socket.leaveGroup(group), close the socket
            // Enable to WRITE to MULTICAST data (ie, 192.168.1.0)
            udpBootstrap.option(ChannelOption.SO_BROADCAST, false);
            udpBootstrap.option(ChannelOption.SO_SNDBUF, udpMaxSize);
        }


        if (udtBootstrap != null) {
            EventLoopGroup boss;
            EventLoopGroup worker;

            // all of this must be proxied to another class, so THIS class doesn't have unmet dependencies.
            // Annoying and abusing the classloader, but it works well.
            boss = UdtEndpointProxy.getServerBoss(DEFAULT_THREAD_POOL_SIZE, name, nettyGroup);
            worker = UdtEndpointProxy.getServerWorker(DEFAULT_THREAD_POOL_SIZE, name, nettyGroup);

            UdtEndpointProxy.setChannelFactory(udtBootstrap);
            udtBootstrap.group(boss, worker)
                        .option(ChannelOption.SO_BACKLOG, backlogConnectionCount)
                         // not binding to specific address, since it's driven by TCP, and that can be bound to a specific address
                        .localAddress(udtPort)
                        .childHandler(new RegistrationRemoteHandlerServerUDT(name, registrationWrapper, serializationManager));

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
        synchronized (shutdownInProgress) {
        }


        // Note: The bootstraps will be accessed ONE AT A TIME, in this order!

        ChannelFuture future;

        // LOCAL
        if (localBootstrap != null) {
            try {
                future = localBootstrap.bind();
                future.await();
            } catch (InterruptedException e) {
                logger.error("Could not bind to LOCAL address on the server.", e.getCause());
                stop();
                throw new IllegalArgumentException();
            }

            if (!future.isSuccess()) {
                logger.error("Could not bind to LOCAL address on the server.", future.cause());
                stop();
                throw new IllegalArgumentException();
            }

            logger.info("Listening on LOCAL address: '{}'", localChannelName);
            manageForShutdown(future);
        }


        // TCP
        if (tcpBootstrap != null) {
            // Wait until the connection attempt succeeds or fails.
            try {
                future = tcpBootstrap.bind();
                future.await();
            } catch (Exception e) {
                logger.error("Could not bind to TCP port {} on the server.", tcpPort, e.getCause());
                stop();
                throw new IllegalArgumentException("Could not bind to TCP port");
            }

            if (!future.isSuccess()) {
                logger.error("Could not bind to TCP port {} on the server.", tcpPort , future.cause());
                stop();
                throw new IllegalArgumentException("Could not bind to TCP port");
            }

            logger.info("Listening on TCP port: {}", tcpPort);
            manageForShutdown(future);
        }

        // UDP
        if (udpBootstrap != null) {
            // Wait until the connection attempt succeeds or fails.
            try {
                future = udpBootstrap.bind();
                future.await();
            } catch (Exception e) {
                logger.error("Could not bind to UDP port {} on the server.", udpPort, e.getCause());
                stop();
                throw new IllegalArgumentException("Could not bind to UDP port");
            }

            if (!future.isSuccess()) {
                logger.error("Could not bind to UDP port {} on the server.", udpPort, future.cause());
                stop();
                throw new IllegalArgumentException("Could not bind to UDP port");
            }

            logger.info("Listening on UDP port: {}", udpPort);
            manageForShutdown(future);
        }

        // UDT
        if (udtBootstrap != null) {
            // Wait until the connection attempt succeeds or fails.
            try {
                future = udtBootstrap.bind();
                future.await();
            } catch (Exception e) {
                logger.error("Could not bind to UDT port {} on the server.", udtPort, e.getCause());
                stop();
                throw new IllegalArgumentException("Could not bind to UDT port");
            }

            if (!future.isSuccess()) {
                logger.error("Could not bind to UDT port {} on the server.", udtPort, future.cause());
                stop();
                throw new IllegalArgumentException("Could not bind to UDT port");
            }

            logger.info("Listening on UDT port: {}", udtPort);
            manageForShutdown(future);
        }

        // we now BLOCK until the stop method is called.
        // if we want to continue running code in the server, bind should be called in a separate, non-daemon thread.
        waitForStop(blockUntilTerminate);
    }
}
