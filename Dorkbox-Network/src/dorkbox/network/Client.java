package dorkbox.network;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.socket.oio.OioDatagramChannel;
import io.netty.channel.socket.oio.OioSocketChannel;
import io.netty.util.internal.PlatformDependent;

import java.net.InetSocketAddress;
import java.util.LinkedList;
import java.util.List;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.ConnectionBridge;
import dorkbox.network.connection.ConnectionBridgeFlushAlways;
import dorkbox.network.connection.EndPointClient;
import dorkbox.network.connection.idle.IdleBridge;
import dorkbox.network.connection.idle.IdleSender;
import dorkbox.network.connection.ping.Ping;
import dorkbox.network.connection.registration.local.RegistrationLocalHandlerClient;
import dorkbox.network.connection.registration.remote.RegistrationRemoteHandlerClientTCP;
import dorkbox.network.connection.registration.remote.RegistrationRemoteHandlerClientUDP;
import dorkbox.network.connection.registration.remote.RegistrationRemoteHandlerClientUDT;
import dorkbox.network.util.InitializationException;
import dorkbox.network.util.NamedThreadFactory;
import dorkbox.network.util.SecurityException;
import dorkbox.network.util.SerializationManager;
import dorkbox.network.util.udt.UdtEndpointProxy;

/**
 * The client is both SYNC and ASYNC, meaning that once the client is connected to the server, you can access it however you want.
 * <p>
 * Another way to put this: The client (like the server) can respond to EVENTS (ie, listeners), but you can also use it DIRECTLY, for
 * example, send data to the server on keyboard input. This is because the client will BLOCK the calling thread until it's ready.
 */
public class Client extends EndPointClient {
    private List<BootstrapWrapper> bootstraps = new LinkedList<BootstrapWrapper>();

    private volatile boolean registrationInProgress = false;

    private volatile int connectionTimeout = 5000; // default

    /**
     * Starts a LOCAL <b>only</b> client, with the default local channel name and serialization scheme
     */
    public Client() throws InitializationException, SecurityException {
        this(new ConnectionOptions(LOCAL_CHANNEL));
    }

    /**
     * Starts a TCP & UDP client (or a LOCAL client), with the specified serialization scheme
     */
    public Client(String host, int tcpPort, int udpPort, int udtPort, String localChannelName, SerializationManager serializationManager)
            throws InitializationException, SecurityException {
        this(new ConnectionOptions(host, tcpPort, udpPort, udtPort, localChannelName, serializationManager));
    }

    /**
     * Starts a REMOTE <b>only</b> client, which will connect to the specified host using the specified Connections Options
     */
    public Client(ConnectionOptions options) throws InitializationException, SecurityException {
        super("Client", options);

        if (options.localChannelName != null && (options.tcpPort > 0 || options.udpPort > 0 || options.host != null) ||
            options.localChannelName == null && (options.tcpPort == 0 || options.udpPort == 0 || options.host == null)
           ) {
            String msg = this.name + " Local channel use and TCP/UDP use are MUTUALLY exclusive. Unable to determine intent.";
            this.logger.error(msg);
            throw new IllegalArgumentException(msg);
        }

        boolean isAndroid = PlatformDependent.isAndroid();

        if (isAndroid && options.udtPort > 0) {
            // Android does not support UDT.
            this.logger.info("Android does not support UDT.");
            options.udtPort = -1;
        }


//      tcpBootstrap.setOption(SO_SNDBUF, 1048576);
//      tcpBootstrap.setOption(SO_RCVBUF, 1048576);

        // setup the thread group to easily ID what the following threads belong to (and their spawned threads...)
        SecurityManager s = System.getSecurityManager();
        ThreadGroup nettyGroup = new ThreadGroup(s != null ? s.getThreadGroup() : Thread.currentThread().getThreadGroup(), this.name + " (Netty)");

        if (options.localChannelName != null && options.tcpPort < 0 && options.udpPort < 0 && options.udtPort < 0) {
            // no networked bootstraps. LOCAL connection only
            Bootstrap localBootstrap = new Bootstrap();
            this.bootstraps.add(new BootstrapWrapper("LOCAL", -1, localBootstrap));

            EventLoopGroup boss;

            boss = new DefaultEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(this.name + "-LOCAL", nettyGroup));

            localBootstrap.group(boss)
                          .channel(LocalChannel.class)
                          .remoteAddress(new LocalAddress(options.localChannelName))
                          .handler(new RegistrationLocalHandlerClient(this.name, this.registrationWrapper));

            manageForShutdown(boss);
        }
        else {
            if (options.tcpPort > 0) {
                Bootstrap tcpBootstrap = new Bootstrap();
                this.bootstraps.add(new BootstrapWrapper("TCP", options.tcpPort, tcpBootstrap));

                EventLoopGroup boss;

                if (isAndroid) {
                    // android ONLY supports OIO (not NIO)
                    boss = new OioEventLoopGroup(0, new NamedThreadFactory(this.name + "-TCP", nettyGroup));
                    tcpBootstrap.channel(OioSocketChannel.class);
                } else {
                    boss = new NioEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(this.name + "-TCP", nettyGroup));
                    tcpBootstrap.channel(NioSocketChannel.class);
                }

                tcpBootstrap.group(boss)
                            .remoteAddress(options.host, options.tcpPort)
                            .handler(new RegistrationRemoteHandlerClientTCP(this.name, this.registrationWrapper, this.serializationManager));


                manageForShutdown(boss);

                // android screws up on this!!
                tcpBootstrap.option(ChannelOption.TCP_NODELAY, !isAndroid);
                tcpBootstrap.option(ChannelOption.SO_KEEPALIVE, true);
            }


