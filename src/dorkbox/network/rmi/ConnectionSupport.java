package dorkbox.network.rmi;

import org.slf4j.Logger;

import dorkbox.network.connection.ConnectionImpl;

/**
 *
 */
public
class ConnectionSupport {
    public
    void close() {
    }

    public
    void removeAllListeners() {
    }

    public
    <Iface> void createRemoteObject(final ConnectionImpl connection, final Class<Iface> interfaceClass, final RemoteObjectCallback<Iface> callback) {
    }

    public
    <Iface> void getRemoteObject(final ConnectionImpl connection, final int objectId, final RemoteObjectCallback<Iface> callback) {
    }

    public
    boolean manage(final ConnectionImpl connection, final Object message) {
        return false;
    }

    public
    Object fixupRmi(final ConnectionImpl connection, final Object message) {
        return message;
    }

    public
    <T> int getRegisteredId(final T object) {
        return RmiBridge.INVALID_RMI;
    }

    public
    void runCallback(final Class<?> interfaceClass, final int callbackId, final Object remoteObject, final Logger logger) {
    }

    public
    RemoteObject getProxyObject(final ConnectionImpl connection, final int rmiId, final Class<?> iFace) {
        return null;
    }

    public
    RemoteObject getLocalProxyObject(final ConnectionImpl connection, final int rmiId, final Class<?> iFace, final Object object) {
        return null;
    }

    public
    Object getImplementationObject(final int objectId) {
        return null;
    }
}
