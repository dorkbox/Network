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

import dorkbox.network.Client;
import dorkbox.network.Configuration;
import dorkbox.network.connection.bridge.ConnectionBridge;
import dorkbox.network.connection.bridge.ConnectionBridgeFlushAlways;
import dorkbox.util.exceptions.InitializationException;
import dorkbox.util.exceptions.SecurityException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    private volatile ConnectionBridgeFlushAlways connectionBridgeFlushAlways;


    public
    EndPointClient(Configuration options) throws InitializationException, SecurityException, IOException {
        super(Client.class, options);
    }

    protected
    void registerNextProtocol() {
        new Thread(this, "Bootstrap registration").start();
    }

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
                                                           "Could not connect to the " + bootstrapWrapper.type + " server on port: " +
                                                           bootstrapWrapper.port,
                                                           e);
                throw new IllegalArgumentException(errorMessage);
            }

            if (!future.isSuccess()) {
                String errorMessage = stopWithErrorMessage(logger2,
                                                           "Could not connect to the " + bootstrapWrapper.type + " server on port: " +
                                                           bootstrapWrapper.port,
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
    void connectionConnected0(ConnectionImpl connection) {
        // invokes the listener.connection() method, and initialize the connection channels with whatever extra info they might need.
        super.connectionConnected0(connection);

        // notify the registration we are done!
        synchronized (this.registrationLock) {
            this.registrationLock.notify();
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
        ConnectionBridgeFlushAlways connectionBridgeFlushAlways2 = this.connectionBridgeFlushAlways;
        if (connectionBridgeFlushAlways2 == null) {
            ConnectionBridge clientBridge = this.connection.send();
            this.connectionBridgeFlushAlways = new ConnectionBridgeFlushAlways(clientBridge);
        }

        return this.connectionBridgeFlushAlways;
    }

    /**
     * Internal call to abort registration if the shutdown command is issued during channel registration.
     */
    void abortRegistration() {
        synchronized (this.registrationLock) {
            this.registrationLock.notify();
        }
        stop();
    }
}
