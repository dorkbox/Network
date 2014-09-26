package dorkbox.network.connection.bridge;


public interface ConnectionBridgeServer extends ConnectionBridgeBase {

    /**
     * Exposes methods to send the object to all server connections (except the specified one)
     * over the network. (or via LOCAL when it's a local channel).
     */
    public ConnectionExceptSpecifiedBridgeServer except();
}
