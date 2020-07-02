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
package dorkbox.network.connection

import dorkbox.network.rmi.RemoteObjectCallback

interface Connection : AutoCloseable {
    /**
     * Has the remote ECC public key changed. This can be useful if specific actions are necessary when the key has changed.
     */
    fun hasRemoteKeyChanged(): Boolean

    /**
     * The publication port (used by aeron) for this connection. This is from the perspective of the server!
     */
    val subscriptionPort: Int
    val publicationPort: Int

    /**
     * the remote address, as a string.
     */
    val remoteAddress: String

    /**
     * the remote address, as an integer.
     */
    val remoteAddressInt: Int

    /**
     * @return true if this connection is established on the loopback interface
     */
    val isLoopback: Boolean

    /**
     * @return true if this connection is an IPC connection
     */
    val isIPC: Boolean

    /**
     * @return true if this connection is a network connection
     */
    val isNetwork: Boolean

    /**
     * the stream id of this connection.
     */
    val streamId: Int

    /**
     * the session id of this connection.
     */
    val sessionId: Int

    /**
     * Polls the AERON media driver subscription channel for incoming messages
     */
    fun pollSubscriptions(): Int



    /**
     * Safely sends objects to a destination.
     */
    suspend fun send(message: Any)

    /**
     * Safely sends objects to a destination with the specified priority.
     *
     *
     * A priority of 255 (highest) will always be sent immediately.
     *
     *
     * A priority of 0-254 will be sent (0, the lowest, will be last) if there is no backpressure from the MediaDriver.
     */
    suspend fun send(message: Any, priority: Byte)

    /**
     * Sends a "ping" packet, trying UDP then TCP (in that order) to measure **ROUND TRIP** time to the remote connection.
     *
     * @return Ping can have a listener attached, which will get called when the ping returns.
     */
    suspend fun ping(): Ping // TODO: USE AERON FOR THIS

//    /**
//     * Expose methods to modify the connection-specific listeners.
//     */
//    fun listeners(): Listeners<Connection>

    /**
     * @param now The current time
     *
     * @return `true` if this connection has no subscribers and the current time `now` is after the expiration date
     */
    fun isExpired(now: Long): Boolean

    /**
     * @param now The current time
     *
     * @return `true` if this connection has no subscribers and the current time `now` is after the expiration date
     */
    fun isClosed(): Boolean


    /**
     * Closes the connection and removes all listeners
     */
    @Throws(Exception::class)
    override fun close()



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
     *
     *
     * The callback will be notified when the remote object has been created.
     *
     *
     *
     *
     * Methods that return a value will throw [TimeoutException] if the response is not received with the
     * [response timeout][RemoteObject.setResponseTimeout].
     *
     *
     * If [non-blocking][RemoteObject.setAsync] is false (the default), then methods that return a value must
     * not be called from the update thread for the connection. An exception will be thrown if this occurs. Methods with a
     * void return value can be called on the update thread.
     *
     *
     * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side
     * will have the proxy object replaced with the registered (non-proxy) object.
     *
     * If one wishes to change the default behavior, cast the object to access the different methods.
     * ie:  `RemoteObject remoteObject = (RemoteObject) test;`
     *
     * @see RemoteObject
     */
    suspend fun <Iface> createRemoteObject(interfaceClass: Class<Iface>, callback: RemoteObjectCallback<Iface>)

    /**
     * Tells the remote connection to access an already created proxy object that implements the specified interface. The methods on this object "map"
     * to an object that is created remotely.
     *
     *
     * The callback will be notified when the remote object has been created.
     *
     *
     *
     *
     * Methods that return a value will throw [TimeoutException] if the response is not received with the
     * [response timeout][RemoteObject.setResponseTimeout].
     *
     *
     * If [non-blocking][RemoteObject.setAsync] is false (the default), then methods that return a value must
     * not be called from the update thread for the connection. An exception will be thrown if this occurs. Methods with a
     * void return value can be called on the update thread.
     *
     *
     * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side
     * will have the proxy object replaced with the registered (non-proxy) object.
     *
     *
     * If one wishes to change the default behavior, cast the object to access the different methods.
     * ie:  `RemoteObject remoteObject = (RemoteObject) test;`
     *
     * @see RemoteObject
     */
    suspend fun <Iface> getRemoteObject(objectId: Int, callback: RemoteObjectCallback<Iface>)
}
