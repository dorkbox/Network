package dorkbox.network.connection;

public interface ConnectionPoint {

    /**
     * Writes data to the pipe. <b>DOES NOT FLUSH</b> the pipe to the wire!
     */
    public void write(Object object);

    /**
     * Waits for the last write to complete. Useful when sending large amounts of data at once.
     */
    public void waitForWriteToComplete();

    /**
     * Flushes the contents of the TCP/UDP/UDT/etc pipes to the wire.
     */
    public void flush();
}
