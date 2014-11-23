package dorkbox.network;

import dorkbox.network.rmi.RemoteObject;
import dorkbox.network.util.SerializationManager;
import dorkbox.network.util.store.SettingsStore;

public class ConnectionOptions {
    public String host = null;
    public int tcpPort = -1;

    /** UDP requires TCP to handshake */
    public int udpPort = -1;

    /** UDT requires TCP to handshake */
    public int udtPort = -1;

    public String localChannelName = null;

    public SerializationManager serializationManager = null;
    public SettingsStore settingsStore = null;

    /**
     * Enable remote method invocation (RMI) for this connection. This is additional overhead to using RMI.
     * <p>
     * Specifically, It costs at least 2 bytes more to use remote method invocation than just
     * sending the parameters. If the method has a return value which is not
     * {@link RemoteObject#setNonBlocking(boolean) ignored}, an extra byte is
     * written. If the type of a parameter is not final (note primitives are final)
     * then an extra byte is written for that parameter.
     */
    public boolean enableRmi = false;


    public ConnectionOptions() {
    }

    public ConnectionOptions(String localChannelName) {
        this.localChannelName = localChannelName;
    }

    public ConnectionOptions(String host, int tcpPort, int udpPort, int udtPort, String localChannelName, SerializationManager serializationManager) {
        this.host = host;
        this.tcpPort = tcpPort;
        this.udpPort = udpPort;
        this.udtPort = udtPort;
        this.localChannelName = localChannelName;
        this.serializationManager = serializationManager;
    }
}
