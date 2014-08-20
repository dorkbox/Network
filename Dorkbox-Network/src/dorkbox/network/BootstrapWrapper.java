package dorkbox.network;

import io.netty.bootstrap.Bootstrap;

class BootstrapWrapper {
    final String type;
    final Bootstrap bootstrap;
    final int port;

    BootstrapWrapper(String type, int port, Bootstrap bootstrap) {
        this.type = type;
        this.port = port;
        this.bootstrap = bootstrap;
    }

    @Override
    public String toString() {
        return "BootstrapWrapper [type=" + this.type + ", port=" + this.port + "]";
    }
}
