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
     * @return true if this connection is an IPC connection
     */
    boolean isIPC();

    /**
     * @return true if this connection is a network connection
     */
    boolean isNetwork();

    /**
     * @return the connection id of this connection.
     */
    int id();

    /**
     * @return the connection id of this connection as a HEX string.
     */
    String idAsHex();


    /**
     * Safely sends objects to a destination.
     */
    ConnectionPoint send(Object message);

    /**
     * Safely sends objects to a destination with the specified priority.
     * <p>
     * A priority of 255 (highest) will always be sent immediately.
     * <p>
     * A priority of 0-254 will be sent (0, the lowest, will be last) if there is no backpressure from the MediaDriver.
     */
    ConnectionPoint send(Object message, byte priority);

    /**
     * Safely sends objects to a destination, but does not guarantee delivery
     */
    ConnectionPoint sendUnreliable(Object message);


    /**
     * Safely sends objects to a destination, but does not guarantee delivery.
     * <p>
     * A priority of 255 (highest) will always be sent immediately.
     * <p>
     * A priority of 0-254 will be sent (0, the lowest, will be last) if there is no backpressure from the MediaDriver.
     */
    ConnectionPoint sendUnreliable(Object message, byte priority);

    /**
     * Sends a "ping" packet, trying UDP then TCP (in that order) to measure <b>ROUND TRIP</b> time to the remote connection.
     *
     * @return Ping can have a listener attached, which will get called when the ping returns.
     */
    Ping ping(); // TODO: USE AERON FOR THIS



    /**
     * Expose methods to modify the connection-specific listeners.
     */
    Listeners listeners();

    /**
     * Closes the connection and removes all listeners
     */
    void close();

    // TODO: below should just be "new()" to create a new object, to mirror "new Object()"
    //   // RMI
    //         // client.get(5) -> gets from the server connection, if exists, then global.
    //         //                  on server, a connection local RMI object "uses" an id for global, so there will never be a conflict
    //         //                  using some tricks, we can make it so that it DOESN'T matter the order in which objects are created,
    //         //                  and can specify, if we want, the object created.
    //         //                  Once created though, as NEW ONE with the same ID cannot be created until the old one is removed!
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
