package dorkbox.network.connection;

/**
 * Internal message to determine round trip time.
 */
class PingMessage {
    public int     id;
    public boolean isReply;
}