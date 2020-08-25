/*
 * Copyright 2020 dorkbox, llc
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

import dorkbox.util.collections.ConcurrentEntry
import dorkbox.util.collections.ConcurrentIterator
import dorkbox.util.collections.ConcurrentIterator.headREF

// .equals() compares the identity on purpose,this because we cannot create two separate objects that are somehow equal to each other.
@Suppress("UNCHECKED_CAST")
internal open class ConnectionManager<CONNECTION: Connection>() {

    private val connections = ConcurrentIterator<CONNECTION>()


    /**
     * Invoked when aeron successfully connects to a remote address.
     *
     * @param connection the connection to add
     */
    fun add(connection: CONNECTION) {
        connections.add(connection)
    }

    /**
     * Removes a custom connection to the server.
     *
     *
     * This should only be used in situations where there can be DIFFERENT types of connections (such as a 'web-based' connection) and
     * you want *this* server instance to manage listeners + message dispatch
     *
     * @param connection the connection to remove
     */
    fun remove(connection: CONNECTION) {
        connections.remove(connection)
    }

    /**
     * Performs an action on each connection in the list.
     */
    inline fun forEach(function: (connection: CONNECTION) -> Unit) {
        // access a snapshot (single-writer-principle)
        val head = headREF.get(connections) as ConcurrentEntry<CONNECTION>?
        var current: ConcurrentEntry<CONNECTION>? = head

        var connection: CONNECTION
        while (current != null) {
            // Concurrent iteration...
            connection = current.value
            current = current.next()

            function(connection)
        }
    }

    /**
     * Performs an action on each connection in the list.
     */
    internal inline fun forEachWithCleanup(function: (connection: CONNECTION) -> Boolean,
                                           cleanup: (connection: CONNECTION) -> Unit) {

        val head = headREF.get(connections) as ConcurrentEntry<CONNECTION>?
        var current: ConcurrentEntry<CONNECTION>? = head

        var connection: CONNECTION
        while (current != null) {
            connection = current.value
            current = current.next()

            if (function(connection)) {
                // Concurrent iteration...
                connections.remove(connection)
                cleanup(connection)
            }
        }
    }

    fun connectionCount(): Int {
        return connections.size()
    }

    /**
     * Closes all associated resources/threads/connections
     */
    fun close() {
        connections.clear()
    }

    /**
     * Safely sends objects to a destination (such as a custom object or a standard ping). This will automatically choose which protocol
     * is available to use.
     */
    suspend inline fun send(message: Any) {
        forEach {
            it.send(message)
        }
    }
}
