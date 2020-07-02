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

interface Listener {}

/**
 * Called before the remote end has been connected.
 * <p>
 * This permits the addition of connection filters to decide if a connection is permitted.
 */
interface FilterConnection<C : Connection> {
    /**
     * Called before the remote end has been connected.
     * <p>
     * This permits the addition of connection filters to decide if a connection is permitted.
     * <p>
     * @return true if the connection is permitted, false if it will be rejected
     */
    fun filter(connection: C): Boolean
}

/**
 * Called when the remote end has been connected. This will be invoked before any objects are received by the network.
 */
interface OnConnected<C : Connection> {
    /**
     * Called when the remote end has been connected. This will be invoked before any objects are received by the network.
     */
    fun connected(connection: C)
}

/**
 * Called when the remote end is no longer connected.
 * <p>
 * Do not try to send messages! The connection will already be closed, resulting in an error if you attempt to do so.
 */
interface OnDisconnected<C : Connection> {
    /**
     * Called when the remote end is no longer connected.
     * <p>
     * Do not write data in this method! The connection can already be closed, resulting in an error if you attempt to do so.
     */
    fun disconnected(connection: C)
}

/**
 * Called when there is an error
 * <p>
 * The error is also sent to an error log before this method is called.
 */
interface OnError<C : Connection> {
    /**
     * Called when there is an error
     * <p>
     * The error is sent to an error log before this method is called.
     */
    fun error(connection: C, throwable: Throwable)
}

/**
 * Called when an object has been received from the remote end of the connection.
 * <p>
 * This method should not block for long periods as other network activity will not be processed until it returns.
 */
interface OnMessageReceived<C : Connection, M : Any> {
    fun received(connection: C, message: M)
}

/**
 * Permits a listener to specify it's own referenced object type, if passing in a generic parameter doesn't work. This is necessary since
 * the system looks up incoming message types to determine what listeners to dispatch them to.
 */
interface SelfDefinedType {
    /**
     * Permits a listener to specify it's own referenced object type, if passing in a generic parameter doesn't work. This is necessary since
     * the system looks up incoming message types to determine what listeners to dispatch them to.
     */
    fun type(): Class<*>
}
