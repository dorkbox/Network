package dorkbox.network.connection;



public interface ConnectionBridge {
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

    /**
     * Sends a "ping" packet, trying UDP, then UDT, then TCP (in that order) to measure <b>ROUND TRIP</b> time to the remote connection.
     *
     * @return Ping can have a listener attached, which will get called when the ping returns.
     */
    public Ping ping();

    /**
     * Flushes the contents of the TCP/UDP/UDT/etc pipes to the actual transport.
     */
    public void flush();
}
