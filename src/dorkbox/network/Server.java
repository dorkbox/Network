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

import static dorkbox.network.pipeline.ConnectionType.LOCAL;

import java.io.IOException;
import java.net.Socket;
import java.util.List;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.EndPointServer;
import dorkbox.network.connection.registration.local.RegistrationLocalHandlerServer;
import dorkbox.network.connection.registration.remote.RegistrationRemoteHandlerServerTCP;
import dorkbox.network.connection.registration.remote.RegistrationRemoteHandlerServerUDP;
import dorkbox.network.pipeline.discovery.BroadcastResponse;
import dorkbox.util.OS;
import dorkbox.util.Property;
import dorkbox.util.exceptions.SecurityException;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.bootstrap.SessionBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.kqueue.KQueueDatagramChannel;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.oio.OioDatagramChannel;
import io.netty.channel.socket.oio.OioServerSocketChannel;

/**
 * The server can only be accessed in an ASYNC manner. This means that the server can only be used in RESPONSE to events. If you access the
 * server OUTSIDE of events, you will get inaccurate information from the server (such as getConnections())
 * <p/>
 * To put it bluntly, ONLY have the server do work inside of a listener!
 */
public
class Server<C extends Connection> extends EndPointServer {


    /**
     * Gets the version number.
     */
    public static
    String getVersion() {
        return "2.16";
    }

    /**
     * The maximum queue length for incoming connection indications (a request to connect). If a connection indication arrives when the
     * queue is full, the connection is refused.
     */
    @Property
    public static int backlogConnectionCount = 50;

    private final ServerBootstrap localBootstrap;
    private final ServerBootstrap tcpBootstrap;
    private final SessionBootstrap udpBootstrap;

    private final int tcpPort;
    private final int udpPort;

    private final String localChannelName;
    private final String hostName;

    private volatile boolean isRunning = false;


    /**
     * Starts a LOCAL <b>only</b> server, with the default serialization scheme.
     */
    public
    Server() throws  SecurityException {
        this(Configuration.localOnly());
    }

    /**
     * Convenience method to starts a server with the specified Connection Options
     */
    @SuppressWarnings("AutoBoxing")
    public
    Server(Configuration config) throws SecurityException {
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
            // This is what allows us to have UDP behave "similar" to TCP, in that a session is established based on the port/ip of the
            // remote connection. This allows us to reuse channels and have "state" for a UDP connection that normally wouldn't exist.
            // Additionally, this is what responds to discovery broadcast packets
            udpBootstrap = new SessionBootstrap(tcpPort, udpPort);
        }
        else {
            udpBootstrap = null;
        }


        String threadName = Server.class.getSimpleName();

