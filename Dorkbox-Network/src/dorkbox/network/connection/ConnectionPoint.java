package dorkbox.network.connection;

public interface ConnectionPoint {

    /**
     * Writes data to the pipe. <b>DOES NOT FLUSH</b> the pipes to the wire!
     */
    public void write(Object object);

    /**
     * Flushes the contents of the TCP/UDP/UDT/etc pipes to the wire.
     */
    public void flush();
}
