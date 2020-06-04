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
import java.security.SecureRandom;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.agrona.BufferUtil;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

import dorkbox.network.aeron.EchoChannels;
import dorkbox.network.aeron.EchoMessages;
import dorkbox.network.aeron.exceptions.ClientIOException;
import dorkbox.network.aeron.exceptions.EchoClientException;
import dorkbox.network.aeron.exceptions.EchoClientRejectedException;
import dorkbox.network.aeron.exceptions.EchoClientTimedOutException;
import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.EndPointClient;
import dorkbox.network.rmi.RemoteObject;
import dorkbox.network.rmi.RemoteObjectCallback;
import dorkbox.network.rmi.TimeoutException;
import dorkbox.util.exceptions.SecurityException;
import io.aeron.ConcurrentPublication;
import io.aeron.FragmentAssembler;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;

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
        return "4.1";
    }


    private static final Pattern PATTERN_ERROR = Pattern.compile("^ERROR (.*)$");
    private static final Pattern PATTERN_CONNECT = Pattern.compile("^CONNECT ([0-9]+) ([0-9]+) ([0-9A-F]+)$");
    private static final Pattern PATTERN_ECHO = Pattern.compile("^ECHO (.*)$");

    private final SecureRandom random = new SecureRandom();

    private volatile int remote_data_port;
    private volatile int remote_control_port;
    private volatile boolean remote_ports_received;
    private volatile boolean failed;
    private volatile int remote_session;
    private volatile int duologue_key;


    /**
     * Starts a LOCAL <b>only</b> client, with the default local channel name and serialization scheme
     */
    public
    Client() throws SecurityException, IOException {
        this(new ClientConfiguration());
    }

    /**
     * Starts a REMOTE <b>only</b> client, which will connect to the specified host using the specified Connections Options
     */
    @SuppressWarnings("AutoBoxing")
    public
    Client(final ClientConfiguration config) throws SecurityException, IOException {
        super(config);
    }

    /**
     * Allows the client to reconnect to the last connected server
     *
     * @throws IOException
     *                 if the client is unable to reconnect in the previously requested connection-timeout
     */
    public
    void reconnect() throws IOException, EchoClientException {
        reconnect(connectionTimeout);
    }

    /**
     * Allows the client to reconnect to the last connected server
     *
     * @throws IOException
     *                 if the client is unable to reconnect in the requested time
     */
    public
    void reconnect(final int connectionTimeout) throws IOException, EchoClientException {
        // make sure we are closed first
        close();

        connect(connectionTimeout);
    }


    /**
     * will attempt to connect to the server, with a 30 second timeout.
     *
     * @throws IOException
     *                 if the client is unable to connect in 30 seconds
     */
    public
    void connect() throws IOException, EchoClientException {
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
    void connect(final int connectionTimeout) throws IOException, EchoClientException {
        this.connectionTimeout = connectionTimeout;

        // make sure we are not trying to connect during a close or stop event.
        // This will wait until we have finished shutting down.
        // synchronized (shutdownInProgress) {
        // }

        // // if we are in the SAME thread as netty -- start in a new thread (otherwise we will deadlock)
        // if (isNettyThread()) {
        //     runNewThread("Restart Thread", new Runnable(){
        //         @Override
        //         public
        //         void run() {
        //             try {
        //                 connect(connectionTimeout);
        //             } catch (IOException e) {
        //                 e.printStackTrace();
        //             }
        //         }
        //     });
        //
        //     return;
        // }

        /*
         * Generate a one-time pad.
         */
        this.duologue_key = this.random.nextInt();

        final UnsafeBuffer buffer = new UnsafeBuffer(BufferUtil.allocateDirectAligned(1024, 16));

        final String session_name;
        try (final Subscription subscription = this.setupAllClientsSubscription()) {
            try (final Publication publication = this.setupAllClientsPublication()) {

                /*
                 * Send a one-time pad to the server.
                 */
                EchoMessages.sendMessage(publication,
                                         buffer,
                                         "HELLO " + Integer.toUnsignedString(this.duologue_key, 16)
                                                           .toUpperCase());

                session_name = Integer.toString(publication.sessionId());
                this.waitForConnectResponse(subscription, session_name);
            } catch (final IOException e) {
                throw new ClientIOException(e);
            }
        }

        /*
         * Connect to the publication and subscription that the server has sent
         * back to this client.
         */
        try (final Subscription subscription = this.setupConnectSubscription()) {
            try (final Publication publication = this.setupConnectPublication()) {

                /**
                 * Note: Reassembly has been shown to be minimal impact to latency. But not totally negligible. If the lowest latency is desired, then limiting message sizes to MTU size is a good practice.
                 *
                 * Note: There is a maximum length allowed for messages which is the min of 1/8th a term length or 16MB. Messages larger than this should chunked using an application level chunking protocol. Chunking has better recovery properties from failure and streams with mechanical sympathy.
                 */
                final FragmentHandler fragmentHandler = new FragmentAssembler((data, offset, length, header)->onEchoResponse(session_name,
                                                                                                                             data,
                                                                                                                             offset,
                                                                                                                             length));

                final IdleStrategy idleStrategy = new BackoffIdleStrategy(100, 10, TimeUnit.MICROSECONDS.toNanos(1), TimeUnit.MICROSECONDS.toNanos(100));

                while (true) {
                    // final int fragmentsRead = subscription.poll(fragmentHandler, 10);
                    // idleStrategy.idle(fragmentsRead);

                    /*
                     * Send ECHO messages to the server and wait for responses.
                     */
                    EchoMessages.sendMessage(publication, buffer, "ECHO " + Long.toUnsignedString(this.random.nextLong(), 16));

                    for (int index = 0; index < 100; ++index) {
                        subscription.poll(fragmentHandler, 1000);

                        try {
                            Thread.sleep(10L);
                        } catch (final InterruptedException e) {
                            Thread.currentThread()
                                  .interrupt();
                        }
                    }
                }


            } catch (final IOException e) {
                throw new ClientIOException(e);
            }
        }




        // if (isShutdown()) {
        //     throw new IOException("Unable to connect when shutdown...");
        // }

        // if (localChannelName != null) {
        //     logger.info("Connecting to local server: {}", localChannelName);
        // }
        // else {
        //     if (config.tcpPort > 0 && config.udpPort > 0) {
        //         logger.info("Connecting to TCP/UDP server [{}:{}]", hostName, config.tcpPort, config.udpPort);
        //     }
        //     else if (config.tcpPort > 0) {
        //         logger.info("Connecting to TCP server  [{}:{}]", hostName, config.tcpPort);
        //     }
        //     else {
        //         logger.info("Connecting to UDP server  [{}:{}]", hostName, config.udpPort);
        //     }
        // }
        //
        // // have to start the registration process. This will wait until registration is complete and RMI methods are initialized
        // // if this is called in the event dispatch thread for netty, it will deadlock!
        // startRegistration();

        //
        // if (config.tcpPort == 0 && config.udpPort > 0) {
        //     // AFTER registration is complete, if we are UDP only -- setup a heartbeat (must be the larger of 2x the idle timeout OR 10 seconds)
        //     startUdpHeartbeat();
        // }


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

    @Override
    public
    boolean isIPC() {
        return false;
    }

    /**
     * @return true if this connection is a network connection
     */
    @Override
    public
    boolean isNetwork() { return false; }

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
     * Closes all connections ONLY (keeps the client running), does not remove any listeners. To STOP the client, use stop().
     * <p/>
     * This is used, for example, when reconnecting to a server.
     */
    @Override
    public
    void close() {
        super.close();
        // closeConnection();
    }

    /**
     * Checks to see if this client has connected yet or not.
     *
     * @return true if we are connected, false otherwise.
     */
    public
    boolean isConnected() {
        return super.isConnected.get();
    }

































    private
    void onEchoResponse(final String session_name, final DirectBuffer buffer, final int offset, final int length) {
        final String response = EchoMessages.parseMessageUTF8(buffer, offset, length);

        logger.debug("[{}] response: {}", session_name, response);

        final Matcher echo_matcher = PATTERN_ECHO.matcher(response);
        if (echo_matcher.matches()) {
            final String message = echo_matcher.group(1);
            logger.debug("[{}] ECHO {}", session_name, message);
            return;
        }

        logger.error("[{}] server returned unrecognized message: {}", session_name, response);
    }

    private
    Publication setupConnectPublication() throws EchoClientTimedOutException {
        final ConcurrentPublication publication = EchoChannels.createPublicationWithSession(this.aeron,
                                                                                            this.config.remoteAddress,
                                                                                            this.remote_data_port,
                                                                                            this.remote_session, UDP_STREAM_ID);

        for (int index = 0; index < 1000; ++index) {
            if (publication.isConnected()) {
                logger.debug("CONNECT publication connected");
                return publication;
            }

            try {
                Thread.sleep(10L);
            } catch (final InterruptedException e) {
                Thread.currentThread()
                      .interrupt();
            }
        }

        publication.close();
        throw new EchoClientTimedOutException("Making CONNECT publication to server");
    }

    private
    Subscription setupConnectSubscription() throws EchoClientTimedOutException {
        final Subscription subscription = EchoChannels.createSubscriptionDynamicMDCWithSession(this.aeron,
                                                                                               this.config.remoteAddress,
                                                                                               this.remote_control_port,
                                                                                               this.remote_session, UDP_STREAM_ID);

        for (int index = 0; index < 1000; ++index) {
            if (subscription.isConnected() && subscription.imageCount() > 0) {
                logger.debug("CONNECT subscription connected");
                return subscription;
            }

            try {
                Thread.sleep(10L);
            } catch (final InterruptedException e) {
                Thread.currentThread()
                      .interrupt();
            }
        }

        subscription.close();
        throw new EchoClientTimedOutException("Making CONNECT subscription to server");
    }

    private
    void waitForConnectResponse(final Subscription subscription, final String session_name) throws EchoClientTimedOutException, EchoClientRejectedException {
        logger.debug("waiting for response");

        final FragmentHandler handler = new FragmentAssembler((data, offset, length, header)->this.onInitialResponse(session_name,
                                                                                                                     data,
                                                                                                                     offset,
                                                                                                                     length));

        for (int index = 0; index < 1000; ++index) {
            subscription.poll(handler, 1000);

            if (this.failed) {
                throw new EchoClientRejectedException("Server rejected this client");
            }

            if (this.remote_ports_received) {
                return;
            }

            try {
                Thread.sleep(10L);
            } catch (final InterruptedException e) {
                Thread.currentThread()
                      .interrupt();
            }
        }

        throw new EchoClientTimedOutException("Waiting for CONNECT response from server");
    }

    /**
     * Parse the initial response from the server.
     */

    private
    void onInitialResponse(final String session_name, final DirectBuffer buffer, final int offset, final int length) {
        final String response = EchoMessages.parseMessageUTF8(buffer, offset, length);

        logger.trace("[{}] response: {}", session_name, response);

        /*
         * Try to extract the session identifier to determine whether the message
         * was intended for this client or not.
         */
        final int space = response.indexOf(" ");
        if (space == -1) {
            logger.error("[{}] server returned unrecognized message: {}", session_name, response);
            return;
        }

        final String message_session = response.substring(0, space);
        if (!Objects.equals(message_session, session_name)) {
            logger.trace("[{}] ignored message intended for another client", session_name);
            return;
        }

        /*
         * The message was intended for this client. Try to parse it as one
         * of the available message types.
         */

        final String text = response.substring(space)
                                    .trim();

        final Matcher error_matcher = PATTERN_ERROR.matcher(text);
        if (error_matcher.matches()) {
            final String message = error_matcher.group(1);
            logger.error("[{}] server returned an error: {}", session_name, message);
            this.failed = true;
            return;
        }

        final Matcher connect_matcher = PATTERN_CONNECT.matcher(text);
        if (connect_matcher.matches()) {
            final int port_data = Integer.parseUnsignedInt(connect_matcher.group(1));
            final int port_control = Integer.parseUnsignedInt(connect_matcher.group(2));
            final int session_crypted = Integer.parseUnsignedInt(connect_matcher.group(3), 16);

            logger.debug("[{}] connect {} {} (encrypted {})",
                      session_name,
                      Integer.valueOf(port_data),
                      Integer.valueOf(port_control),

                      Integer.valueOf(session_crypted));
            this.remote_control_port = port_control;
            this.remote_data_port = port_data;
            this.remote_session = this.duologue_key ^ session_crypted;
            this.remote_ports_received = true;
            return;
        }

        logger.error("[{}] server returned unrecognized message: {}", session_name, text);
    }

    private
    Publication setupAllClientsPublication() throws EchoClientTimedOutException {
        // Note: The Aeron.addPublication method will block until the Media Driver acknowledges the request or a timeout occurs.
        final ConcurrentPublication publication = EchoChannels.createPublication(this.aeron,
                                                                                 this.config.remoteAddress,
                                                                                 this.config.port, UDP_STREAM_ID);

        for (int index = 0; index < 1000; ++index) {
            if (publication.isConnected()) {
                logger.debug("initial publication connected");
                return publication;
            }

            try {
                Thread.sleep(10L);
            } catch (final InterruptedException e) {
                Thread.currentThread()
                      .interrupt();
            }
        }

        publication.close();
        throw new EchoClientTimedOutException("Making initial publication to server");
    }

    private
    Subscription setupAllClientsSubscription() throws EchoClientTimedOutException {
        final Subscription subscription = EchoChannels.createSubscriptionDynamicMDC(this.aeron,
                                                                                    this.config.remoteAddress,
                                                                                    this.config.controlPort,
                                                                                    UDP_STREAM_ID);

        for (int index = 0; index < 1000; ++index) {
            if (subscription.isConnected() && subscription.imageCount() > 0) {
                logger.debug("initial subscription connected");
                return subscription;
            }

            try {
                Thread.sleep(10L);
            } catch (final InterruptedException e) {
                Thread.currentThread()
                      .interrupt();
            }
        }

        subscription.close();
        throw new EchoClientTimedOutException("Making initial subscription to server");
    }
}

