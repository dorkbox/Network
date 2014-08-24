package dorkbox.network.connection;

import dorkbox.network.ConnectionOptions;
import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.SecurityException;

/**
 * This serves the purpose of making sure that specific methods are not available to the end user.
 */
public class EndPointServer extends EndPointWithSerialization {

    private ServerConnectionBridge serverConnections;

    public EndPointServer(String name, ConnectionOptions options) throws InitializationException, SecurityException {
        super(name, options);

        serverConnections = new ServerConnectionBridge(connectionManager);
    }

    /**
     * Expose methods to send objects to a destination.
     */
    public ConnectionBridgeServer send() {
        return serverConnections;
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
    final ConnectionManager addListenerManager(Connection connection) {
        return connectionManager.addListenerManager(connection);
    }

    /**
     * When called by a server, NORMALLY listeners are added at the GLOBAL level (meaning, I add one listener,
     * and ALL connections are notified of that listener.
     * <br>
     * It is POSSIBLE to remove a server-connection 'local' listener (via connection.removeListener), meaning that ONLY
     * that listener attached to the connection is removed
     *
     * This removes the listener manager for that specific connection
     */
    final void removeListenerManager(Connection connection) {
        connectionManager.removeListenerManager(connection);
    }
}
