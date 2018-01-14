/*
 * Copyright 2010 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.network;

import java.io.IOException;
import java.net.Socket;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPointServer;
import dorkbox.network.connection.registration.local.RegistrationLocalHandlerServer;
import dorkbox.network.connection.registration.remote.RegistrationRemoteHandlerServerTCP;
import dorkbox.network.connection.registration.remote.RegistrationRemoteHandlerServerUDP;
import dorkbox.util.NamedThreadFactory;
import dorkbox.util.OS;
import dorkbox.util.Property;
import dorkbox.util.exceptions.InitializationException;
import dorkbox.util.exceptions.SecurityException;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.kqueue.KQueueDatagramChannel;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.oio.OioDatagramChannel;
import io.netty.channel.socket.oio.OioServerSocketChannel;
import io.netty.channel.unix.UnixChannelOption;

/**
 * The server can only be accessed in an ASYNC manner. This means that the server can only be used in RESPONSE to events. If you access the
 * server OUTSIDE of events, you will get inaccurate information from the server (such as getConnections())
 * <p/>
 * To put it bluntly, ONLY have the server do work inside of a listener!
 */
public
class Server<C extends Connection> extends EndPointServer<C> {


    /**
     * Gets the version number.
     */
    public static
    String getVersion() {
        return "2.5";
    }

    /**
     * The maximum queue length for incoming connection indications (a request to connect). If a connection indication arrives when the
     * queue is full, the connection is refused.
     */
    @Property
    public static int backlogConnectionCount = 50;

    private final ServerBootstrap localBootstrap;
    private final ServerBootstrap tcpBootstrap;
    private final Bootstrap udpBootstrap;

    private final int tcpPort;
    private final int udpPort;

    private final String localChannelName;
    private final String hostName;

    private volatile boolean isRunning = false;


    /**
     * Starts a LOCAL <b>only</b> server, with the default serialization scheme.
     */
    public
    Server() throws InitializationException, SecurityException, IOException {
        this(Configuration.localOnly());
    }

