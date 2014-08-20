package dorkbox.network.connection.ping;


public class PingUtil {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(PingUtil.class);

    //all methods are protected by this!

    private int lastPingID = 0;
    private long lastPingSendTime;

    // if something takes longer than 2billion seconds (signed int) and the connection doesn't time out. We have problems.
    /** The ping round-trip time in nanoseconds */
    private int returnTripTime;

    public PingUtil() {
    }

    public final synchronized PingMessage pingMessage() {
        PingMessage ping = new PingMessage();
        ping.id = lastPingID++;
        lastPingSendTime = System.currentTimeMillis();

        return ping;
    }

    public final synchronized int getReturnTripTime() {
        return returnTripTime;
    }

    public final synchronized void updatePing(PingMessage ping) {
        if (ping.id == lastPingID - 1) {
            ping.time = returnTripTime = (int)(System.currentTimeMillis() - lastPingSendTime);
            logger.trace("Return trip time: {}", returnTripTime);
        }
    }
}
