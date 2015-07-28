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

import dorkbox.network.Configuration;
import dorkbox.network.Server;
import dorkbox.network.connection.bridge.ConnectionBridgeServer;
import dorkbox.util.exceptions.InitializationException;
import dorkbox.util.exceptions.SecurityException;

import java.io.IOException;

/**
 * This serves the purpose of making sure that specific methods are not available to the end user.
 */
public
class EndPointServer<C extends Connection> extends EndPoint<C> {

    private final ServerConnectionBridge serverConnections;

    public
    EndPointServer(Configuration options) throws InitializationException, SecurityException, IOException {
        super(Server.class, options);

        this.serverConnections = new ServerConnectionBridge(this.connectionManager);
    }

    /**
     * Expose methods to send objects to a destination.
     */
    @Override
    public
    ConnectionBridgeServer send() {
        return this.serverConnections;
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
    ConnectionManager<C> addListenerManager(C connection) {
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
    void removeListenerManager(C connection) {
        this.connectionManager.removeListenerManager(connection);
    }
}
