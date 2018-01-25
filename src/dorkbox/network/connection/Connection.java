/*
 * Copyright 2010 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.network.connection;

import dorkbox.network.connection.bridge.ConnectionBridge;
import dorkbox.network.connection.idle.IdleBridge;
import dorkbox.network.connection.idle.IdleSender;
import dorkbox.network.rmi.RemoteObject;
import dorkbox.network.rmi.RemoteObjectCallback;
import dorkbox.network.rmi.TimeoutException;

@SuppressWarnings("unused")
public
interface Connection {

    /**
     * Has the remote ECC public key changed. This can be useful if specific actions are necessary when the key has changed.
     */
    boolean hasRemoteKeyChanged();

    /**
     * @return the remote address, as a string.
     */
    String getRemoteHost();

    /**
     * @return true if this connection is established on the loopback interface
     */
    boolean isLoopback();

    /**
     * @return the endpoint associated with this connection
     */
    @SuppressWarnings("rawtypes")
    EndPoint getEndPoint();

    /**
     * @return the connection (TCP or LOCAL) id of this connection.
     */
    int id();

    /**
     * @return the connection (TCP or LOCAL) id of this connection as a HEX string.
     */
    String idAsHex();

    /**
     * @return true if this connection is also configured to use UDP
     */
    boolean hasUDP();

    /**
     * Expose methods to send objects to a destination (such as a custom object or a standard ping)
     */
    ConnectionBridge send();

    /**
     * Expose methods to send objects to a destination when the connection has become idle.
     */
    IdleBridge sendOnIdle(IdleSender<?, ?> sender);

    /**
     * Expose methods to send objects to a destination when the connection has become idle.
     */
    IdleBridge sendOnIdle(Object message);

    /**
     * Expose methods to modify the connection listeners.
     */
    Listeners listeners();

    /**
     * Closes the connection
     */
    void close();

    /**
     * Marks the connection to be closed as soon as possible. This is evaluated when the current thread execution returns to the network stack.
     */
    void closeAsap();

    /**
     * Tells the remote connection to create a new proxy object that implements the specified interface. The methods on this object "map"
     * to an object that is created remotely.
     * <p>
     * The callback will be notified when the remote object has been created.
     * <p>
     * <p>
     * Methods that return a value will throw {@link TimeoutException} if the response is not received with the
     * {@link RemoteObject#setResponseTimeout(int) response timeout}.
     * <p/>
     * If {@link RemoteObject#setAsync(boolean) non-blocking} is false (the default), then methods that return a value must
     * not be called from the update thread for the connection. An exception will be thrown if this occurs. Methods with a
     * void return value can be called on the update thread.
     * <p/>
     * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side
     * will have the proxy object replaced with the registered (non-proxy) object.
     *
     * If one wishes to change the default behavior, cast the object to access the different methods.
     * ie:  `RemoteObject remoteObject = (RemoteObject) test;`
     *
     * @see RemoteObject
     */
    <Iface> void createRemoteObject(final Class<Iface> interfaceClass, final RemoteObjectCallback<Iface> callback);

    /**
     * Tells the remote connection to access an already created proxy object that implements the specified interface. The methods on this object "map"
     * to an object that is created remotely.
     * <p>
     * The callback will be notified when the remote object has been created.
     * <p>
     * <p>
     * Methods that return a value will throw {@link TimeoutException} if the response is not received with the
     * {@link RemoteObject#setResponseTimeout(int) response timeout}.
     * <p/>
     * If {@link RemoteObject#setAsync(boolean) non-blocking} is false (the default), then methods that return a value must
     * not be called from the update thread for the connection. An exception will be thrown if this occurs. Methods with a
     * void return value can be called on the update thread.
     * <p/>
     * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side
     * will have the proxy object replaced with the registered (non-proxy) object.
     * <p>
     * If one wishes to change the default behavior, cast the object to access the different methods.
     * ie:  `RemoteObject remoteObject = (RemoteObject) test;`
     *
     * @see RemoteObject
     */
    <Iface> void getRemoteObject(final int objectId, final RemoteObjectCallback<Iface> callback);
}
