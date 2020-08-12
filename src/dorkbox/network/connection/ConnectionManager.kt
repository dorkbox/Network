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

import dorkbox.network.Configuration
import kotlinx.coroutines.runBlocking
import mu.KLogger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

// Because all of our callbacks are in response to network communication, and there CANNOT be CPU race conditions over a network...
// we specifically use atomic references to set/get all of the callbacks. This ensures that these objects are visible when accessed
// from different coroutines (because, ultimately, we want to use multiple threads on the box for processing data, and if we use
// coroutines, we can ensure maximum thread output)

// .equals() compares the identity on purpose,this because we cannot create two separate objects that are somehow equal to each other.
internal open class ConnectionManager<CONNECTION: Connection>(val logger: KLogger, val config: Configuration, val listenerManager: ListenerManager<CONNECTION>) : AutoCloseable {

    private val connectionLock = ReentrantReadWriteLock()
    private val connections = mutableListOf<CONNECTION>()

    /**
     * Invoked when aeron successfully connects to a remote address.
     *
     * @param connection the connection to add
     */
    fun addConnection(connection: CONNECTION) {
        connectionLock.write {
            connections.add(connection)
        }
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
    fun removeConnection(connection: CONNECTION) {
        connectionLock.write {
            connections.remove(connection)
        }
    }

    /**
     * Performs an action on each connection in the list inside a read lock
     */
    suspend fun forEachConnectionDoRead(function: suspend (connection: CONNECTION) -> Unit) {
        connectionLock.read {
            connections.forEach {
                function(it)
            }
        }
    }

    /**
     * Performs an action on each connection in the list.
     */
    private val connectionsToRemove = mutableListOf<CONNECTION>()
    internal suspend fun forEachConnectionCleanup(function: suspend (connection: CONNECTION) -> Boolean,
                                                  cleanup: suspend (connection: CONNECTION) -> Unit) {
        connectionLock.write {
            connections.forEach {
                if (function(it)) {
                    try {
                        it.close()
                    } finally {
                        connectionsToRemove.add(it)
                    }
                }
            }

            if (connectionsToRemove.size > 0) {
                connectionsToRemove.forEach {
                    cleanup(it)
                }
                connectionsToRemove.clear()
            }
        }
    }

    fun connectionCount(): Int {
        return connections.size
    }


//    fun addListenerManager(connection: C): ConnectionManager<C> {
//        // when we are a server, NORMALLY listeners are added at the GLOBAL level (meaning, I add one listener, and ALL connections
//        // are notified of that listener.
//
//        // it is POSSIBLE to add a connection-specific listener (via connection.addListener), meaning that ONLY
//        // that listener is notified on that event (ie, admin type listeners)
//        var created = false
//        var manager = localManagers[connection]
//        if (manager == null) {
//            created = true
//            manager = ConnectionManager<C>("$loggerName-$connection Specific", actionDispatchScope)
//            localManagers.put(connection, manager)
//        }
//        if (created) {
//            val logger2 = logger
//            if (logger2.isTraceEnabled) {
//                logger2.trace("Connection specific Listener Manager added for connection: {}", connection)
//            }
//        }
//        return manager
//    }

//    fun removeListenerManager(connection: C) {
//        var wasRemoved = false
//        val removed = localManagers.remove(connection)
//        if (removed != null) {
//            wasRemoved = true
//        }
//        if (wasRemoved) {
//            val logger2 = logger
//            if (logger2.isTraceEnabled) {
//                logger2.trace("Connection specific Listener Manager removed for connection: {}", connection)
//            }
//        }
//    }

    /**
     * Closes all associated resources/threads/connections
     */
    override fun close() {
        connectionLock.write {
            // runBlocking because we don't want to progress until we are 100% done closing all connections
            runBlocking {
                // don't need anything fast or fancy here, because this method will only be called once
                connections.forEach {
                    it.close()
                }

                connections.forEach {
                    listenerManager.notifyDisconnect(it)
                }

                connections.clear()
            }
        }
    }



    /**
     * Exposes methods to send the object to all server connections (except the specified one) over the network. (or via LOCAL when it's a
     * local channel).
     */
    // @Override
    // public
    // ConnectionExceptSpecifiedBridgeServer except() {
    //     return this;
    // }
    // /**
    //  * Sends the message to other listeners INSIDE this endpoint for EVERY connection. It does not send it to a remote address.
    //  */
    // @Override
    // public
    // ConnectionPoint self(final Object message) {
    //     ConcurrentEntry<ConnectionImpl> current = connectionsREF.get(this);
    //     ConnectionImpl c;
    //     while (current != null) {
    //         c = current.getValue();
    //         current = current.next();
    //
    //         onMessage(c, message);
    //     }
    //     return this;
    // }

    /**
     * Safely sends objects to a destination (such as a custom object or a standard ping). This will automatically choose which protocol
     * is available to use.
     */
    suspend fun send(message: Any) {
        TODO("NOT IMPL YET. going to use aeron for this functionality since it's a lot faster")
        // TODO: USE AERON add.dataPublisher thingy, so it's areon pushing messages out (way, WAY faster than if we are to iterate over
        //  the connections
//        for (connection in connections) {
//            connection.send(message)
//        }
    }
}
