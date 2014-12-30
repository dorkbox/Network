package dorkbox.network.connection;

import java.util.Collection;

import dorkbox.network.connection.bridge.ConnectionBridgeServer;
import dorkbox.network.connection.bridge.ConnectionExceptSpecifiedBridgeServer;

public class ServerConnectionBridge implements ConnectionPoint, ConnectionBridgeServer, ConnectionExceptSpecifiedBridgeServer {

    private final ConnectionManager connectionManager;

    public ServerConnectionBridge(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    /**
     * Sends the object all server connections over the network using TCP. (or
     * via LOCAL when it's a local channel).
     */
    @Override
    public ConnectionPoint TCP(Object message) {
        Collection<Connection> connections0 = this.connectionManager.getConnections0();
        for (Connection c : connections0) {
            c.send().TCP(message);
        }

        return this;
    }

    /**
     * Sends the object all server connections over the network using UDP. (or
     * via LOCAL when it's a local channel).
     */
    @Override
    public ConnectionPoint UDP(Object message) {
        Collection<Connection> connections0 = this.connectionManager.getConnections0();
        for (Connection c : connections0) {
            c.send().UDP(message);
        }

        return this;
    }

    /**
     * Sends the object all server connections over the network using UDT. (or
     * via LOCAL when it's a local channel).
     */
    @Override
    public ConnectionPoint UDT(Object message) {
        Collection<Connection> connections0 = this.connectionManager.getConnections0();
        for (Connection c : connections0) {
            c.send().UDT(message);
        }

        return this;
    }

    /**
     * Not implemented, since this would cause horrendous problems.
     *
     * @see dorkbox.network.connection.ConnectionPoint#waitForWriteToComplete()
     */
    @Override
    public void waitForWriteToComplete() {
        throw new UnsupportedOperationException("Method not implemented");
    }

    /**
     * This will flush the data from EVERY connection on this server.
     * <p>
     * THIS WILL BE SLOW!
     *
     * @see dorkbox.network.connection.ConnectionPoint#flush()
     */
    @Override
    public void flush() {
        Collection<Connection> connections0 = this.connectionManager.getConnections0();
        for (Connection c : connections0) {
            c.send().flush();
        }
    }

    /**
     * Exposes methods to send the object to all server connections (except the specified one)
     * over the network. (or via LOCAL when it's a local channel).
     */
    @Override
    public ConnectionExceptSpecifiedBridgeServer except() {
        return this;
    }

    /**
     * Sends the object to all server connections (except the specified one)
     * over the network using TCP. (or via LOCAL when it's a local channel).
     */
    @Override
    public void TCP(Connection connection, Object message) {
        Collection<Connection> connections0 = this.connectionManager.getConnections0();
        for (Connection c : connections0) {
            if (c != connection) {
                c.send().TCP(message);
            }
        }
    }

    /**
     * Sends the object to all server connections (except the specified one)
     * over the network using UDP (or via LOCAL when it's a local channel).
     */
    @Override
    public void UDP(Connection connection, Object message) {
        Collection<Connection> connections0 = this.connectionManager.getConnections0();
        for (Connection c : connections0) {
            if (c != connection) {
                c.send().UDP(message);
            }
        }
    }

    /**
     * Sends the object to all server connections (except the specified one)
     * over the network using UDT. (or via LOCAL when it's a local channel).
     */
    @Override
    public void UDT(Connection connection, Object message) {
        Collection<Connection> connections0 = this.connectionManager.getConnections0();
        for (Connection c : connections0) {
            if (c != connection) {
                c.send().UDT(message);
            }
        }
    }

    /**
     * Sends the message to other listeners INSIDE this endpoint for EVERY connection. It does not
     * send it to a remote address.
     */
    @Override
    public void self(Object message) {
        Collection<Connection> connections0 = this.connectionManager.getConnections0();
        for (Connection c : connections0) {
            this.connectionManager.notifyOnMessage(c, message);
        }
    }
}
