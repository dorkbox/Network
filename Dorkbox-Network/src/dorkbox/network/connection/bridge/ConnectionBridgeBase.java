package dorkbox.network.connection.bridge;

import dorkbox.network.connection.ConnectionPoint;



public interface ConnectionBridgeBase {
    /**
     * Sends the message to other listeners INSIDE this endpoint. It does not
     * send it to a remote address.
     */
    public void self(Object message);

    /**
     * Sends the message over the network using TCP. (or via LOCAL when it's a
     * local channel).
     */
    public ConnectionPoint TCP(Object message);

    /**
     * Sends the message over the network using UDP (or via LOCAL when it's a
     * local channel).
     */
    public ConnectionPoint UDP(Object message);

    /**
     * Sends the message over the network using UDT. (or via LOCAL when it's a
     * local channel).
     */
    public ConnectionPoint UDT(Object message);
}
