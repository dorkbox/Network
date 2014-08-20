package dorkbox.network.connection.idle;


public interface IdleBridge {
    /**
     * Sends the object over the network using TCP (or via LOCAL when it's a
     * local channel) when the socket is in an "idle" state.
     */
    public void TCP();

    /**
     * Sends the object over the network using UDP when the socket is in an
     * "idle" state.
     */
    public void UDP();

    /**
     * Sends the object over the network using UDT (or via LOCAL when it's a
     * local channel) when the socket is in an "idle" state.
     */
    public void UDT();
}
