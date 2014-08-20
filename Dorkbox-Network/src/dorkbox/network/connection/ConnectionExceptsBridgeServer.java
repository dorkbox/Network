package dorkbox.network.connection;


public interface ConnectionExceptsBridgeServer {

    /**
     * Sends the object to all server connections (except the specified one)
     * over the network using TCP. (or via LOCAL when it's a local channel).
     */
    public void TCP(Connection connection, Object message);

    /**
     * Sends the object to all server connections (except the specified one)
     * over the network using UDP (or via LOCAL when it's a local channel).
     */
    public void UDP(Connection connection, Object message);

    /**
     * Sends the object to all server connections (except the specified one)
     * over the network using UDT. (or via LOCAL when it's a local channel).
     */
    public void UDT(Connection connection, Object message);
}
