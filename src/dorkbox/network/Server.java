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

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPointServer;
import dorkbox.network.connection.registration.local.RegistrationLocalHandlerServer;
import dorkbox.network.connection.registration.remote.RegistrationRemoteHandlerServerTCP;
import dorkbox.network.connection.registration.remote.RegistrationRemoteHandlerServerUDP;
import dorkbox.network.connection.registration.remote.RegistrationRemoteHandlerServerUDT;
import dorkbox.network.util.udt.UdtEndpointProxy;
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

import java.io.IOException;

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
        return "1.22";
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
    private final ServerBootstrap udtBootstrap;

    private final int tcpPort;
    private final int udpPort;
    private final int udtPort;

    private final String localChannelName;

    /**
     * Starts a LOCAL <b>only</b> server, with the default serialization scheme
     */
    public
    Server() throws InitializationException, SecurityException, IOException {
        this(new Configuration(LOCAL_CHANNEL));
    }

    /**
     * Convenience method to starts a server with the specified Connection Options
     */
    @SuppressWarnings("AutoBoxing")
    public
    Server(Configuration options) throws InitializationException, SecurityException, IOException {
        // watch-out for serialization... it can be NULL incoming. The EndPoint (superclass) sets it, if null, so
        // you have to make sure to use this.serialization
        super(options);

        Logger logger2 = logger;
        if (OS.isAndroid() && options.udtPort > 0) {
            // Android does not support UDT.
            if (logger2.isInfoEnabled()) {
                logger2.info("Android does not support UDT.");
            }
            options.udtPort = -1;
        }

        tcpPort = options.tcpPort;
        udpPort = options.udpPort;
        udtPort = options.udtPort;

        localChannelName = options.localChannelName;

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

        if (udtPort > 0) {
            // check to see if we have UDT available!
            boolean udtAvailable = false;
            try {
                Class.forName("com.barchart.udt.nio.SelectorProviderUDT");
                udtAvailable = true;
            } catch (Throwable e) {
                logger2.error("Requested a UDT service on port {}, but the barchart UDT libraries are not loaded.", udtPort);
            }

            if (udtAvailable) {
                udtBootstrap = new ServerBootstrap();
            }
            else {
                udtBootstrap = null;
            }
        }
        else {
            udtBootstrap = null;
        }

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
                              .childOption(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, WRITE_BUFF_HIGH)
                              .childOption(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, WRITE_BUFF_LOW)
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
                        .childOption(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, WRITE_BUFF_HIGH)
                        .childOption(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, WRITE_BUFF_LOW)
                        .childHandler(new RegistrationRemoteHandlerServerTCP<C>(threadName,
                                                                                registrationWrapper,
                                                                                serializationManager));

            if (options.host != null) {
                tcpBootstrap.localAddress(options.host, tcpPort);
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
                        .option(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, WRITE_BUFF_HIGH)
                        .option(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, WRITE_BUFF_LOW)

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


        if (udtBootstrap != null) {
            EventLoopGroup udtBoss;
            EventLoopGroup udtWorker;

            // all of this must be proxied to another class, so THIS class doesn't have unmet dependencies.
            udtBoss = UdtEndpointProxy.getBoss(DEFAULT_THREAD_POOL_SIZE, threadName, threadGroup);
            udtWorker = UdtEndpointProxy.getWorker(DEFAULT_THREAD_POOL_SIZE, threadName, threadGroup);

            UdtEndpointProxy.setChannelFactory(udtBootstrap);
            udtBootstrap.group(udtBoss, udtWorker)
                        .option(ChannelOption.SO_BACKLOG, backlogConnectionCount)
                        .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                        .childOption(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, WRITE_BUFF_HIGH)
                        .childOption(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, WRITE_BUFF_LOW)
                        // not binding to specific address, since it's driven by TCP, and that can be bound to a specific address
                        .localAddress(udtPort)
                        .childHandler(new RegistrationRemoteHandlerServerUDT<C>(threadName,
                                                                                registrationWrapper,
                                                                                serializationManager));

            manageForShutdown(udtBoss);
            manageForShutdown(udtWorker);
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
        Logger logger2 = logger;
        if (localBootstrap != null) {
            try {
                future = localBootstrap.bind();
                future.await();
            } catch (InterruptedException e) {
                String errorMessage = stopWithErrorMessage(logger2, "Could not bind to LOCAL address on the server.", e);
                throw new IllegalArgumentException(errorMessage);
            }

            if (!future.isSuccess()) {
                String errorMessage = stopWithErrorMessage(logger2, "Could not bind to LOCAL address on the server.", future.cause());
                throw new IllegalArgumentException(errorMessage);
            }

            logger2.info("Listening on LOCAL address: '{}'", localChannelName);
            manageForShutdown(future);
        }


        // TCP
        if (tcpBootstrap != null) {
            // Wait until the connection attempt succeeds or fails.
            try {
                future = tcpBootstrap.bind();
                future.await();
            } catch (Exception e) {
                String errorMessage = stopWithErrorMessage(logger2, "Could not bind to TCP port " + tcpPort + " on the server.", e);
                throw new IllegalArgumentException(errorMessage);
            }

            if (!future.isSuccess()) {
                String errorMessage = stopWithErrorMessage(logger2,
                                                           "Could not bind to TCP port " + tcpPort + " on the server.",
                                                           future.cause());
                throw new IllegalArgumentException(errorMessage);
            }

            logger2.info("Listening on TCP port: {}", tcpPort);
            manageForShutdown(future);
        }

        // UDP
        if (udpBootstrap != null) {
            // Wait until the connection attempt succeeds or fails.
            try {
                future = udpBootstrap.bind();
                future.await();
            } catch (Exception e) {
                String errorMessage = stopWithErrorMessage(logger2, "Could not bind to UDP port " + udpPort + " on the server.", e);
                throw new IllegalArgumentException(errorMessage);
            }

            if (!future.isSuccess()) {
                String errorMessage = stopWithErrorMessage(logger2,
                                                           "Could not bind to UDP port " + udpPort + " on the server.",
                                                           future.cause());
                throw new IllegalArgumentException(errorMessage);
            }

            logger2.info("Listening on UDP port: {}", udpPort);
            manageForShutdown(future);
        }

        // UDT
        if (udtBootstrap != null) {
            // Wait until the connection attempt succeeds or fails.
            try {
                future = udtBootstrap.bind();
                future.await();
            } catch (Exception e) {
                String errorMessage = stopWithErrorMessage(logger2, "Could not bind to UDT port " + udtPort + " on the server.", e);
                throw new IllegalArgumentException(errorMessage);
            }

            if (!future.isSuccess()) {
                String errorMessage = stopWithErrorMessage(logger2,
                                                           "Could not bind to UDT port " + udtPort + " on the server.",
                                                           future.cause());
                throw new IllegalArgumentException(errorMessage);
            }

            logger2.info("Listening on UDT port: {}", udtPort);
            manageForShutdown(future);
        }

        // we now BLOCK until the stop method is called.
        // if we want to continue running code in the server, bind should be called in a separate, non-daemon thread.
        if (blockUntilTerminate) {
            waitForShutdown();
        }
    }
}

