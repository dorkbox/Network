package dorkbox.network.connection;

import java.util.Collection;

public class ServerConnectionBridge implements ConnectionBridgeServer, ConnectionExceptSpecifiedBridgeServer {

    private final ConnectionManager connectionManager;

    public ServerConnectionBridge(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    /**
     * Sends the object all server connections over the network using TCP. (or
     * via LOCAL when it's a local channel).
     */
    @Override
    public void TCP(Object message) {
        Collection<Connection> connections0 = connectionManager.getConnections0();
        for (Connection c : connections0) {
            c.send().TCP(message);
        }
    }

    /**
     * Sends the object all server connections over the network using UDP. (or
     * via LOCAL when it's a local channel).
     */
    @Override
    public void UDP(Object message) {
        Collection<Connection> connections0 = connectionManager.getConnections0();
        for (Connection c : connections0) {
            c.send().UDP(message);
        }
    }

    /**
     * Sends the object all server connections over the network using UDT. (or
     * via LOCAL when it's a local channel).
     */
    @Override
    public void UDT(Object message) {
        Collection<Connection> connections0 = connectionManager.getConnections0();
        for (Connection c : connections0) {
            c.send().UDT(message);
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
        Collection<Connection> connections0 = connectionManager.getConnections0();
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
        Collection<Connection> connections0 = connectionManager.getConnections0();
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
        Collection<Connection> connections0 = connectionManager.getConnections0();
        for (Connection c : connections0) {
            if (c != connection) {
                c.send().UDT(message);
            }
        }
    }
}
