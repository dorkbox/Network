package dorkbox.network.connection;

import dorkbox.network.connection.ping.Ping;

public class ConnectionBridgeFlushAlways implements ConnectionBridge {

    private final ConnectionBridge originalBridge;

    public ConnectionBridgeFlushAlways(ConnectionBridge originalBridge) {
        this.originalBridge = originalBridge;
    }

    @Override
    public void self(Object message) {
        originalBridge.self(message);
        flush();
    }

    @Override
    public ConnectionPoint TCP(Object message) {
        ConnectionPoint connection = originalBridge.TCP(message);
        connection.flush();
        return connection;
    }

    @Override
    public ConnectionPoint UDP(Object message) {
        ConnectionPoint connection = originalBridge.UDP(message);
        connection.flush();
        return connection;
    }

    @Override
    public ConnectionPoint UDT(Object message) {
        ConnectionPoint connection = originalBridge.UDT(message);
        connection.flush();
        return connection;
    }

    @Override
    public Ping ping() {
        Ping ping = originalBridge.ping();
        flush();
        return ping;
    }

    @Override
    public void flush() {
        originalBridge.flush();
    }

}
