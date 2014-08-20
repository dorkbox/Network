package dorkbox.network.connection;


public interface ConnectionBridgeServer {

    /**
     * Sends the object all server connections over the network using TCP. (or
     * via LOCAL when it's a local channel).
     */
    public void TCP(Object message);

    /**
     * Sends the object all server connections over the network using UDP (or
     * via LOCAL when it's a local channel).
     */
    public void UDP(Object message);

    /**
     * Sends the object all server connections over the network using UDT. (or
     * via LOCAL when it's a local channel).
     */
    public void UDT(Object message);

    /**
     * Exposes methods to send the object to all server connections (except the specified one)
     * over the network. (or via LOCAL when it's a local channel).
     */
    public ConnectionExceptsBridgeServer except();
}
