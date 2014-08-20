package dorkbox.network.connection.ping;

/**
 * Internal message to determine round trip time.
 */
public class PingMessage {
    public int           id;
    public boolean       isReply;

    /** The ping round-trip time in milliseconds */
    public transient int time;
}