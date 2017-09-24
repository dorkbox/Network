package dorkbox.network.rmi;

/**
 *
 */
public
interface RemoteObjectCallback<Iface> {
    /**
     * @param remoteObject the remote object (as a proxy object) or null if there was an error
     */
    void created(Iface remoteObject);
}
