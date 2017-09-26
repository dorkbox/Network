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
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;

import dorkbox.network.Client;
import dorkbox.network.Configuration;
import dorkbox.network.connection.bridge.ConnectionBridge;
import dorkbox.util.exceptions.InitializationException;
import dorkbox.util.exceptions.SecurityException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;

/**
 * This serves the purpose of making sure that specific methods are not available to the end user.
 */
public
class EndPointClient<C extends Connection> extends EndPoint<C> implements Runnable {

    protected C connection;

    protected final Object registrationLock = new Object();

    protected final AtomicInteger connectingBootstrap = new AtomicInteger(0);
    protected List<BootstrapWrapper> bootstraps = new LinkedList<BootstrapWrapper>();
    protected volatile int connectionTimeout = 5000; // default
    protected volatile boolean registrationComplete = false;
    private volatile boolean rmiInitializationComplete = false;

    private volatile ConnectionBridge connectionBridgeFlushAlways;


    public
    EndPointClient(Configuration options) throws InitializationException, SecurityException, IOException {
        super(Client.class, options);
    }

    protected
    void registerNextProtocol() {
        this.registrationComplete = false; // always reset.

        new Thread(this, "Bootstrap registration").start();
    }

    @SuppressWarnings("AutoBoxing")
    @Override
    public
    void run() {
        synchronized (this.connectingBootstrap) {
            int bootstrapToRegister = this.connectingBootstrap.getAndIncrement();

            BootstrapWrapper bootstrapWrapper = this.bootstraps.get(bootstrapToRegister);

            ChannelFuture future;

            if (this.connectionTimeout != 0) {
                // must be before connect
                bootstrapWrapper.bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, this.connectionTimeout);
            }

            Logger logger2 = this.logger;
            try {
                // UDP : When this is CONNECT, a udp socket will ONLY accept UDP traffic from the remote address (ip/port combo).
                //       If the reply isn't from the correct port, then the other end will receive a "Port Unreachable" exception.

                future = bootstrapWrapper.bootstrap.connect();
                future.await();
            } catch (Exception e) {
                String errorMessage = stopWithErrorMessage(logger2,
                                                           "Could not connect to the " + bootstrapWrapper.type + " server at " +
                                                           bootstrapWrapper.address + " on port: " + bootstrapWrapper.port,
                                                           e);
                throw new IllegalArgumentException(errorMessage);
            }

            if (!future.isSuccess()) {
                String errorMessage = stopWithErrorMessage(logger2,
                                                           "Could not connect to the " + bootstrapWrapper.type + " server at " +
                                                           bootstrapWrapper.address + " on port: " + bootstrapWrapper.port,
                                                           future.cause());
                throw new IllegalArgumentException(errorMessage);
            }

            if (logger2.isTraceEnabled()) {
                logger2.trace("Waiting for registration from server.");
            }
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
        synchronized (this.connectingBootstrap) {
            this.registrationComplete = this.connectingBootstrap.get() == this.bootstraps.size();
            if (!this.registrationComplete) {
                registerNextProtocol();
            }
        }

        Logger logger2 = this.logger;
        if (logger2.isTraceEnabled()) {
            logger2.trace("Registered protocol from server.");
        }

        // only let us continue with connections (this starts up the client/server implementations) once ALL of the
        // bootstraps have connected
        return this.registrationComplete;
    }

    /**
     * Internal (to the networking stack) to notify the client that registration has COMPLETED. This is necessary because the client
     * will BLOCK until it has successfully registered it's connections.
     */
    @Override
    final
    void connectionConnected0(final ConnectionImpl connection) {
        // invokes the listener.connection() method, and initialize the connection channels with whatever extra info they might need.
        super.connectionConnected0(connection);

        this.connectionBridgeFlushAlways = new ConnectionBridge() {
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
            ConnectionPoint UDT(Object message) {
                ConnectionPoint udt = connection.UDT_backpressure(message);
                udt.flush();
                return udt;
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
        this.connection = (C) connection;

        // check if there were any RMI callbacks during the connect phase.
        rmiInitializationComplete = connection.rmiCallbacksIsEmpty();

        // notify the registration we are done!
        synchronized (this.registrationLock) {
            this.registrationLock.notify();
        }
    }

    /**
     * Internal call.
     * <p>
     * RMI methods are usually created during the connection phase. We should wait until they are finished, but ONLY if there is
     * something we need to wait for.
     *
     * This is called AFTER registration is finished.
     */
    protected
    void waitForRmi(final int connectionTimeout) {
        if (!rmiInitializationComplete && connection instanceof ConnectionImpl) {
            ((ConnectionImpl) connection).waitForRmi(connectionTimeout);
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
        return this.connectionBridgeFlushAlways;
    }

    /**
     * Closes all connections ONLY (keeps the client running).  To STOP the client, use stop().
     * <p/>
     * This is used, for example, when reconnecting to a server.
     */
    @Override
    public
    void closeConnections() {
        super.closeConnections();

        // for the CLIENT only, we clear these connections! (the server only clears them on shutdown)
        shutdownChannels();

        // make sure we're not waiting on registration
        registrationComplete = true;
        synchronized (this.registrationLock) {
            this.registrationLock.notify();
        }
        registrationComplete = false;

        // Always unblock the waiting client.connect().
        if (connection instanceof ConnectionImpl) {
            ((ConnectionImpl) connection).rmiCallbacksNotify();
        }
    }

    /**
     * Internal call to abort registration if the shutdown command is issued during channel registration.
     */
    void abortRegistration() {
        synchronized (this.registrationLock) {
            this.registrationLock.notify();
        }

        // Always unblock the waiting client.connect().
        if (connection instanceof ConnectionImpl) {
            ((ConnectionImpl) connection).rmiCallbacksNotify();
        }
        stop();
    }
}
