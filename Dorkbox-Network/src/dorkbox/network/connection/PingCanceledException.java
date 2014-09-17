package dorkbox.network.connection;

public class PingCanceledException extends RuntimeException {

    private static final long serialVersionUID = 9045461384091038605L;

    public PingCanceledException() {
        super("Ping request has been canceled.");
    }
}
