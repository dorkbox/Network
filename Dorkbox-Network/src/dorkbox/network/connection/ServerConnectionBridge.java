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

import dorkbox.network.connection.bridge.ConnectionBridgeServer;
import dorkbox.network.connection.bridge.ConnectionExceptSpecifiedBridgeServer;

import java.util.Collection;

public
class ServerConnectionBridge<C extends Connection>
                implements ConnectionPoint, ConnectionBridgeServer<C>, ConnectionExceptSpecifiedBridgeServer<C> {

    private final ConnectionManager<C> connectionManager;

    public
    ServerConnectionBridge(final  ConnectionManager<C> connectionManager) {
        this.connectionManager = connectionManager;
    }

    /**
     * Not implemented, since this would cause horrendous problems.
     *
     * @see dorkbox.network.connection.ConnectionPoint#waitForWriteToComplete()
     */
    @Override
    public
    void waitForWriteToComplete() {
        throw new UnsupportedOperationException("Method not implemented");
    }

    /**
     * This will flush the data from EVERY connection on this server.
     * <p/>
     * THIS WILL BE SLOW!
     *
     * @see dorkbox.network.connection.ConnectionPoint#flush()
     */
    @Override
    public
    void flush() {
        Collection<C> connections0 = this.connectionManager.getConnections0();
        for (C c : connections0) {
            c.send()
             .flush();
        }
    }

    /**
     * Exposes methods to send the object to all server connections (except the specified one) over the network. (or via LOCAL when it's a
     * local channel).
     */
    @Override
    public
    ConnectionExceptSpecifiedBridgeServer<C> except() {
        return this;
    }

    /**
     * Sends the object to all server connections (except the specified one) over the network using TCP. (or via LOCAL when it's a local
     * channel).
     */
    @Override
    public
    void TCP(final C connection, final Object message) {
        Collection<C> connections0 = this.connectionManager.getConnections0();
        for (C c : connections0) {
            if (c != connection) {
                c.send()
                 .TCP(message);
            }
        }
    }

    /**
     * Sends the object to all server connections (except the specified one) over the network using UDP (or via LOCAL when it's a local
     * channel).
     */
    @Override
    public
    void UDP(final C connection, final Object message) {
        Collection<C> connections0 = this.connectionManager.getConnections0();
        for (C c : connections0) {
            if (c != connection) {
                c.send()
                 .UDP(message);
            }
        }
    }

    /**
     * Sends the object to all server connections (except the specified one) over the network using UDT. (or via LOCAL when it's a local
     * channel).
     */
    @Override
    public
    void UDT(final C connection, final Object message) {
        Collection<C> connections0 = this.connectionManager.getConnections0();
        for (C c : connections0) {
            if (c != connection) {
                c.send()
                 .UDT(message);
            }
        }
    }

    /**
     * Sends the message to other listeners INSIDE this endpoint for EVERY connection. It does not send it to a remote address.
     */
    @Override
    public
    void self(final Object message) {
        Collection<C> connections0 = this.connectionManager.getConnections0();
        for (C c : connections0) {
            this.connectionManager.notifyOnMessage(c, message);
        }
    }

    /**
     * Sends the object all server connections over the network using TCP. (or via LOCAL when it's a local channel).
     */
    @Override
    public
    ConnectionPoint TCP(final Object message) {
        Collection<C> connections0 = this.connectionManager.getConnections0();
        for (C c : connections0) {
            c.send()
             .TCP(message);
        }

        return this;
    }

    /**
     * Sends the object all server connections over the network using UDP. (or via LOCAL when it's a local channel).
     */
    @Override
    public
    ConnectionPoint UDP(final Object message) {
        Collection<C> connections0 = this.connectionManager.getConnections0();
        for (C c : connections0) {
            c.send()
             .UDP(message);
        }

        return this;
    }

    /**
     * Sends the object all server connections over the network using UDT. (or via LOCAL when it's a local channel).
     */
    @Override
    public
    ConnectionPoint UDT(final Object message) {
        Collection<C> connections0 = this.connectionManager.getConnections0();
        for (C c : connections0) {
            c.send()
             .UDT(message);
        }

        return this;
    }
}
