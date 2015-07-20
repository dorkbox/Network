package dorkbox.network;

import dorkbox.network.util.store.SettingsStore;

import java.util.concurrent.Executor;

public
class Configuration {
    public String host = null;
    public int tcpPort = -1;

    /**
     * UDP requires TCP to handshake
     */
    public int udpPort = -1;

    /**
     * UDT requires TCP to handshake
     */
    public int udtPort = -1;

    public String localChannelName = null;

    public SettingsStore settingsStore = null;

    /**
     * Enable remote method invocation (RMI) for this connection. This is additional overhead to using RMI.
     * <p/>
     * Specifically, It costs at least 2 bytes more to use remote method invocation than just
     * sending the parameters. If the method has a return value which is not
     * {@link dorkbox.network.rmi.RemoteObject#setNonBlocking(boolean) ignored}, an extra byte is
     * written. If the type of a parameter is not final (note primitives are final)
     * then an extra byte is written for that parameter.
     */
    public boolean rmiEnabled = false;

    /**
     * Sets the executor used to invoke methods when an invocation is received
     * from a remote endpoint. By default, no executor is set and invocations
     * occur on the network thread, which should not be blocked for long.
     */
    public Executor rmiExecutor = null;

    public
    Configuration() {
    }

    public
    Configuration(String localChannelName) {
        this.localChannelName = localChannelName;
    }

    public
    Configuration(String host, int tcpPort, int udpPort, int udtPort, String localChannelName) {
        this.host = host;
        this.tcpPort = tcpPort;
        this.udpPort = udpPort;
        this.udtPort = udtPort;
        this.localChannelName = localChannelName;
    }
}