        // always use local channels on the server.
        if (localBootstrap != null) {
            localBootstrap.group(newEventLoop(LOCAL, 1, threadName + "-JVM-BOSS"),
                                 newEventLoop(LOCAL, 1, threadName ))
                          .channel(LocalServerChannel.class)
                          .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                          .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(WRITE_BUFF_LOW, WRITE_BUFF_HIGH))
                          .localAddress(new LocalAddress(localChannelName))
                          .childHandler(new RegistrationLocalHandlerServer(threadName, registrationWrapper));
        }


        EventLoopGroup workerEventLoop = null;
        if (tcpBootstrap != null || udpBootstrap != null) {
            workerEventLoop = newEventLoop(config.workerThreadPoolSize, threadName);
        }

        if (tcpBootstrap != null) {
            if (OS.isAndroid()) {
                // android ONLY supports OIO (not NIO)
                tcpBootstrap.channel(OioServerSocketChannel.class);
            }
            else if (OS.isLinux() && NativeLibrary.isAvailable()) {
                // epoll network stack is MUCH faster (but only on linux)
                tcpBootstrap.channel(EpollServerSocketChannel.class);
            }
            else if (OS.isMacOsX() && NativeLibrary.isAvailable()) {
                // KQueue network stack is MUCH faster (but only on macosx)
                tcpBootstrap.channel(KQueueServerSocketChannel.class);
            }
            else {
                tcpBootstrap.channel(NioServerSocketChannel.class);
            }

            // TODO: If we use netty for an HTTP server,
            // Beside the usual ChannelOptions the Native Transport allows to enable TCP_CORK which may come in handy if you implement a HTTP Server.

            tcpBootstrap.group(newEventLoop(1, threadName + "-TCP-BOSS"),
                               newEventLoop(1, threadName + "-TCP-REGISTRATION"))
                        .option(ChannelOption.SO_BACKLOG, backlogConnectionCount)
                        .option(ChannelOption.SO_REUSEADDR, true)
                        .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(WRITE_BUFF_LOW, WRITE_BUFF_HIGH))

                        .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                        .childOption(ChannelOption.SO_KEEPALIVE, true)
                        .childHandler(new RegistrationRemoteHandlerServerTCP(threadName, registrationWrapper, workerEventLoop));

            // have to check options.host for "0.0.0.0". we don't bind to "0.0.0.0", we bind to "null" to get the "any" address!
            if (hostName.equals("0.0.0.0")) {
                tcpBootstrap.localAddress(tcpPort);
            }
            else {
                tcpBootstrap.localAddress(hostName, tcpPort);
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
            else if (OS.isLinux() && NativeLibrary.isAvailable()) {
                // epoll network stack is MUCH faster (but only on linux)
                udpBootstrap.channel(EpollDatagramChannel.class);
            }
            else if (OS.isMacOsX() && NativeLibrary.isAvailable()) {
                // KQueue network stack is MUCH faster (but only on macosx)
                udpBootstrap.channel(KQueueDatagramChannel.class);
            }
            else {
                // windows and linux/mac that are incompatible with the native implementations
                udpBootstrap.channel(NioDatagramChannel.class);
            }


            // Netty4 has a default of 2048 bytes as upper limit for datagram packets, we want this to be whatever we specify
            FixedRecvByteBufAllocator recvByteBufAllocator = new FixedRecvByteBufAllocator(EndPoint.udpMaxSize);

            udpBootstrap.group(newEventLoop(1, threadName + "-UDP-BOSS"),
                               newEventLoop(1, threadName + "-UDP-REGISTRATION"))
                        .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                        .option(ChannelOption.RCVBUF_ALLOCATOR, recvByteBufAllocator)
                        .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(WRITE_BUFF_LOW, WRITE_BUFF_HIGH))

                        // a non-root user can't receive a broadcast packet on *nix if the socket is bound on non-wildcard address.
                        // TODO: move broadcast to it's own handler, and have UDP server be able to be bound to a specific IP
                        // OF NOTE: At the end in my case I decided to bind to .255 broadcast address on Linux systems. (to receive broadcast packets)
                        .localAddress(udpPort) // if you bind to a specific interface, Linux will be unable to receive broadcast packets! see: http://developerweb.net/viewtopic.php?id=5722
                        .childHandler(new RegistrationRemoteHandlerServerUDP(threadName, registrationWrapper, workerEventLoop));

            // // have to check options.host for null. we don't bind to 0.0.0.0, we bind to "null" to get the "any" address!
            // if (hostName.equals("0.0.0.0")) {
            //     udpBootstrap.localAddress(hostName, tcpPort);
            // }
            // else {
            //     udpBootstrap.localAddress(udpPort);
            // }

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
                throw new IllegalArgumentException("Could not bind to LOCAL address '" + localChannelName + "' on the server.", e);
            }

            if (!future.isSuccess()) {
                throw new IllegalArgumentException("Could not bind to LOCAL address '" + localChannelName + "' on the server.", future.cause());
            }

            logger.info("Listening on LOCAL address: [{}]", localChannelName);
            manageForShutdown(future);
        }


        // TCP
        if (tcpBootstrap != null) {
            // Wait until the connection attempt succeeds or fails.
            try {
                future = tcpBootstrap.bind();
                future.await();
            } catch (Exception e) {
                stop();
                throw new IllegalArgumentException("Could not bind to address " + hostName + " TCP port " + tcpPort + " on the server.", e);
            }

            if (!future.isSuccess()) {
                stop();
                throw new IllegalArgumentException("Could not bind to address " + hostName + " TCP port " + tcpPort + " on the server.", future.cause());
            }

            logger.info("TCP server listen address [{}:{}]", hostName, tcpPort);
            manageForShutdown(future);
        }

        // UDP
        if (udpBootstrap != null) {
            // Wait until the connection attempt succeeds or fails.
            try {
                future = udpBootstrap.bind();
                future.await();
            } catch (Exception e) {
                throw new IllegalArgumentException("Could not bind to address " + hostName + " UDP port " + udpPort + " on the server.", e);
            }

            if (!future.isSuccess()) {
                throw new IllegalArgumentException("Could not bind to address " + hostName + " UDP port " + udpPort + " on the server.",
                                                   future.cause());
            }

            logger.info("UDP server listen address [{}:{}]", hostName, udpPort);
            manageForShutdown(future);
        }

        isRunning = true;

        // we now BLOCK until the stop method is called.
        // if we want to continue running code in the server, bind should be called in a separate, non-daemon thread.
        if (blockUntilTerminate) {
            waitForShutdown();
        }
    }

    // called when we are stopped/shut down
    @Override
    protected
    void stopExtraActions() {
        isRunning = false;

        // now WAIT until bind has released the socket
        // wait a max of 10 tries
        int tries = 10;
        while (tries-- > 0 && isRunning(this.config)) {
            logger.warn("Server has requested shutdown, but the socket is still bound. Waiting {} more times", tries);
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
            }
        }
    }

    /**
     * @return true if this server has successfully bound to an IP address and is running
     */
    public
    boolean isRunning() {
        return isRunning;
    }

    /**
     * Checks to see if a server (using the specified configuration) is running. This will check across JVMs by checking the
     * network socket directly, and assumes that if the port is in use and answers, then the server is "running". This does not try to
     * authenticate or validate the connection.
     * <p>
     * This does not check local-channels (which are intra-JVM only). Uses `Broadcast` to check for UDP servers
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

        if (config.tcpPort > 0) {
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
        }

        // use Broadcast to see if there is a UDP server connected
        if (config.udpPort > 0) {
            List<BroadcastResponse> broadcastResponses = null;
            try {
                broadcastResponses = Broadcast.discoverHosts0(null, config.udpPort, 500, true);
                return !broadcastResponses.isEmpty();
            } catch (IOException ignored) {
            }
        }

        return false;
    }
}

