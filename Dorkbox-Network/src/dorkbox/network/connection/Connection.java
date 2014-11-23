package dorkbox.network.connection;


import org.bouncycastle.crypto.params.ParametersWithIV;

import dorkbox.network.connection.bridge.ConnectionBridge;
import dorkbox.network.connection.idle.IdleBridge;
import dorkbox.network.connection.idle.IdleSender;
import dorkbox.network.rmi.RemoteObject;
import dorkbox.network.rmi.TimeoutException;

public interface Connection {
    public static final String connection = "connection";

    /**
     * Initialize the connection with any extra info that is needed but was unavailable at the channel construction.
     * <p>
     * This happens BEFORE prep.
     */
    public void init(EndPoint endPoint, Bridge bridge);

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
     * @return the endpoint associated with this connection
     */
    public EndPoint getEndPoint();


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

    /**
     * Identical to {@link #getRemoteObject(C, int, Class...)} except returns
     * the object cast to the specified interface type. The returned object
     * still implements {@link RemoteObject}.
     */
    public <T> T getRemoteObject(int objectID, Class<T> iface);

    /**
     * Returns a proxy object that implements the specified interfaces. Methods
     * invoked on the proxy object will be invoked remotely on the object with
     * the specified ID in the ObjectSpace for the specified connection. If the
     * remote end of the connection has not {@link #addConnection(Connection)
     * added} the connection to the ObjectSpace, the remote method invocations
     * will be ignored.
     * <p>
     * Methods that return a value will throw {@link TimeoutException} if the
     * response is not received with the
     * {@link RemoteObject#setResponseTimeout(int) response timeout}.
     * <p>
     * If {@link RemoteObject#setNonBlocking(boolean) non-blocking} is false
     * (the default), then methods that return a value must not be called from
     * the update thread for the connection. An exception will be thrown if this
     * occurs. Methods with a void return value can be called on the update
     * thread.
     * <p>
     * If a proxy returned from this method is part of an object graph sent over
     * the network, the object graph on the receiving side will have the proxy
     * object replaced with the registered object.
     *
     * @see RemoteObject
     */
    public RemoteObject getRemoteObject(int objectID, Class<?>... ifaces);
}
