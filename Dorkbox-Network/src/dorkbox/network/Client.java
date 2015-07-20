package dorkbox.network;

import dorkbox.network.connection.BootstrapWrapper;
import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.EndPointClient;
import dorkbox.network.connection.idle.IdleBridge;
import dorkbox.network.connection.idle.IdleSender;
import dorkbox.network.connection.registration.local.RegistrationLocalHandlerClient;
import dorkbox.network.connection.registration.remote.RegistrationRemoteHandlerClientTCP;
import dorkbox.network.connection.registration.remote.RegistrationRemoteHandlerClientUDP;
import dorkbox.network.connection.registration.remote.RegistrationRemoteHandlerClientUDT;
import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.SecurityException;
import dorkbox.network.util.udt.UdtEndpointProxy;
import dorkbox.util.NamedThreadFactory;
import dorkbox.util.OS;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.socket.oio.OioDatagramChannel;
import io.netty.channel.socket.oio.OioSocketChannel;
import io.netty.util.internal.PlatformDependent;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * The client is both SYNC and ASYNC. It starts off SYNC (blocks thread until it's done), then once it's connected to the server, it's ASYNC.
 */
public
class Client extends EndPointClient {

    private final Configuration options;

    /**
     * Starts a LOCAL <b>only</b> client, with the default local channel name and serialization scheme
     */
    public
    Client() throws InitializationException, SecurityException, IOException {
        this(new Configuration(LOCAL_CHANNEL));
    }

    /**
     * Starts a TCP & UDP client (or a LOCAL client), with the specified serialization scheme
     */
    public
    Client(String host, int tcpPort, int udpPort, int udtPort, String localChannelName)
                    throws InitializationException, SecurityException, IOException {
        this(new Configuration(host, tcpPort, udpPort, udtPort, localChannelName));
    }

    /**
     * Starts a REMOTE <b>only</b> client, which will connect to the specified host using the specified Connections Options
     */
    public
    Client(final Configuration options) throws InitializationException, SecurityException, IOException {
        super(options);
        this.options = options;

        String threadName = Client.class.getSimpleName();

        Logger logger2 = this.logger;
        if (options.localChannelName != null && (options.tcpPort > 0 || options.udpPort > 0 || options.host != null) ||
            options.localChannelName == null && (options.tcpPort == 0 || options.udpPort == 0 || options.host == null)) {
            String msg = threadName + " Local channel use and TCP/UDP use are MUTUALLY exclusive. Unable to determine intent.";
            logger2.error(msg);
            throw new IllegalArgumentException(msg);
        }

        boolean isAndroid = PlatformDependent.isAndroid();

        if (isAndroid && options.udtPort > 0) {
            // Android does not support UDT.
            if (logger2.isInfoEnabled()) {
                logger2.info("Android does not support UDT.");
            }
            options.udtPort = -1;
        }

//      tcpBootstrap.setOption(SO_SNDBUF, 1048576);
//      tcpBootstrap.setOption(SO_RCVBUF, 1048576);

        // setup the thread group to easily ID what the following threads belong to (and their spawned threads...)
        SecurityManager s = System.getSecurityManager();
        ThreadGroup nettyGroup = new ThreadGroup(s != null ?
                                                                 s.getThreadGroup() :
                                                                 Thread.currentThread()
                                                                       .getThreadGroup(), threadName + " (Netty)");

        if (options.localChannelName != null && options.tcpPort < 0 && options.udpPort < 0 && options.udtPort < 0) {
            // no networked bootstraps. LOCAL connection only
            Bootstrap localBootstrap = new Bootstrap();
            this.bootstraps.add(new BootstrapWrapper("LOCAL", -1, localBootstrap));

            EventLoopGroup boss;

            boss = new DefaultEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(threadName + "-LOCAL", nettyGroup));

            localBootstrap.group(boss)
                          .channel(LocalChannel.class)
                          .remoteAddress(new LocalAddress(options.localChannelName))
                          .handler(new RegistrationLocalHandlerClient(threadName, this.registrationWrapper));

            manageForShutdown(boss);
        }
        else {
            if (options.tcpPort > 0) {
                Bootstrap tcpBootstrap = new Bootstrap();
                this.bootstraps.add(new BootstrapWrapper("TCP", options.tcpPort, tcpBootstrap));

                EventLoopGroup boss;

                if (isAndroid) {
                    // android ONLY supports OIO (not NIO)
                    boss = new OioEventLoopGroup(0, new NamedThreadFactory(threadName + "-TCP", nettyGroup));
                    tcpBootstrap.channel(OioSocketChannel.class);
                }
                else if (OS.isLinux()) {
                    // JNI network stack is MUCH faster (but only on linux)
                    boss = new EpollEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(threadName + "-TCP", nettyGroup));
                    tcpBootstrap.channel(EpollSocketChannel.class);
                }
                else {
                    boss = new NioEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(threadName + "-TCP", nettyGroup));
                    tcpBootstrap.channel(NioSocketChannel.class);
                }

                tcpBootstrap.group(boss)
                            .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                            .remoteAddress(options.host, options.tcpPort)
                            .handler(new RegistrationRemoteHandlerClientTCP(threadName,
                                                                            this.registrationWrapper,
                                                                            this.serializationManager));


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
                    boss = new OioEventLoopGroup(0, new NamedThreadFactory(threadName + "-UDP", nettyGroup));
                    udpBootstrap.channel(OioDatagramChannel.class);
                }
                else if (OS.isLinux()) {
                    // JNI network stack is MUCH faster (but only on linux)
                    boss = new EpollEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(threadName + "-UDP", nettyGroup));
                    udpBootstrap.channel(EpollDatagramChannel.class);
                }
                else {
                    boss = new NioEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(threadName + "-UDP", nettyGroup));
                    udpBootstrap.channel(NioDatagramChannel.class);
                }

                udpBootstrap.group(boss)
                            .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                            .localAddress(new InetSocketAddress(0))
                            .remoteAddress(new InetSocketAddress(options.host, options.udpPort))
                            .handler(new RegistrationRemoteHandlerClientUDP(threadName,
                                                                            this.registrationWrapper,
                                                                            this.serializationManager));

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
                    logger2.error("Requested a UDT connection on port {}, but the barchart UDT libraries are not loaded.", options.udtPort);
                }

                if (udtAvailable) {
                    // all of this must be proxied to another class, so THIS class doesn't have unmet dependencies.
                    // Annoying and abusing the classloader, but it works well.
                    Bootstrap udtBootstrap = new Bootstrap();
                    this.bootstraps.add(new BootstrapWrapper("UDT", options.udtPort, udtBootstrap));

                    EventLoopGroup boss;

                    boss = UdtEndpointProxy.getClientWorker(DEFAULT_THREAD_POOL_SIZE, threadName, nettyGroup);

                    UdtEndpointProxy.setChannelFactory(udtBootstrap);

                    udtBootstrap.group(boss)
                                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                                .remoteAddress(options.host, options.udtPort)
                                .handler(new RegistrationRemoteHandlerClientUDT(threadName,
                                                                                this.registrationWrapper,
                                                                                this.serializationManager));

                    manageForShutdown(boss);
                }
            }
        }
    }

    /**
     * Allows the client to reconnect to the last connected server
     */
    public
    void reconnect() {
        reconnect(this.connectionTimeout);
    }

    /**
     * Allows the client to reconnect to the last connected server
     */
    public
    void reconnect(int connectionTimeout) {
        // close out all old connections
        close();

        connect(connectionTimeout);
    }


    /**
     * will attempt to connect to the server, with a 30 second timeout.
     */
    public
    void connect() {
        connect(30000);
    }

    /**
     * will attempt to connect to the server, and will the specified timeout.
     * <p/>
     * will BLOCK until completed
     *
     * @param connectionTimeout wait for x milliseconds. 0 will wait indefinitely
     */
    public
    void connect(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;

        // make sure we are not trying to connect during a close or stop event.
        // This will wait until we have finished shutting down.
        synchronized (this.shutdownInProgress) {
        }

        // have to start the registration process
        this.connectingBootstrap.set(0);
        registerNextProtocol();

        // have to BLOCK
        // don't want the client to run before registration is complete
        synchronized (this.registrationLock) {
            try {
                this.registrationLock.wait(connectionTimeout);
            } catch (InterruptedException e) {
                this.logger.error("Registration thread interrupted!");
            }
        }
    }

    /**
     * Expose methods to send objects to a destination when the connection has become idle.
     */
    public
    IdleBridge sendOnIdle(IdleSender<?, ?> sender) {
        return this.connectionManager.getConnection0()
                                     .sendOnIdle(sender);
    }

    /**
     * Expose methods to send objects to a destination when the connection has become idle.
     */
    public
    IdleBridge sendOnIdle(Object message) {
        return this.connectionManager.getConnection0()
                                     .sendOnIdle(message);
    }

    /**
     * Fetches the connection used by the client.
     * <p/>
     * Make <b>sure</b> that you only call this <b>after</b> the client connects!
     * <p/>
     * This is preferred to {@link EndPoint#getConnections()} getConnections()}, as it properly does some error checking
     */
    public
    Connection getConnection() {
        return this.connectionManager.getConnection0();
    }

    /**
     * Fetches the host (server) name for this client.
     */
    public
    String getHost() {
        return this.options.host;
    }

    /**
     * Closes all connections ONLY (keeps the server/client running).
     * <p/>
     * This is used, for example, when reconnecting to a server.
     */
    @Override
    public
    void close() {
        synchronized (this.registrationLock) {
            this.registrationLock.notify();
        }
    }
}
