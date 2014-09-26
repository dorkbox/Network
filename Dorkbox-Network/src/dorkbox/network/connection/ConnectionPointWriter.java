package dorkbox.network.connection;

public interface ConnectionPointWriter extends ConnectionPoint {

    /**
     * Writes data to the pipe. <b>DOES NOT FLUSH</b> the pipe to the wire!
     */
    public void write(Object object);
}
