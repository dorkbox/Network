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
package dorkbox.network.connection;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dorkbox.network.Client;
import dorkbox.network.Configuration;
import dorkbox.network.connection.bridge.ConnectionBridge;
import dorkbox.util.exceptions.SecurityException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;

/**
 * This serves the purpose of making sure that specific methods are not available to the end user.
 */
public
class EndPointClient extends EndPoint {

    // is valid when there is a connection to the server, otherwise it is null
    protected volatile Connection connection;

    private CountDownLatch registration;

    private final Object bootstrapLock = new Object();
    protected List<BootstrapWrapper> bootstraps = new LinkedList<BootstrapWrapper>();
    private Iterator<BootstrapWrapper> bootstrapIterator;

    protected volatile int connectionTimeout = 5000; // default is 5 seconds


    private volatile ConnectionBridge connectionBridgeFlushAlways;


    public
    EndPointClient(Configuration config) throws SecurityException {
        super(Client.class, config);
    }

    protected
    void startRegistration() throws IOException {
        synchronized (bootstrapLock) {
            // always reset everything.
            registration = new CountDownLatch(1);

            bootstrapIterator = bootstraps.iterator();
        }

        doRegistration();

        // have to BLOCK
        // don't want the client to run before registration is complete
        try {
            if (!registration.await(connectionTimeout, TimeUnit.MILLISECONDS)) {
                throw new IOException("Unable to complete registration within '" + connectionTimeout + "' milliseconds");
            }
        } catch (InterruptedException e) {
            throw new IOException("Unable to complete registration within '" + connectionTimeout + "' milliseconds", e);
        }
    }

    // protected by bootstrapLock
    private
    boolean isRegistrationComplete() {
        return !bootstrapIterator.hasNext();
    }

    // this is called by 2 threads. The startup thread, and the registration-in-progress thread
    private void doRegistration() {
        synchronized (bootstrapLock) {
            BootstrapWrapper bootstrapWrapper = bootstrapIterator.next();

            ChannelFuture future;

            if (connectionTimeout != 0) {
                // must be before connect
                bootstrapWrapper.bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectionTimeout);
            }

            try {
                // UDP : When this is CONNECT, a udp socket will ONLY accept UDP traffic from the remote address (ip/port combo).
                //       If the reply isn't from the correct port, then the other end will receive a "Port Unreachable" exception.

                future = bootstrapWrapper.bootstrap.connect();
                future.await(connectionTimeout);
            } catch (Exception e) {
                String errorMessage = "Could not connect to the " + bootstrapWrapper.type + " server at " + bootstrapWrapper.address + " on port: " + bootstrapWrapper.port;
                if (logger.isDebugEnabled()) {
                    // extra info if debug is enabled
                    logger.error(errorMessage, e);
                }
                else {
                    logger.error(errorMessage);
                }

                return;
            }

            if (!future.isSuccess()) {
                Throwable cause = future.cause();
                String errorMessage = "Connection refused  :" + bootstrapWrapper.address + " at " + bootstrapWrapper.type + " port: " + bootstrapWrapper.port;

                if (cause instanceof java.net.ConnectException) {
                    if (cause.getMessage()
                             .contains("refused")) {
                        // extra space here is so it aligns with "Connecting to server:"
                        logger.error(errorMessage);
                    }

                } else {
                    logger.error(errorMessage, cause);
                }

                return;
            }

            logger.trace("Waiting for registration from server.");

            manageForShutdown(future);
        }
    }



    /**
     * Internal call by the pipeline to notify the client to continue registering the different session protocols.
     *
     * @return true if we are done registering bootstraps
     */
    @Override
    protected
    boolean registerNextProtocol0() {
        boolean registrationComplete;

        synchronized (bootstrapLock) {
            registrationComplete = isRegistrationComplete();
            if (!registrationComplete) {
                doRegistration();
            }
        }

        logger.trace("Registered protocol from server.");

        // only let us continue with connections (this starts up the client/server implementations) once ALL of the
        // bootstraps have connected
        return registrationComplete;
    }

    /**
     * Internal (to the networking stack) to notify the client that registration has COMPLETED. This is necessary because the client
     * will BLOCK until it has successfully registered it's connections.
     */
    @Override
    final
    void connectionConnected0(final ConnectionImpl connection) {
        connectionBridgeFlushAlways = new ConnectionBridge() {
            @Override
            public
            void self(Object message) {
                connection.self(message);
                flush();
            }

            @Override
            public
            ConnectionPoint TCP(Object message) {
                ConnectionPoint tcp = connection.TCP_backpressure(message);
                tcp.flush();
                return tcp;
            }

            @Override
            public
            ConnectionPoint UDP(Object message) {
                ConnectionPoint udp = connection.UDP_backpressure(message);
                udp.flush();
                return udp;
            }

            @Override
            public
            Ping ping() {
                Ping ping = connection.ping();
                flush();
                return ping;
            }

            @Override
            public
            void flush() {
                connection.flush();
            }
        };

        //noinspection unchecked
        this.connection = connection;

        synchronized (bootstrapLock) {
            // we're done with registration, so no need to keep this around
            bootstrapIterator = null;
            registration.countDown();
        }

        // invokes the listener.connection() method, and initialize the connection channels with whatever extra info they might need.
        // This will also start the RMI (if necessary) initialization/creation of objects
        super.connectionConnected0(connection);
    }

    private
    void registrationCompleted() {
        // make sure we're not waiting on registration
        synchronized (bootstrapLock) {
            // we're done with registration, so no need to keep this around
            bootstrapIterator = null;
            registration.countDown();
        }
    }

    /**
     * Expose methods to send objects to a destination.
     * <p/>
     * This returns a bridge that will flush after EVERY send! This is because sending data can occur on the client, outside
     * of the normal eventloop patterns, and it is confusing to the user to have to manually flush the channel each time.
     */
    @Override
    public
    ConnectionBridge send() {
        return connectionBridgeFlushAlways;
    }

    /**
     * Closes all connections ONLY (keeps the client running).  To STOP the client, use stop().
     * <p/>
     * This is used, for example, when reconnecting to a server.
     */
    public
    void closeConnections() {
        // Only keep the listeners for connections IF we are the client. If we remove listeners as a client,
        // ALL of the client logic will be lost. The server is reactive, so listeners are added to connections as needed (instead of before startup)
        closeConnections(true);

        // make sure we're not waiting on registration
        registrationCompleted();

        // for the CLIENT only, we clear these connections! (the server only clears them on shutdown)
        shutdownChannels();

        connection = null;
    }

    /**
     * Internal call to abort registration if the shutdown command is issued during channel registration.
     */
    void abortRegistration() {
        // make sure we're not waiting on registration
        registrationCompleted();
    }
}