            if (options.udpPort > 0) {
                Bootstrap udpBootstrap = new Bootstrap();
                this.bootstraps.add(new BootstrapWrapper("UDP", options.udpPort, udpBootstrap));

                EventLoopGroup boss;

                if (isAndroid) {
                    // android ONLY supports OIO (not NIO)
                    boss = new OioEventLoopGroup(0, new NamedThreadFactory(this.name + "-UDP", nettyGroup));
                    udpBootstrap.channel(OioDatagramChannel.class);
                } else {
                    boss = new NioEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(this.name + "-UDP", nettyGroup));
                    udpBootstrap.channel(NioDatagramChannel.class);
                }

                udpBootstrap.group(boss)
                            .localAddress(new InetSocketAddress(0))
                            .remoteAddress(new InetSocketAddress(options.host, options.udpPort))
                            .handler(new RegistrationRemoteHandlerClientUDP(this.name, this.registrationWrapper, this.serializationManager));

                manageForShutdown(boss);

                // Enable to READ and WRITE MULTICAST data (ie, 192.168.1.0)
                // in order to WRITE: write as normal, just make sure it ends in .255
                // in order to LISTEN:
                //    InetAddress group = InetAddress.getByName("203.0.113.0");
                //    NioDatagramChannel.joinGroup(group);
                // THEN once done
                //    NioDatagramChannel.leaveGroup(group), close the socket
                udpBootstrap.option(ChannelOption.SO_BROADCAST, false);
                udpBootstrap.option(ChannelOption.SO_SNDBUF, udpMaxSize);
            }


            if (options.udtPort > 0) {
                // check to see if we have UDT available!
                boolean udtAvailable = false;
                try {
                    Class.forName("com.barchart.udt.nio.SelectorProviderUDT");
                    udtAvailable = true;
                } catch (Throwable e) {
                    this.logger.error("Requested a UDT connection on port {}, but the barchart UDT libraries are not loaded.", options.udtPort);
                }

                if (udtAvailable) {
                    // all of this must be proxied to another class, so THIS class doesn't have unmet dependencies.
                    // Annoying and abusing the classloader, but it works well.
                    Bootstrap udtBootstrap = new Bootstrap();
                    this.bootstraps.add(new BootstrapWrapper("UDT", options.udtPort, udtBootstrap));

                    EventLoopGroup boss;

                    boss = UdtEndpointProxy.getClientWorker(DEFAULT_THREAD_POOL_SIZE, this.name, nettyGroup);

                    UdtEndpointProxy.setChannelFactory(udtBootstrap);

                    udtBootstrap.group(boss)
                                .remoteAddress(options.host, options.udtPort)
                                .handler(new RegistrationRemoteHandlerClientUDT(this.name, this.registrationWrapper, this.serializationManager));

                    manageForShutdown(boss);
                }
            }
        }

        // this thread will prevent the application from closing, since the JVM only exits when all non-daemon threads have ended.
        // We will wait until Client.stop() is called before exiting.
        // NOTE: if we are the webserver, then this method will be called for EVERY web connection made
