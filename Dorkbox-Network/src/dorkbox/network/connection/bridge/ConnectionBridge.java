package dorkbox.network.connection.bridge;

import dorkbox.network.connection.Ping;



public interface ConnectionBridge extends ConnectionBridgeBase {
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
