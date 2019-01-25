package dorkbox.network.rmi;

import org.bouncycastle.crypto.params.ParametersWithIV;

import dorkbox.network.connection.ConnectionPoint;
import dorkbox.network.connection.Connection_;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.Listeners;
import dorkbox.network.connection.bridge.ConnectionBridge;
import dorkbox.network.connection.idle.IdleBridge;
import dorkbox.network.connection.idle.IdleSender;

/**
 *
 */
public
class RmiNopConnection implements Connection_ {
    @Override
    public
    boolean hasRemoteKeyChanged() {
        return false;
    }

    @Override
    public
    String getRemoteHost() {
        return null;
    }

    @Override
    public
    boolean isLoopback() {
        return false;
    }

    @Override
    public
    EndPoint getEndPoint() {
        return null;
    }

    @Override
    public
    int id() {
        return 0;
    }

    @Override
    public
    String idAsHex() {
        return null;
    }

    @Override
    public
    boolean hasUDP() {
        return false;
    }

    @Override
    public
    ConnectionBridge send() {
        return null;
    }

    @Override
    public
    ConnectionPoint send(final Object message) {
        return null;
    }

    @Override
    public
    IdleBridge sendOnIdle(final IdleSender<?, ?> sender) {
        return null;
    }

    @Override
    public
    IdleBridge sendOnIdle(final Object message) {
        return null;
    }

    @Override
    public
    Listeners listeners() {
        return null;
    }

    @Override
    public
    void close() {

    }

    @Override
    public
    void closeAsap() {

    }

    @Override
    public
    <Iface> void createRemoteObject(final Class<Iface> interfaceClass, final RemoteObjectCallback<Iface> callback) {

    }

    @Override
    public
    <Iface> void getRemoteObject(final int objectId, final RemoteObjectCallback<Iface> callback) {

    }

    @Override
    public
    ConnectionSupport rmiSupport() {
        return null;
    }

    @Override
    public
    long getNextGcmSequence() {
        return 0;
    }

    @Override
    public
    ParametersWithIV getCryptoParameters() {
        return null;
    }
}
