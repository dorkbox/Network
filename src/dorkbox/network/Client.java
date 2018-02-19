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
import java.net.InetSocketAddress;

import dorkbox.network.connection.BootstrapWrapper;
import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.EndPointClient;
import dorkbox.network.connection.idle.IdleBridge;
import dorkbox.network.connection.idle.IdleSender;
import dorkbox.network.connection.registration.local.RegistrationLocalHandlerClient;
import dorkbox.network.connection.registration.remote.RegistrationRemoteHandlerClientTCP;
import dorkbox.network.connection.registration.remote.RegistrationRemoteHandlerClientUDP;
import dorkbox.network.rmi.RemoteObject;
import dorkbox.network.rmi.RemoteObjectCallback;
import dorkbox.network.rmi.TimeoutException;
import dorkbox.util.NamedThreadFactory;
import dorkbox.util.OS;
import dorkbox.util.exceptions.SecurityException;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.kqueue.KQueueDatagramChannel;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueSocketChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.socket.oio.OioDatagramChannel;
import io.netty.channel.socket.oio.OioSocketChannel;

/**
 * The client is both SYNC and ASYNC. It starts off SYNC (blocks thread until it's done), then once it's connected to the server, it's
 * ASYNC.
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public
class Client<C extends Connection> extends EndPointClient implements Connection {
    /**
     * Gets the version number.
     */
    public static
    String getVersion() {
        return "2.8";
    }

    private final String localChannelName;
    private final String hostName;
    private Configuration config;

    /**
     * Starts a LOCAL <b>only</b> client, with the default local channel name and serialization scheme
     */
    public
    Client() throws SecurityException {
        this(Configuration.localOnly());
    }

    /**
     * Starts a TCP & UDP client (or a LOCAL client), with the specified serialization scheme
     */
    public
    Client(String host, int tcpPort, int udpPort, String localChannelName) throws SecurityException {
        this(new Configuration(host, tcpPort, udpPort, localChannelName));
    }

    /**
     * Starts a REMOTE <b>only</b> client, which will connect to the specified host using the specified Connections Options
     */
    @SuppressWarnings("AutoBoxing")
    public
    Client(final Configuration config) throws SecurityException {
        super(config);

        String threadName = Client.class.getSimpleName();

        this.config = config;
        boolean hostConfigured = (config.tcpPort > 0 || config.udpPort > 0) && config.host != null;
        boolean isLocalChannel = config.localChannelName != null;

        if (isLocalChannel && hostConfigured) {
            String msg = threadName + " Local channel use and TCP/UDP use are MUTUALLY exclusive. Unable to determine what to do.";
            logger.error(msg);
            throw new IllegalArgumentException(msg);
        }

        localChannelName = config.localChannelName;
        hostName = config.host;

        final EventLoopGroup boss;

        if (OS.isAndroid()) {
            // android ONLY supports OIO (not NIO)
            boss = new OioEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(threadName, threadGroup));
        }
        else if (OS.isLinux() && NativeLibrary.isAvailable()) {
            // JNI network stack is MUCH faster (but only on linux)
            boss = new EpollEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(threadName, threadGroup));
        }
        else if (OS.isMacOsX() && NativeLibrary.isAvailable()) {
            // KQueue network stack is MUCH faster (but only on macosx)
            boss = new KQueueEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(threadName, threadGroup));
        }
        else {
            boss = new NioEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(threadName, threadGroup));
        }

        manageForShutdown(boss);

        if (config.localChannelName != null && config.tcpPort <= 0 && config.udpPort <= 0) {
            // no networked bootstraps. LOCAL connection only
            Bootstrap localBootstrap = new Bootstrap();
            bootstraps.add(new BootstrapWrapper("LOCAL", config.localChannelName, -1, localBootstrap));

            EventLoopGroup localBoss = new DefaultEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(threadName + "-LOCAL",
                                                                                                                  threadGroup));

            localBootstrap.group(localBoss)
                          .channel(LocalChannel.class)
                          .remoteAddress(new LocalAddress(config.localChannelName))
                          .handler(new RegistrationLocalHandlerClient(threadName, registrationWrapper));

            manageForShutdown(localBoss);
        }
        else {
            if (config.host == null) {
                throw new IllegalArgumentException("You must define what host you want to connect to.");
            }

            if (config.tcpPort <= 0 && config.udpPort <= 0) {
                throw new IllegalArgumentException("You must define what port you want to connect to.");
            }

            if (config.tcpPort > 0) {
                Bootstrap tcpBootstrap = new Bootstrap();
                bootstraps.add(new BootstrapWrapper("TCP", config.host, config.tcpPort, tcpBootstrap));

                if (OS.isAndroid()) {
                    // android ONLY supports OIO (not NIO)
                    tcpBootstrap.channel(OioSocketChannel.class);
                }
                else if (OS.isLinux() && NativeLibrary.isAvailable()) {
                    // JNI network stack is MUCH faster (but only on linux)
                    tcpBootstrap.channel(EpollSocketChannel.class);
                }
                else if (OS.isMacOsX() && NativeLibrary.isAvailable()) {
                    // KQueue network stack is MUCH faster (but only on macosx)
                    tcpBootstrap.channel(KQueueSocketChannel.class);
                }
                else {
                    tcpBootstrap.channel(NioSocketChannel.class);
                }

                tcpBootstrap.group(boss)
                            .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                            .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(WRITE_BUFF_LOW, WRITE_BUFF_HIGH))
                            .remoteAddress(config.host, config.tcpPort)
                            .handler(new RegistrationRemoteHandlerClientTCP(threadName,
                                                                            registrationWrapper));

                // android screws up on this!!
                tcpBootstrap.option(ChannelOption.TCP_NODELAY, !OS.isAndroid())
                            .option(ChannelOption.SO_KEEPALIVE, true);
            }


            if (config.udpPort > 0) {
                Bootstrap udpBootstrap = new Bootstrap();
                bootstraps.add(new BootstrapWrapper("UDP", config.host, config.udpPort, udpBootstrap));

                if (OS.isAndroid()) {
                    // android ONLY supports OIO (not NIO)
                    udpBootstrap.channel(OioDatagramChannel.class);
                }
                else if (OS.isLinux() && NativeLibrary.isAvailable()) {
                    // JNI network stack is MUCH faster (but only on linux)
                    udpBootstrap.channel(EpollDatagramChannel.class);
                }
                else if (OS.isMacOsX() && NativeLibrary.isAvailable()) {
                    // KQueue network stack is MUCH faster (but only on macosx)
                    udpBootstrap.channel(KQueueDatagramChannel.class);
                }
                else {
                    udpBootstrap.channel(NioDatagramChannel.class);
                }


                udpBootstrap.group(boss)
                            .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                            // Netty4 has a default of 2048 bytes as upper limit for datagram packets.
                            .option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(EndPoint.udpMaxSize))
                            .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(WRITE_BUFF_LOW, WRITE_BUFF_HIGH))
                            .localAddress(new InetSocketAddress(0))  // bind to wildcard
                            .remoteAddress(new InetSocketAddress(config.host, config.udpPort))
                            .handler(new RegistrationRemoteHandlerClientUDP(threadName,
                                                                            registrationWrapper));

                // Enable to READ and WRITE MULTICAST data (ie, 192.168.1.0)
                // in order to WRITE: write as normal, just make sure it ends in .255
                // in order to LISTEN:
                //    InetAddress group = InetAddress.getByName("203.0.113.0");
                //    NioDatagramChannel.joinGroup(group);
                // THEN once done
                //    NioDatagramChannel.leaveGroup(group), close the socket
                udpBootstrap.option(ChannelOption.SO_BROADCAST, false)
                            .option(ChannelOption.SO_SNDBUF, udpMaxSize);
            }
        }
    }

    /**
     * Allows the client to reconnect to the last connected server
     *
     * @throws IOException
     *                 if the client is unable to reconnect in the previously requested connection-timeout
     */
    public
    void reconnect() throws IOException {
        reconnect(connectionTimeout);
    }

    /**
     * Allows the client to reconnect to the last connected server
     *
     * @throws IOException
     *                 if the client is unable to reconnect in the requested time
     */
    public
    void reconnect(final int connectionTimeout) throws IOException {
        // close out all old connections
        closeConnections();

        connect(connectionTimeout);
    }


    /**
     * will attempt to connect to the server, with a 30 second timeout.
     *
     * @throws IOException
     *                 if the client is unable to connect in 30 seconds
     */
    public
    void connect() throws IOException {
        connect(30000);
    }

    /**
     * will attempt to connect to the server, and will the specified timeout.
     * <p/>
     * will BLOCK until completed
     *
     * @param connectionTimeout
     *                 wait for x milliseconds. 0 will wait indefinitely
     *
     * @throws IOException
     *                 if the client is unable to connect in the requested time
     */
    public
    void connect(int connectionTimeout) throws IOException {
        this.connectionTimeout = connectionTimeout;

        // make sure we are not trying to connect during a close or stop event.
        // This will wait until we have finished shutting down.
        synchronized (shutdownInProgress) {
        }

        if (isShutdown()) {
            throw new IOException("Unable to connect when shutdown...");
        }

        if (localChannelName != null) {
            logger.info("Connecting to local server: {}", localChannelName);
        }
        else {
            if (config.tcpPort > 0 && config.udpPort > 0) {
                logger.info("Connecting to server: {} at TCP/UDP port: {}", hostName, config.tcpPort, config.udpPort);
            } else {
                logger.info("Connecting to server: {} at TCP port: {}", hostName, config.tcpPort);
            }
        }

        // have to start the registration process. This will wait until registration is complete and RMI methods are initialized
        startRegistration();
    }

    @Override
    public
    boolean hasRemoteKeyChanged() {
        return connection.hasRemoteKeyChanged();
    }

    /**
     * @return the remote address, as a string.
     */
    @Override
    public
    String getRemoteHost() {
        return connection.getRemoteHost();
    }

    /**
     * @return true if this connection is established on the loopback interface
     */
    @Override
    public
    boolean isLoopback() {
        return connection.isLoopback();
    }

    @SuppressWarnings("rawtypes")
    @Override
    public
    EndPoint getEndPoint() {
        return this;
    }

    /**
     * @return the connection (TCP or LOCAL) id of this connection.
     */
    @Override
    public
    int id() {
        return connection.id();
    }

    /**
     * @return the connection (TCP or LOCAL) id of this connection as a HEX string.
     */
    @Override
    public
    String idAsHex() {
        return connection.idAsHex();
    }

    @Override
    public
    boolean hasUDP() {
        return connection.hasUDP();
    }

    /**
     * Expose methods to send objects to a destination when the connection has become idle.
     */
    @Override
    public
    IdleBridge sendOnIdle(IdleSender<?, ?> sender) {
        return connection.sendOnIdle(sender);
    }

    /**
     * Expose methods to send objects to a destination when the connection has become idle.
     */
    @Override
    public
    IdleBridge sendOnIdle(Object message) {
        return connection.sendOnIdle(message);
    }

    /**
     * Marks the connection to be closed as soon as possible. This is evaluated when the current
     * thread execution returns to the network stack.
     */
    @Override
    public
    void closeAsap() {
        connection.closeAsap();
    }

    /**
     * Tells the remote connection to create a new proxy object that implements the specified interface. The methods on this object "map"
     * to an object that is created remotely.
     * <p>
     * The callback will be notified when the remote object has been created.
     * <p>
     * <p>
     * Methods that return a value will throw {@link TimeoutException} if the response is not received with the
     * {@link RemoteObject#setResponseTimeout(int) response timeout}.
     * <p/>
     * If {@link RemoteObject#setAsync(boolean) non-blocking} is false (the default), then methods that return a value must
     * not be called from the update thread for the connection. An exception will be thrown if this occurs. Methods with a
     * void return value can be called on the update thread.
     * <p/>
     * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side
     * will have the proxy object replaced with the registered (non-proxy) object.
     * <p>
     * If one wishes to change the default behavior, cast the object to access the different methods.
     * ie:  `RemoteObject remoteObject = (RemoteObject) test;`
     *
     * @see RemoteObject
     */
    @Override
    public
    <Iface> void createRemoteObject(final Class<Iface> interfaceClass, final RemoteObjectCallback<Iface> callback) {
        try {
            connection.createRemoteObject(interfaceClass, callback);
        } catch (NullPointerException e) {
            logger.error("Error creating remote object!", e);
        }
    }

    /**
     * Tells the remote connection to create a new proxy object that implements the specified interface. The methods on this object "map"
     * to an object that is created remotely.
     * <p>
     * The callback will be notified when the remote object has been created.
     * <p>
     * <p>
     * Methods that return a value will throw {@link TimeoutException} if the response is not received with the
     * {@link RemoteObject#setResponseTimeout(int) response timeout}.
     * <p/>
     * If {@link RemoteObject#setAsync(boolean) non-blocking} is false (the default), then methods that return a value must
     * not be called from the update thread for the connection. An exception will be thrown if this occurs. Methods with a
     * void return value can be called on the update thread.
     * <p/>
     * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side
     * will have the proxy object replaced with the registered (non-proxy) object.
     * <p>
     * If one wishes to change the default behavior, cast the object to access the different methods.
     * ie:  `RemoteObject remoteObject = (RemoteObject) test;`
     *
     * @see RemoteObject
     */
    @Override
    public
    <Iface> void getRemoteObject(final int objectId, final RemoteObjectCallback<Iface> callback) {
        try {
            connection.getRemoteObject(objectId, callback);
        } catch (NullPointerException e) {
            logger.error("Error getting remote object!", e);
        }
    }

    /**
     * Fetches the connection used by the client.
     * <p/>
     * Make <b>sure</b> that you only call this <b>after</b> the client connects!
     * <p/>
     * This is preferred to {@link EndPoint#getConnections()}, as it properly does some error checking
     */
    @SuppressWarnings("unchecked")
    public
    C getConnection() {
        return (C) connection;
    }

    /**
     * Closes all connections ONLY (keeps the client running). To STOP the client, use stop().
     * <p/>
     * This is used, for example, when reconnecting to a server.
     */
    @Override
    public
    void close() {
        closeConnections();
    }
}

