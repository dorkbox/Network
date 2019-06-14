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
import java.util.concurrent.locks.LockSupport;

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

    private volatile long previousClosedConnectionActivity = 0;

    private volatile ConnectionBridge connectionBridgeFlushAlways;


    public
    EndPointClient(Configuration config) throws SecurityException {
        super(Client.class, config);
    }

    /**
     * Internal call by the pipeline to start the client registering the different session protocols.
     */
    protected
    void startRegistration() throws IOException {
        // make sure we WAIT 100ms BEFORE starting registrations again IF we recently called close...
        while (previousClosedConnectionActivity > 0 &&
               System.nanoTime() - previousClosedConnectionActivity < TimeUnit.MILLISECONDS.toNanos(200)) {
            LockSupport.parkNanos(100L); // wait
        }


        synchronized (bootstrapLock) {
            // always reset everything.
            registration = new CountDownLatch(1);
            bootstrapIterator = bootstraps.iterator();

            doRegistration();
        }

        // have to BLOCK (must be outside of the synchronize call), we don't want the client to run before registration is complete
        try {
            if (connectionTimeout > 0) {
                if (!registration.await(connectionTimeout, TimeUnit.MILLISECONDS)) {
                    closeConnection();
                    throw new IOException("Unable to complete registration within '" + connectionTimeout + "' milliseconds");
                }
            }
            else {
                registration.await();
            }
        } catch (InterruptedException e) {
            if (connectionTimeout > 0) {
                throw new IOException("Unable to complete registration within '" + connectionTimeout + "' milliseconds", e);
            }
            else {
                throw new IOException("Unable to complete registration.", e);
            }
        }
    }

    /**
     * Internal call by the pipeline to notify the client to continue registering the different session protocols.
     *
     * @return true if we are done registering bootstraps
     */
    @Override
    protected
    void startNextProtocolRegistration() {
        logger.trace("Registered protocol from server.");

        synchronized (bootstrapLock) {
            if (hasMoreRegistrations()) {
                doRegistration();
            }
        }
    }

    /**
     * Internal call by the pipeline to check if the client has more protocol registrations to complete.
     *
     * @return true if there are more registrations to process, false if we are 100% done with all types to register (TCP/UDP/etc)
     */
    @Override
    protected
    boolean hasMoreRegistrations() {
        synchronized (bootstrapLock) {
            return !(bootstrapIterator == null || !bootstrapIterator.hasNext());
        }
    }

    /**
     * this is called by 2 threads. The startup thread, and the registration-in-progress thread
     *
     * NOTE: must be inside synchronize(bootstrapLock)!
     */
    private
    void doRegistration() {
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
            String errorMessage =
                    "Could not connect to the " + bootstrapWrapper.type + " server at " + bootstrapWrapper.address + " on port: " +
                    bootstrapWrapper.port;
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

            // extra space here is so it aligns with "Connecting to server:"
            String errorMessage = "Connection refused  :" + bootstrapWrapper.address + " at " + bootstrapWrapper.type + " port: " +
                                  bootstrapWrapper.port;

            if (cause instanceof java.net.ConnectException) {
                if (cause.getMessage()
                         .contains("refused")) {
                    logger.error(errorMessage);
                }

            }
            else {
                logger.error(errorMessage, cause);
            }

            return;
        }

        logger.trace("Waiting for registration from server.");

        manageForShutdown(future);
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
            ConnectionPoint self(Object message) {
                ConnectionPoint self = connection.self(message);
                connection.flush();

                return self;
            }

            @Override
            public
            ConnectionPoint TCP(Object message) {
                ConnectionPoint tcp = connection.TCP(message);
                connection.flush();

                // needed to place back-pressure when writing too much data to the connection. Will create deadlocks if called from
                // INSIDE the event loop
                connection.controlBackPressure(tcp);

                return tcp;
            }

            @Override
            public
            ConnectionPoint UDP(Object message) {
                ConnectionPoint udp = connection.UDP(message);
                connection.flush();

                // needed to place back-pressure when writing too much data to the connection. Will create deadlocks if called from
                // INSIDE the event loop
                connection.controlBackPressure(udp);
                return udp;
            }

            @Override
            public
            Ping ping() {
                Ping ping = connection.ping();
                connection.flush();
                return ping;
            }
        };

        //noinspection unchecked
        this.connection = connection;

        stopRegistration();

        // invokes the listener.connection() method, and initialize the connection channels with whatever extra info they might need.
        // This will also start the RMI (if necessary) initialization/creation of objects
        super.connectionConnected0(connection);
    }


    private
    void stopRegistration() {
        // make sure we're not waiting on registration
        synchronized (bootstrapLock) {
            // we're done with registration, so no need to keep this around
            bootstrapIterator = null;
            while (registration.getCount() > 0) {
                registration.countDown();
            }
        }
    }

    /**
     * AFTER registration is complete, if we are UDP only -- setup a heartbeat (must be the larger of 2x the idle timeout OR 10 seconds)
     *
     * If the server disconnects because of a heartbeat failure, the client has to be made aware of this when it tries to send data again
     * (and it must go through it's entire reconnect protocol)
     */
    protected
    void startUdpHeartbeat() {
        // TODO...
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

    @Override
    public
    ConnectionPoint send(final Object message) {
        ConnectionPoint send = connection.send(message);
        send.flush();

        // needed to place back-pressure when writing too much data to the connection. Will create deadlocks if called from
        // INSIDE the event loop
        ((ConnectionImpl)connection).controlBackPressure(send);
        return send;
    }

    /**
     * Closes all connections ONLY (keeps the client running).  To STOP the client, use stop().
     * <p/>
     * This is used, for example, when reconnecting to a server.
     */
    protected
    void closeConnection() {
        if (isConnected.get()) {
            // make sure we're not waiting on registration
            stopRegistration();

            // for the CLIENT only, we clear these connections! (the server only clears them on shutdown)

            // stop does the same as this + more.  Only keep the listeners for connections IF we are the client. If we remove listeners as a client,
            // ALL of the client logic will be lost. The server is reactive, so listeners are added to connections as needed (instead of before startup)
            connectionManager.closeConnections(true);

            // Sometimes there might be "lingering" connections (ie, halfway though registration) that need to be closed.
            registrationWrapper.clearSessions();


            closeConnections(true);
            shutdownAllChannels();
            // shutdownEventLoops();  we don't do this here!

            connection = null;
            isConnected.set(false);

            previousClosedConnectionActivity = System.nanoTime();
        }
    }

    /**
     * Internal call to abort registration if the shutdown command is issued during channel registration.
     */
    void abortRegistration() {
        // make sure we're not waiting on registration
        stopRegistration();
    }

    @Override
    protected
    void shutdownChannelsPre() {
        closeConnection();

        // this calls connectionManager.stop()
        super.shutdownChannelsPre();
    }
}
