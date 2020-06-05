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

import dorkbox.network.ClientConfiguration;
import dorkbox.network.connection.bridge.ConnectionBridge;
import dorkbox.util.exceptions.SecurityException;

/**
 * This serves the purpose of making sure that specific methods are not available to the end user.
 */
public
class EndPointClient extends EndPoint<ClientConfiguration> {

    // is valid when there is a connection to the server, otherwise it is null
    protected volatile Connection connection;

    private CountDownLatch registration;

    private final Object bootstrapLock = new Object();
    protected List<BootstrapWrapper> bootstraps = new LinkedList<BootstrapWrapper>();
    private Iterator<BootstrapWrapper> bootstrapIterator;

    protected volatile int connectionTimeout = 5000; // default is 5 seconds

    private volatile long previousClosedConnectionActivity = 0;

    private volatile ConnectionBridge connectionBridgeFlushAlways;


    protected
    EndPointClient(ClientConfiguration config) throws SecurityException, IOException {
        super(config);
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

                return self;
            }

            @Override
            public
            ConnectionPoint TCP(Object message) {
                ConnectionPoint tcp = connection.TCP(message);

                // needed to place back-pressure when writing too much data to the connection. Will create deadlocks if called from
                // INSIDE the event loop
                connection.controlBackPressure(tcp);

                return tcp;
            }

            @Override
            public
            ConnectionPoint UDP(Object message) {
                ConnectionPoint udp = connection.UDP(message);

                // needed to place back-pressure when writing too much data to the connection. Will create deadlocks if called from
                // INSIDE the event loop
                connection.controlBackPressure(udp);
                return udp;
            }

            @Override
            public
            Ping ping() {
                Ping ping = connection.ping();
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


    public
    ConnectionPoint send(final Object message) {
        ConnectionPoint send = connection.send(message);

        // needed to place back-pressure when writing too much data to the connection. Will create deadlocks if called from
        // INSIDE the event loop
        ((ConnectionImpl)connection).controlBackPressure(send);
        return send;
    }

    public
    ConnectionPoint send(final Object message, final byte priority) {
        return null;
    }

    public
    ConnectionPoint sendUnreliable(final Object message) {
        return null;
    }

    public
    ConnectionPoint sendUnreliable(final Object message, final byte priority) {
        return null;
    }

    public
    Ping ping() {
        return null;
    }

    // /**
    //  * Closes all connections ONLY (keeps the client running).  To STOP the client, use stop().
    //  * <p/>
    //  * This is used, for example, when reconnecting to a server.
    //  */
    // protected
    // void closeConnection() {
    //     if (isConnected.get()) {
    //         // make sure we're not waiting on registration
    //         stopRegistration();
    //
    //         // for the CLIENT only, we clear these connections! (the server only clears them on shutdown)
    //
    //         // stop does the same as this + more.  Only keep the listeners for connections IF we are the client. If we remove listeners as a client,
    //         // ALL of the client logic will be lost. The server is reactive, so listeners are added to connections as needed (instead of before startup)
    //         connectionManager.closeConnections(true);
    //
    //         // Sometimes there might be "lingering" connections (ie, halfway though registration) that need to be closed.
    //         registrationWrapper.clearSessions();
    //
    //
    //         closeConnections(true);
    //         shutdownAllChannels();
    //         // shutdownEventLoops();  we don't do this here!
    //
    //         connection = null;
    //         isConnected.set(false);
    //
    //         previousClosedConnectionActivity = System.nanoTime();
    //     }
    // }

    /**
     * Internal call to abort registration if the shutdown command is issued during channel registration.
     */
    void abortRegistration() {
        // make sure we're not waiting on registration
        stopRegistration();
    }
    //
    // @Override
    // protected
    // void shutdownChannelsPre() {
    //     closeConnection();
    //
    //     // this calls connectionManager.stop()
    //     super.shutdownChannelsPre();
    // }
}
