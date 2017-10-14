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

import org.slf4j.Logger;

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
import dorkbox.util.exceptions.InitializationException;
import dorkbox.util.exceptions.SecurityException;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
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

/**
 * The client is both SYNC and ASYNC. It starts off SYNC (blocks thread until it's done), then once it's connected to the server, it's
 * ASYNC.
 */
@SuppressWarnings("unused")
public
class Client<C extends Connection> extends EndPointClient<C> implements Connection {
    /**
     * Gets the version number.
     */
    public static
    String getVersion() {
        return "2.4";
    }

    private final String localChannelName;
    private final String hostName;

    /**
     * Starts a LOCAL <b>only</b> client, with the default local channel name and serialization scheme
     */
    public
    Client() throws InitializationException, SecurityException, IOException {
        this(Configuration.localOnly());
    }

    /**
     * Starts a TCP & UDP client (or a LOCAL client), with the specified serialization scheme
     */
    public
    Client(String host, int tcpPort, int udpPort, String localChannelName)
                    throws InitializationException, SecurityException, IOException {
        this(new Configuration(host, tcpPort, udpPort, localChannelName));
    }

    /**
     * Starts a REMOTE <b>only</b> client, which will connect to the specified host using the specified Connections Options
     */
    @SuppressWarnings("AutoBoxing")
    public
    Client(final Configuration options) throws InitializationException, SecurityException, IOException {
        super(options);

        String threadName = Client.class.getSimpleName();

        Logger logger2 = this.logger;
        if (options.localChannelName != null && (options.tcpPort > 0 || options.udpPort > 0 || options.host != null) ||
            options.localChannelName == null && (options.tcpPort == 0 || options.udpPort == 0 || options.host == null)) {
            String msg = threadName + " Local channel use and TCP/UDP use are MUTUALLY exclusive. Unable to determine intent.";
            logger2.error(msg);
            throw new IllegalArgumentException(msg);
        }

        localChannelName = options.localChannelName;
        hostName = options.host;

        boolean isAndroid = PlatformDependent.isAndroid();


        final EventLoopGroup boss;

        if (isAndroid) {
            // android ONLY supports OIO (not NIO)
            boss = new OioEventLoopGroup(0, new NamedThreadFactory(threadName, threadGroup));
        }
        else if (OS.isLinux()) {
            // JNI network stack is MUCH faster (but only on linux)
            boss = new EpollEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(threadName, threadGroup));
        }
        else {
            boss = new NioEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(threadName, threadGroup));
        }

        manageForShutdown(boss);

        if (options.localChannelName != null && options.tcpPort < 0 && options.udpPort < 0) {
            // no networked bootstraps. LOCAL connection only
            Bootstrap localBootstrap = new Bootstrap();
            this.bootstraps.add(new BootstrapWrapper("LOCAL", options.localChannelName, -1, localBootstrap));

            EventLoopGroup localBoss = new DefaultEventLoopGroup(DEFAULT_THREAD_POOL_SIZE, new NamedThreadFactory(threadName + "-LOCAL",
                                                                                                                  threadGroup));

            localBootstrap.group(localBoss)
                          .channel(LocalChannel.class)
                          .remoteAddress(new LocalAddress(options.localChannelName))
                          .handler(new RegistrationLocalHandlerClient<C>(threadName, registrationWrapper));

            manageForShutdown(localBoss);
        }
        else {
            if (options.host == null) {
                throw new IllegalArgumentException("You must define what host you want to connect to.");
            }

            if (options.tcpPort < 0 && options.udpPort < 0) {
                throw new IllegalArgumentException("You must define what port you want to connect to.");
            }

            if (options.tcpPort > 0) {
                Bootstrap tcpBootstrap = new Bootstrap();
                this.bootstraps.add(new BootstrapWrapper("TCP", options.host, options.tcpPort, tcpBootstrap));

                if (isAndroid) {
                    // android ONLY supports OIO (not NIO)
                    tcpBootstrap.channel(OioSocketChannel.class);
                }
                else if (OS.isLinux()) {
                    // JNI network stack is MUCH faster (but only on linux)
                    tcpBootstrap.channel(EpollSocketChannel.class);
                }
                else {
                    tcpBootstrap.channel(NioSocketChannel.class);
                }

                tcpBootstrap.group(boss)
                            .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                            .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(WRITE_BUFF_LOW, WRITE_BUFF_HIGH))
                            .remoteAddress(options.host, options.tcpPort)
                            .handler(new RegistrationRemoteHandlerClientTCP<C>(threadName,
                                                                               registrationWrapper,
                                                                               serializationManager));

                // android screws up on this!!
                tcpBootstrap.option(ChannelOption.TCP_NODELAY, !isAndroid)
                            .option(ChannelOption.SO_KEEPALIVE, true);
            }


            if (options.udpPort > 0) {
                Bootstrap udpBootstrap = new Bootstrap();
                this.bootstraps.add(new BootstrapWrapper("UDP", options.host, options.udpPort, udpBootstrap));

                if (isAndroid) {
                    // android ONLY supports OIO (not NIO)
                    udpBootstrap.channel(OioDatagramChannel.class);
                }
                else if (OS.isLinux()) {
                    // JNI network stack is MUCH faster (but only on linux)
                    udpBootstrap.channel(EpollDatagramChannel.class);
                }
                else {
                    udpBootstrap.channel(NioDatagramChannel.class);
                }

                udpBootstrap.group(boss)
                            .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                            .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(WRITE_BUFF_LOW, WRITE_BUFF_HIGH))
                            .localAddress(new InetSocketAddress(0))  // bind to wildcard
                            .remoteAddress(new InetSocketAddress(options.host, options.udpPort))
                            .handler(new RegistrationRemoteHandlerClientUDP<C>(threadName,
                                                                               registrationWrapper,
                                                                               serializationManager));

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
        reconnect(this.connectionTimeout);
    }

    /**
     * Allows the client to reconnect to the last connected server
     *
     * @throws IOException
     *                 if the client is unable to reconnect in the requested time
     */
    public
    void reconnect(int connectionTimeout) throws IOException {
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
        synchronized (this.shutdownInProgress) {
        }

        if (localChannelName != null) {
            logger.info("Connecting to local server: {}", localChannelName);
        }
        else {
            logger.info("Connecting to server: {}", hostName);
        }

        // have to start the registration process
        this.connectingBootstrap.set(0);
        registerNextProtocol();

        // have to BLOCK
        // don't want the client to run before registration is complete
        synchronized (this.registrationLock) {
            if (!registrationComplete) {
                try {
                    this.registrationLock.wait(connectionTimeout);
                } catch (InterruptedException e) {
                    throw new IOException("Unable to complete registration within '" + connectionTimeout + "' milliseconds", e);
                }
            }
        }

        // RMI methods are usually created during the connection phase. We should wait until they are finished
        waitForRmi(connectionTimeout);
    }

    @Override
    public
    boolean hasRemoteKeyChanged() {
        return this.connection.hasRemoteKeyChanged();
    }

    /**
     * @return the remote address, as a string.
     */
    @Override
    public
    String getRemoteHost() {
        return this.connection.getRemoteHost();
    }

    /**
     * @return true if this connection is established on the loopback interface
     */
    @Override
    public
    boolean isLoopback() {
        return this.connection.isLoopback();
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
        return this.connection.id();
    }

    /**
     * @return the connection (TCP or LOCAL) id of this connection as a HEX string.
     */
    @Override
    public
    String idAsHex() {
        return this.connection.idAsHex();
    }

    @Override
    public
    boolean hasUDP() {
        return this.connection.hasUDP();
    }

    /**
     * Expose methods to send objects to a destination when the connection has become idle.
     */
    @Override
    public
    IdleBridge sendOnIdle(IdleSender<?, ?> sender) {
        return this.connection.sendOnIdle(sender);
    }

    /**
     * Expose methods to send objects to a destination when the connection has become idle.
     */
    @Override
    public
    IdleBridge sendOnIdle(Object message) {
        return this.connection.sendOnIdle(message);
    }

    /**
     * Marks the connection to be closed as soon as possible. This is evaluated when the current
     * thread execution returns to the network stack.
     */
    @Override
    public
    void closeAsap() {
        this.connection.closeAsap();
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
    <Iface> void getRemoteObject(final Class<Iface> interfaceClass, final RemoteObjectCallback<Iface> callback) throws IOException {
        this.connectionManager.getConnection0().getRemoteObject(interfaceClass, callback);
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
    <Iface> void getRemoteObject(final int objectId, final RemoteObjectCallback<Iface> callback) throws IOException {
        this.connectionManager.getConnection0().getRemoteObject(objectId, callback);
    }

    /**
     * Fetches the connection used by the client.
     * <p/>
     * Make <b>sure</b> that you only call this <b>after</b> the client connects!
     * <p/>
     * This is preferred to {@link EndPoint#getConnections()} getConnections()}, as it properly does some error checking
     */
    public
    C getConnection() {
        return this.connection;
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

