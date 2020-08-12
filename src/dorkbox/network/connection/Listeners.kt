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

/**
 * Generic types are in place to make sure that users of the application do not
 * accidentally add an incompatible connection type.
 */
interface Listeners<C : Connection> {
    /**
     * Adds a function that will be called BEFORE a client/server "connects" with
     * each other, and used to determine if a connection should be allowed
     *
     * If the function returns TRUE, then the connection will continue to connect.
     * If the function returns FALSE, then the other end of the connection will
     *   receive a connection error
     *
     * For a server, this function will be called for ALL clients.
     */
    fun filter(function: (C) -> Boolean): Int


    /**
     * Adds a function that will be called when a client/server "connects" with
     * each other
     *
     * For a server, this function will be called for ALL clients.
     */
    fun onConnect(function: (C) -> Unit): Int


    /**
     * Adds a function that will be called when a client/server "disconnects" with
     * each other
     *
     * For a server, this function will be called for ALL clients.
     *
     * It is POSSIBLE to add a server CONNECTION only (ie, not global) listener
     * (via connection.addListener), meaning that ONLY that listener attached to
     * the connection is notified on that event (ie, admin type listeners)
     */
    fun onDisconnect(function: (C) -> Unit): Int


    /**
     * Adds a function that will be called when a client/server encounters an error
     *
     * For a server, this function will be called for ALL clients.
     *
     * It is POSSIBLE to add a server CONNECTION only (ie, not global) listener
     * (via connection.addListener), meaning that ONLY that listener attached to
     * the connection is notified on that event (ie, admin type listeners)
     */
    fun onError(function: (C, throwable: Throwable) -> Unit): Int


    /**
     * Adds a function that will be called when a client/server receives a message
     *
     * For a server, this function will be called for ALL clients.
     *
     * It is POSSIBLE to add a server CONNECTION only (ie, not global) listener
     * (via connection.addListener), meaning that ONLY that listener attached to
     * the connection is notified on that event (ie, admin type listeners)
     */
    fun <M : Any> onMessage(function: (C, M) -> Unit): Int


    /**
     * Removes a listener from this connection/endpoint to NO LONGER be notified
     * of connect/disconnect/idle/receive(object) events.
     *
     *
     * When called by a server, NORMALLY listeners are added at the GLOBAL level
     * (meaning, I add one listener, and ALL connections are notified of that
     * listener.
     *
     *
     * It is POSSIBLE to remove a server-connection 'non-global' listener (via
     * connection.removeListener), meaning that ONLY that listener attached to
     * the connection is removed
     */
    fun remove(listenerId: Int)


    /**
     * Removes all registered listeners from this connection/endpoint to NO
     * LONGER be notified of connect/disconnect/idle/receive(object) events.
     */
    fun removeAll()
}
