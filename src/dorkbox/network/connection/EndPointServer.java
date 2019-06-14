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

import java.net.InetSocketAddress;

import dorkbox.network.Configuration;
import dorkbox.network.Server;
import dorkbox.network.connection.bridge.ConnectionBridgeServer;
import dorkbox.network.connection.registration.UpgradeType;
import dorkbox.util.exceptions.SecurityException;

/**
 * This serves the purpose of making sure that specific methods are not available to the end user.
 */
public
class EndPointServer extends EndPoint {

    public
    EndPointServer(final Configuration config) throws SecurityException {
        super(Server.class, config);
    }

    /**
     * Expose methods to send objects to a destination.
     */
    @Override
    public
    ConnectionBridgeServer send() {
        return this.connectionManager;
    }

    /**
     * Safely sends objects to a destination (such as a custom object or a standard ping). This will automatically choose which protocol
     * is available to use. If you want specify the protocol, use {@link #send()}, followed by the protocol you wish to use.
     */
    @Override
    public
    ConnectionPoint send(final Object message) {
        return this.connectionManager.send(message);
    }


    /**
     * When called by a server, NORMALLY listeners are added at the GLOBAL level (meaning, I add one listener,
     * and ALL connections are notified of that listener.
     * <br>
     * It is POSSIBLE to add a server-connection 'local' listener (via connection.addListener), meaning that ONLY
     * that listener attached to the connection is notified on that event (ie, admin type listeners)
     *
     * @return a newly created listener manager for the connection
     */
    final
    ConnectionManager addListenerManager(final Connection connection) {
        return this.connectionManager.addListenerManager(connection);
    }

    /**
     * When called by a server, NORMALLY listeners are added at the GLOBAL level (meaning, I add one listener,
     * and ALL connections are notified of that listener.
     * <br>
     * It is POSSIBLE to remove a server-connection 'local' listener (via connection.removeListener), meaning that ONLY
     * that listener attached to the connection is removed
     * <p/>
     * This removes the listener manager for that specific connection
     */
    final
    void removeListenerManager(final Connection connection) {
        this.connectionManager.removeListenerManager(connection);
    }

    /**
     * Adds a custom connection to the server.
     * <p>
     * This should only be used in situations where there can be DIFFERENT types of connections (such as a 'web-based' connection) and
     * you want *this* server instance to manage listeners + message dispatch
     *
     * @param connection the connection to add
     */
    public
    void add(Connection connection) {
        connectionManager.addConnection0(connection);
    }

    /**
     * Removes a custom connection to the server.
     * <p>
     * This should only be used in situations where there can be DIFFERENT types of connections (such as a 'web-based' connection) and
     * you want *this* server instance to manage listeners + message dispatch
     *
     * @param connection the connection to remove
     */
    public
    void remove(Connection connection) {
        connectionManager.removeConnection(connection);
    }

    byte getConnectionUpgradeType(final InetSocketAddress remoteAddress) {
        // TODO, crypto/compression based on ip/range of remote address
        return UpgradeType.ENCRYPT;
    }
}
