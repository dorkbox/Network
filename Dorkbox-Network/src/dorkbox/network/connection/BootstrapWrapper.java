package dorkbox.network.connection;

import io.netty.bootstrap.Bootstrap;

public class BootstrapWrapper {
    public final String type;
    public final Bootstrap bootstrap;
    public final int port;

    public BootstrapWrapper(String type, int port, Bootstrap bootstrap) {
        this.type = type;
        this.port = port;
        this.bootstrap = bootstrap;
    }

    @Override
    public String toString() {
        return "BootstrapWrapper [type=" + this.type + ", port=" + this.port + "]";
    }
}
