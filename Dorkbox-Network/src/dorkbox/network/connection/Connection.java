package dorkbox.network.connection;


import org.bouncycastle.crypto.params.ParametersWithIV;

import dorkbox.network.connection.bridge.ConnectionBridge;
import dorkbox.network.connection.idle.IdleBridge;
import dorkbox.network.connection.idle.IdleSender;

public interface Connection {

    public static final String connection = "connection";

    /**
     * Initialize the connection with any extra info that is needed but was unavailable at the channel construction.
     * <p>
     * This happens BEFORE prep.
     */
    public void init(EndPointWithSerialization endPoint, Bridge bridge);

    /**
     * Prepare the channel wrapper, since it doesn't have access to certain fields during it's initialization.
     * <p>
     * This happens AFTER init.
     */
    public void prep();

    /**
     * @return the AES key/IV, etc associated with this connection
     */
    public ParametersWithIV getCryptoParameters();

    /**
     * Has the remote ECC public key changed. This can be useful if specific actions are necessary when the key has changed.
     */
    public boolean hasRemoteKeyChanged();

    /**
     * @return the remote address, as a string.
     */
    public String getRemoteHost();

    /**
     * @return the name used by the connection
     */
    public String getName();

    /**
     * @return the connection (TCP or LOCAL) id of this connection.
     */
    public int id();

    /**
     * @return the connection (TCP or LOCAL) id of this connection as a HEX
     *         string.
     */
    public String idAsHex();

    /**
     * @return true if this connection is also configured to use UDP
     */
    public boolean hasUDP();

    /**
     * @return true if this connection is also configured to use UDT
     */
    public boolean hasUDT();

    /**
     * Expose methods to send objects to a destination (such as a custom object or a standard ping)
     */
    public ConnectionBridge send();

    /**
     * Expose methods to send objects to a destination when the connection has become idle.
     */
    public IdleBridge sendOnIdle(IdleSender<?, ?> sender);

    /**
     * Expose methods to send objects to a destination when the connection has become idle.
     */
    public IdleBridge sendOnIdle(Object message);

    /**
     * Expose methods to modify the connection listeners.
     */
    public ListenerBridge listeners();

    /**
     * Closes the connection
     */
    public void close();
}
