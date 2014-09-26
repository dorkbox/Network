package dorkbox.network.connection;

public interface ConnectionPoint {

    /**
     * Waits for the last write to complete. Useful when sending large amounts of data at once.
     */
    public void waitForWriteToComplete();

    /**
     * Flushes the contents of the TCP/UDP/UDT/etc pipes to the wire.
     */
    public void flush();
}
