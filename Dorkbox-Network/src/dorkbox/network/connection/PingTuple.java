package dorkbox.network.connection;


public class PingTuple<C extends Connection> {
    public C connection;
    public int responseTime;

    public PingTuple(C connection, int responseTime) {
        this.connection = connection;
        this.responseTime = responseTime;
    }
}