//        Thread exitThread = new Thread(new Runnable() {
//            @Override
//            public void run() {
//                waitForStop();
//            }
//        });
//        exitThread.setDaemon(false);
//        exitThread.setName("Exit Monitor (Client)");
//        exitThread.start();
    }

    /**
     * Allows the client to reconnect to the last connected server
     */
    public void reconnect() {
        reconnect(this.connectionTimeout);
    }

    /**
     * Allows the client to reconnect to the last connected server
     */
    public void reconnect(int connectionTimeout) {
        // close out all old connections
        close();

        connect(connectionTimeout);
    }


    /**
     * will attempt to connect to the server, with a 30 second timeout.
     *
     * @param connectionTimeout wait for x milliseconds. 0 will wait indefinitely
     */
    public void connect() {
        connect(30000);
    }

    /**
     * will attempt to connect to the server, and will the specified timeout.
     *
     * @param connectionTimeout wait for x milliseconds. 0 will wait indefinitely
     */
    public void connect(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;

        // make sure we are not trying to connect during a close or stop event.
        // This will wait until we have finished shutting down.
        synchronized (this.shutdownInProgress) {
        }

        // have to BLOCK here, because we don't want sendTCP() called before registration is complete
        synchronized (this.registrationLock) {
            this.registrationInProgress = true;

            // we will only do a local channel when NOT doing TCP/UDP channels. This is EXCLUSIVE. (XOR)
            int size = this.bootstraps.size();
            for (int i=0;i<size;i++) {
                this.registrationComplete = i == size-1;
                BootstrapWrapper bootstrapWrapper = this.bootstraps.get(i);
                ChannelFuture future;

                if (connectionTimeout != 0) {
                    // must be before connect
                    bootstrapWrapper.bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectionTimeout);
                }

                try {
                    // UDP : When this is CONNECT on a udp socket will ONLY accept UDP traffic from the remote address (ip/port combo).
                    //       If the reply isn't from the correct port, then the other end will receive a "Port Unreachable" exception.

                    future = bootstrapWrapper.bootstrap.connect();
                    future.await();

                } catch (Exception e) {
                    if (this.logger.isDebugEnabled()) {
                        this.logger.error("Could not connect to the {} server on port {}.", bootstrapWrapper.type, bootstrapWrapper.port, e.getCause());
                    } else {
                        this.logger.error("Could not connect to the {} server{}.", bootstrapWrapper.type, bootstrapWrapper.port);
                    }

                    this.registrationInProgress = false;
                    stop();
                    return;
                }

                if (!future.isSuccess()) {
                    if (this.logger.isDebugEnabled()) {
                        this.logger.error("Could not connect to the {} server.", bootstrapWrapper.type, future.cause());
                    } else {
                        this.logger.error("Could not connect to the {} server.", bootstrapWrapper.type);
                    }

                    this.registrationInProgress = false;
                    stop();
                    return;
                }

                this.logger.trace("Waiting for registration from server.");
                manageForShutdown(future);

                // WAIT for the next one to complete.
                try {
                    this.registrationLock.wait();
                } catch (InterruptedException e) {
                }
            }

            this.registrationInProgress = false;
        }
    }


    /**
     * Expose methods to send objects to a destination.
     * <p>
     * This returns a bridge that will flush after EVERY send! This is because sending data can occur on the client, outside
     * of the normal eventloop patterns, and it is confusing to the user to have to manually flush the channel each time.
     */
    public ConnectionBridge send() {
        return new ConnectionBridgeFlushAlways(this.connectionManager.getConnection0().send());
    }

    /**
     * Expose methods to send objects to a destination when the connection has become idle.
     */
    public IdleBridge sendOnIdle(IdleSender<?, ?> sender) {
        return this.connectionManager.getConnection0().sendOnIdle(sender);
    }

    /**
     * Expose methods to send objects to a destination when the connection has become idle.
     */
    public IdleBridge sendOnIdle(Object message) {
        return this.connectionManager.getConnection0().sendOnIdle(message);
    }

    /**
     * Returns a future that will have the last calculated return trip time.
     */
    public Ping ping() {
        return this.connectionManager.getConnection0().send().ping();
    }

    /**
     * Fetches the connection used by the client.
     * <p>
     * Make <b>sure</b> that you only call this <b>after</b> the client connects!
     * <p>
     * This is preferred to {@link getConnections}, as it properly does some error checking
     */
    public Connection getConnection() {
        return this.connectionManager.getConnection0();
    }

    /**
     * Closes all connections ONLY (keeps the server/client running)
     */
    @Override
    public void close() {
        // in case a different thread is blocked waiting for registration.
        synchronized (this.registrationLock) {
            if (this.registrationInProgress) {
                try {
                    this.registrationLock.wait();
                } catch (InterruptedException e) {
                }
            }

            // inside the sync block, because we DON'T want to allow a connect WHILE close is happening! Since connect is also
            // in the sync bloc, we prevent it from happening.
            super.close();
        }
    }
}
