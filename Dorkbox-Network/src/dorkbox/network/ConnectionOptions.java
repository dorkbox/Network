package dorkbox.network;

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
