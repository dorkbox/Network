package dorkbox.network.connection.wrapper;

import java.net.InetSocketAddress;

public
class UdpWrapper {

    private final Object object;
    private final InetSocketAddress remoteAddress;

    public
    UdpWrapper(Object object, InetSocketAddress remoteAddress2) {
        this.object = object;
        this.remoteAddress = remoteAddress2;
    }

    public
    Object object() {
        return this.object;
    }

    public
    InetSocketAddress remoteAddress() {
        return this.remoteAddress;
    }
}
