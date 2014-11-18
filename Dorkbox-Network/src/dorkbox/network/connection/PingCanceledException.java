package dorkbox.network.connection;

import dorkbox.network.util.exceptions.NetException;

public class PingCanceledException extends NetException {

    private static final long serialVersionUID = 9045461384091038605L;

    public PingCanceledException() {
        super("Ping request has been canceled.");
    }
}
