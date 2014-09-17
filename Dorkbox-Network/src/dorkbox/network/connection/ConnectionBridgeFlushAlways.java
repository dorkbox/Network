package dorkbox.network.connection;


public class ConnectionBridgeFlushAlways implements ConnectionBridge {

    private final ConnectionBridge originalBridge;

    public ConnectionBridgeFlushAlways(ConnectionBridge originalBridge) {
        this.originalBridge = originalBridge;
    }

    @Override
    public void self(Object message) {
        this.originalBridge.self(message);
        flush();
    }

    @Override
    public ConnectionPoint TCP(Object message) {
        ConnectionPoint connection = this.originalBridge.TCP(message);
        connection.flush();
        return connection;
    }

    @Override
    public ConnectionPoint UDP(Object message) {
        ConnectionPoint connection = this.originalBridge.UDP(message);
        connection.flush();
        return connection;
    }

    @Override
    public ConnectionPoint UDT(Object message) {
        ConnectionPoint connection = this.originalBridge.UDT(message);
        connection.flush();
        return connection;
    }

    @Override
    public Ping ping() {
        Ping ping = this.originalBridge.ping();
        flush();
        return ping;
    }

    @Override
    public void flush() {
        this.originalBridge.flush();
    }
}