    /**
     * Convenience method to starts a server with the specified Connection Options
     */
    @SuppressWarnings("AutoBoxing")
    public
    Server(Configuration config) throws InitializationException, SecurityException, IOException {
        // watch-out for serialization... it can be NULL incoming. The EndPoint (superclass) sets it, if null, so
        // you have to make sure to use this.serialization
        super(config);

        tcpPort = config.tcpPort;
        udpPort = config.udpPort;

        localChannelName = config.localChannelName;

        if (config.host == null) {
            // we set this to "0.0.0.0" so that it is clear that we are trying to bind to that address.
            hostName = "0.0.0.0";

            // make it clear that this is what we do (configuration wise) so that variable examination is consistent
            config.host = hostName;
        }
        else {
            hostName = config.host;
        }


        if (localChannelName != null) {
            localBootstrap = new ServerBootstrap();
        }
        else {
            localBootstrap = null;
        }

        if (tcpPort > 0) {
            tcpBootstrap = new ServerBootstrap();
        }
        else {
            tcpBootstrap = null;
        }

        if (udpPort > 0) {
            udpBootstrap = new Bootstrap();
        }
        else {
            udpBootstrap = null;
        }


        String threadName = Server.class.getSimpleName();


        final EventLoopGroup boss;
        final EventLoopGroup worker;

        if (OS.isAndroid()) {
            // android ONLY supports OIO (not NIO)
            boss = new OioEventLoopGroup(0, new NamedThreadFactory(threadName + "-boss", threadGroup));
            worker = new OioEventLoopGroup(0, new NamedThreadFactory(threadName, threadGroup));
        }
        else if (OS.isLinux()) {
            // JNI network stack is MUCH faster (but only on linux)
            boss = new EpollEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(threadName + "-boss", threadGroup));
            worker = new EpollEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(threadName, threadGroup));
        }
        else {
            boss = new NioEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(threadName + "-boss", threadGroup));
            worker = new NioEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(threadName, threadGroup));
        }

        manageForShutdown(boss);
        manageForShutdown(worker);

        // always use local channels on the server.
        {
            EventLoopGroup localBoss;
            EventLoopGroup localWorker;

            if (localBootstrap != null) {
                localBoss = new DefaultEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(threadName + "-boss-LOCAL",
                                                                                                       threadGroup));
                localWorker = new DefaultEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(threadName + "-worker-LOCAL",
                                                                                                         threadGroup));

                localBootstrap.group(localBoss, localWorker)
                              .channel(LocalServerChannel.class)
                              .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                              .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(WRITE_BUFF_LOW, WRITE_BUFF_HIGH))
                              .localAddress(new LocalAddress(localChannelName))
                              .childHandler(new RegistrationLocalHandlerServer<C>(threadName, registrationWrapper));

                manageForShutdown(localBoss);
                manageForShutdown(localWorker);
            }
        }

        if (tcpBootstrap != null) {
            if (OS.isAndroid()) {
                // android ONLY supports OIO (not NIO)
                tcpBootstrap.channel(OioServerSocketChannel.class);
            }
            else if (OS.isLinux()) {
                // JNI network stack is MUCH faster (but only on linux)
                tcpBootstrap.channel(EpollServerSocketChannel.class);
            }
            else if (OS.isMacOsX()) {
                // JNI network stack is MUCH faster (but only on macosx)
                tcpBootstrap.channel(KQueueServerSocketChannel.class);
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
                        .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(WRITE_BUFF_LOW, WRITE_BUFF_HIGH))
                        .childHandler(new RegistrationRemoteHandlerServerTCP<C>(threadName,
                                                                                registrationWrapper,
                                                                                serializationManager));

            // have to check options.host for null. we don't bind to 0.0.0.0, we bind to "null" to get the "any" address!
            if (hostName.equals("0.0.0.0")) {
                tcpBootstrap.localAddress(hostName, tcpPort);
            }
            else {
                tcpBootstrap.localAddress(tcpPort);
            }


            // android screws up on this!!
            tcpBootstrap.option(ChannelOption.TCP_NODELAY, !OS.isAndroid())
                        .childOption(ChannelOption.TCP_NODELAY, !OS.isAndroid());
        }


        if (udpBootstrap != null) {
            if (OS.isAndroid()) {
                // android ONLY supports OIO (not NIO)
                udpBootstrap.channel(OioDatagramChannel.class)
                            .option(UnixChannelOption.SO_REUSEPORT, true);
            }
            else if (OS.isLinux()) {
                // JNI network stack is MUCH faster (but only on linux)
                udpBootstrap.channel(EpollDatagramChannel.class)
                            .option(UnixChannelOption.SO_REUSEPORT, true);
            }
            else if (OS.isMacOsX()) {
                // JNI network stack is MUCH faster (but only on macosx)
                udpBootstrap.channel(KQueueDatagramChannel.class)
                            .option(UnixChannelOption.SO_REUSEPORT, true);
            }
            else {
                // windows
                udpBootstrap.channel(NioDatagramChannel.class);
            }

            udpBootstrap.group(worker)
                        .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                        .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(WRITE_BUFF_LOW, WRITE_BUFF_HIGH))

                        // not binding to specific address, since it's driven by TCP, and that can be bound to a specific address
                        .localAddress(udpPort) // if you bind to a specific interface, Linux will be unable to receive broadcast packets!
                        .handler(new RegistrationRemoteHandlerServerUDP<C>(threadName,
                                                                           registrationWrapper,
                                                                           serializationManager));


            // Enable to READ from MULTICAST data (ie, 192.168.1.0)
            // in order to WRITE: write as normal, just make sure it ends in .255
            // in order to LISTEN:
            //    InetAddress group = InetAddress.getByName("203.0.113.0");
            //    socket.joinGroup(group);
            // THEN once done
            //    socket.leaveGroup(group), close the socket
            // Enable to WRITE to MULTICAST data (ie, 192.168.1.0)
            udpBootstrap.option(ChannelOption.SO_BROADCAST, false)
                        .option(ChannelOption.SO_SNDBUF, udpMaxSize);
        }
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
     * @param blockUntilTerminate
     *                 will BLOCK until the server stop method is called, and if you want to continue running code after this method
     *                 invocation, bind should be called in a separate, non-daemon thread - or with false as the parameter.
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

        // LOCAL
        if (localBootstrap != null) {
            try {
                future = localBootstrap.bind();
                future.await();
            } catch (InterruptedException e) {
                String errorMessage = stopWithErrorMessage(logger, "Could not bind to LOCAL address on the server.", e);
                throw new IllegalArgumentException(errorMessage);
            }

            if (!future.isSuccess()) {
                String errorMessage = stopWithErrorMessage(logger, "Could not bind to LOCAL address on the server.", future.cause());
                throw new IllegalArgumentException(errorMessage);
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
                String errorMessage = stopWithErrorMessage(logger,
                                                           "Could not bind to address " + hostName + " TCP port " + tcpPort +
                                                           " on the server.",
                                                           e);
                throw new IllegalArgumentException(errorMessage);
            }

            if (!future.isSuccess()) {
                String errorMessage = stopWithErrorMessage(logger,
                                                           "Could not bind to address " + hostName + " TCP port " + tcpPort +
                                                           " on the server.",
                                                           future.cause());
                throw new IllegalArgumentException(errorMessage);
            }

            logger.info("Listening on address {} at TCP port: {}", hostName, tcpPort);
            manageForShutdown(future);
        }

        // UDP
        if (udpBootstrap != null) {
            // Wait until the connection attempt succeeds or fails.
            try {
                future = udpBootstrap.bind();
                future.await();
            } catch (Exception e) {
                String errorMessage = stopWithErrorMessage(logger,
                                                           "Could not bind to address " + hostName + " UDP port " + udpPort +
                                                           " on the server.",
                                                           e);
                throw new IllegalArgumentException(errorMessage);
            }

            if (!future.isSuccess()) {
                String errorMessage = stopWithErrorMessage(logger,
                                                           "Could not bind to address " + hostName + " UDP port " + udpPort +
                                                           " on the server.",
                                                           future.cause());
                throw new IllegalArgumentException(errorMessage);
            }

            logger.info("Listening on address {} at UDP port: {}", hostName, udpPort);
            manageForShutdown(future);
        }

        isRunning = true;

        // we now BLOCK until the stop method is called.
        // if we want to continue running code in the server, bind should be called in a separate, non-daemon thread.
        if (blockUntilTerminate) {
            waitForShutdown();
        }
    }

    @Override
    protected
    void stopExtraActions() {
        isRunning = false;
    }

    /**
     * @return true if this server has successfully bound to an IP address and is running
     */
    public
    boolean isRunning() {
        return isRunning;
    }

    /**
     * Checks to see if different server (using the specified configuration) is already running. This will check across JVMs by checking the
     * network socket directly, and assumes that if the port is in use and answers, then the server is "running". This does not try to
     * authenticate or validate the connection.
     * <p>
     * This does not check local-channels (which are intra-JVM only) or UDP connections.
     * </p>
     *
     * @return true if the configuration matches and can connect (but not verify) to the TCP control socket.
     */
    public static
    boolean isRunning(Configuration config) {
        String host = config.host;

        // for us, we want a "null" host to connect to the "any" interface.
        if (host == null) {
            host = "0.0.0.0";
        }

        if (config.tcpPort == 0) {
            return false;
        }

        Socket sock = null;

        // since we check the socket, if we cannot connect to a socket, then we're done.
        try {
            sock = new Socket(host, config.tcpPort);
            // if we can connect to the socket, it means that we are already running.
            return sock.isConnected();
        } catch (Exception ignored) {
            if (sock != null) {
                try {
                    sock.close();
                } catch (IOException ignored2) {
                }
            }
        }

        return false;
    }
}

