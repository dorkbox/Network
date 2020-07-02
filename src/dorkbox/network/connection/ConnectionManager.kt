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
import dorkbox.util.Property
import dorkbox.util.classes.ClassHelper
import dorkbox.util.classes.ClassHierarchy
import dorkbox.util.collections.IdentityMap
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.jodah.typetools.TypeResolver
import org.slf4j.Logger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

// Because all of our callbacks are in response to network communication, and there CANNOT be CPU race conditions over a network...
// we specifically use atomic references to set/get all of the callbacks. This ensures that these objects are visible when accessed
// from different coroutines (because, ultimately, we want to use multiple threads on the box for processing data, and if we use
// coroutines, we can ensure maximum thread output)

// .equals() compares the identity on purpose,this because we cannot create two separate objects that are somehow equal to each other.
@Suppress("UNCHECKED_CAST")
open class ConnectionManager<C : Connection>(val logger: Logger, val config: Configuration) : AutoCloseable {
    // used to keep a cache of class hierarchy for distributing messages
    private val classHierarchyCache = ClassHierarchy(LOAD_FACTOR)

    // initialize an emtpy array
    private val onConnectFilterList = atomic(Array<(suspend (C) -> Boolean)>(0) { { true } })
    private val onConnectFilterMutex = Mutex()

    private val onConnectList = atomic(Array<suspend ((C) -> Unit)>(0) { { } })
    private val onConnectMutex = Mutex()

    private val onDisconnectList = atomic(Array<suspend (C) -> Unit>(0) { { } })
    private val onDisconnectMutex = Mutex()

    private val onErrorList = atomic(Array<suspend (C, Throwable) -> Unit>(0) { { _, _ -> } })
    private val onErrorMutex = Mutex()

    private val onErrorGlobalList = atomic(Array<suspend (Throwable) -> Unit>(0) { { _ -> } })
    private val onErrorGlobalMutex = Mutex()

    private val onMessageMap = atomic(IdentityMap<Class<*>, Array<suspend (C, Any) -> Unit>>(32, LOAD_FACTOR))
    private val onMessageMutex = Mutex()


    private val connectionLock = ReentrantReadWriteLock()
    private val connections = CopyOnWriteArrayList<C>()

    private val localManagers = IdentityMap<C, ConnectionManager<C>>(8, LOAD_FACTOR)

    private val shutdown = AtomicBoolean(false)

    companion object {
        /**
         * Specifies the load-factor for the IdentityMap used to manage keeping track of the number of connections + listeners
         */
        @Property
        val LOAD_FACTOR = 0.8f
    }

    private inline fun <reified T> add(thing: T, array: Array<T>): Array<T> {
        val currentLength: Int = array.size

        // add the new subscription to the array
        val newMessageArray = array.copyOf(currentLength + 1) as Array<T>
        newMessageArray[currentLength] = thing

        return newMessageArray
    }


    /**
     * Adds a function that will be called BEFORE a client/server "connects" with
     * each other, and used to determine if a connection should be allowed
     * <p>
     * If the function returns TRUE, then the connection will continue to connect.
     * If the function returns FALSE, then the other end of the connection will
     *   receive a connection error
     * <p>
     * For a server, this function will be called for ALL clients.
     */
    suspend fun filter(function: suspend (C) -> Boolean) {
        onConnectFilterMutex.withLock {
            // we have to follow the single-writer principle!
            onConnectFilterList.lazySet(add(function, onConnectFilterList.value))
        }
    }

    /**
     * Adds a function that will be called when a client/server "connects" with each other
     * <p>
     * For a server, this function will be called for ALL clients.
     */
    suspend fun onConnect(function: suspend (C) -> Unit) {
        onConnectMutex.withLock {
            // we have to follow the single-writer principle!
            onConnectList.lazySet(add(function, onConnectList.value))
        }
    }

    /**
     * Called when the remote end is no longer connected.
     * <p>
     * Do not try to send messages! The connection will already be closed, resulting in an error if you attempt to do so.
     */
    suspend fun onDisconnect(function: suspend (C) -> Unit) {
        onDisconnectMutex.withLock {
            // we have to follow the single-writer principle!
            onDisconnectList.lazySet(add(function, onDisconnectList.value))
        }
    }

    /**
     * Called when there is an error for a specific connection
     * <p>
     * The error is also sent to an error log before this method is called.
     */
    suspend fun onError(function: suspend (C, throwable: Throwable) -> Unit) {
        onErrorMutex.withLock {
            // we have to follow the single-writer principle!
            onErrorList.lazySet(add(function, onErrorList.value))
        }
    }

    /**
     * Called when there is an error in general
     * <p>
     * The error is also sent to an error log before this method is called.
     */
    suspend fun onError(function: suspend (throwable: Throwable) -> Unit) {
        onErrorGlobalMutex.withLock {
            // we have to follow the single-writer principle!
            onErrorGlobalList.lazySet(add(function, onErrorGlobalList.value))
        }
    }

    /**
     * Called when an object has been received from the remote end of the connection.
     * <p>
     * This method should not block for long periods as other network activity will not be processed until it returns.
     */
    suspend fun <M : Any> onMessage(function: suspend (C, M) -> Unit) {
        onMessageMutex.withLock {
            // we have to follow the single-writer principle!

            // this is the connection generic parameter for the listener, works for lambda expressions as well
            val connectionClass = ClassHelper.getGenericParameterAsClassForSuperClass(Connection::class.java, function.javaClass, 0)
            var messageClass = ClassHelper.getGenericParameterAsClassForSuperClass(Object::class.java, function.javaClass, 1)

            var success = false

            if (ClassHelper.hasInterface(Connection::class.java, connectionClass)) {
                // our connection class has "connection" as an interface.
                success = true
            }

            // if we are null, it means that we have no generics specified for our listener, so it accepts everything!
            else if (messageClass == null || messageClass == TypeResolver.Unknown::class.java) {
                messageClass = Object::class.java
                success = true
            }

            if (success) {
                // NOTE: https://github.com/Kotlin/kotlinx.atomicfu
                // this is EXPLICITLY listed as a "Don't" via the documentation. The ****ONLY**** reason this is actually OK is because
                // we are following the "single-writer principle", so only ONE THREAD can modify this at a time.
                val tempMap = onMessageMap.value

                val func = function as suspend (C, Any) -> Unit

                val newMessageArray: Array<suspend (C, Any) -> Unit>
                val onMessageArray: Array<suspend (C, Any) -> Unit>? = tempMap.get(messageClass)

                if (onMessageArray != null) {
                    newMessageArray = add(function, onMessageArray)
                } else {
                    @Suppress("RemoveExplicitTypeArguments")
                    newMessageArray = Array<suspend (C, Any) -> Unit>(1) { { _, _ -> } }
                    newMessageArray[0] = func
                }

                tempMap.put(messageClass, newMessageArray)
                onMessageMap.lazySet(tempMap)
            } else {
                throw IllegalArgumentException("Unable to add incompatible types! Detected connection/message classes: $connectionClass, $messageClass")
            }
        }
    }


    /**
     * Invoked just after a connection is created, but before it is connected.
     *
     * @return true if the connection will be allowed to connect. False if we should terminate this connection
     */
    suspend fun notifyFilter(connection: C): Boolean {
        onConnectFilterList.value.forEach {
            if (!it(connection)) {
                return false
            }
        }

        return true
    }

    /**
     * Invoked when a connection is connected to a remote address.
     */
    suspend fun notifyConnect(connection: C) {
        onConnectList.value.forEach {
            it(connection)
        }
    }

    /**
     * Invoked when a connection is disconnected to a remote address.
     */
    suspend fun notifyDisconnect(connection: C) {
        onDisconnectList.value.forEach {
            it(connection)
        }
    }

    /**
     * Invoked when there is an error for a specific connection
     * <p>
     * The error is also sent to an error log before notifying callbacks
     */
    suspend fun notifyError(connection: C, exception: Throwable) {
        logger.error("Error on connection: $connection", exception)
        onErrorList.value.forEach {
            it(connection, exception)
        }
    }

    /**
     * Invoked when there is an error in general
     * <p>
     * The error is also sent to an error log before notifying callbacks
     */
    suspend fun notifyError(exception: Throwable) {
        logger.error("General error", exception)
        onErrorGlobalList.value.forEach {
            it(exception)
        }
    }


    /**
     * Invoked when a message object was received from a remote peer.
     */
    suspend fun notifyOnMessage(connection: Connection_, message: Any) {
        connection as C // note: this is necessary because of how connection calls it!

        val messageClass: Class<*> = message.javaClass

        // have to save the types + hierarchy (note: duplicates are OK, since they will just be overwritten)
        // this is used to 'pre-populate' the cache, so additional lookups are fast
        val hierarchy = classHierarchyCache.getClassAndSuperClasses(messageClass)

        // we march through the class hierarchy for this message, and call ALL callback that are registered
        // NOTICE: we CALL ALL TYPES -- meaning, if we have Object->Foo->Bar
        //    we have registered for 'Object' and 'Foo'
        //    we will call Foo (from this code)
        //    we will ALSO call Object (since we called Foo).


        // NOTE: https://github.com/Kotlin/kotlinx.atomicfu
        // this is EXPLICITLY listed as a "Don't" via the documentation. The ****ONLY**** reason this is actually OK is because
        // we are following the "single-writer principle", so only ONE THREAD can modify this at a time.

        // cache the lookup (because we don't care about race conditions, since the object hierarchy will be ALREADY established at this
        // exact moment
        val tempMap = onMessageMap.value
        var hasListeners = false
        hierarchy.forEach { clazz ->
            val onMessageArray: Array<suspend (C, Any) -> Unit>? = tempMap.get(clazz)
            if (onMessageArray != null) {
                hasListeners = true

                onMessageArray.forEach { func ->
                    func(connection, message)
                }
            }
        }


//         foundListener |= onMessageReceivedManager.notifyReceived((C) connection, message, shutdown);

        // now have to account for additional connection listener managers (non-global).
        // access a snapshot of the managers (single-writer-principle)
//        val localManager = localManagers[connection as C]
//        if (localManager != null) {
//            // if we found a listener during THIS method call, we need to let the NEXT method call know,
//            // so it doesn't spit out error for not handling a message (since that message MIGHT have
//            // been found in this method).
//            foundListener = foundListener or localManager.notifyOnMessage0(connection, message, foundListener)
//        }

        if (!hasListeners) {
            logger.error("----------- MESSAGE CALLBACK NOT REGISTERED FOR {}", messageClass.simpleName)
        }
    }


//
//    override fun remove(listener: OnConnected<C>): Listeners<C> {
//        return this
//    }


    // /**
    //  * Adds a listener to this connection/endpoint to be notified of connect/disconnect/idle/receive(object) events.
    //  * <p/>
    //  * When called by a server, NORMALLY listeners are added at the GLOBAL level (meaning, I add one listener, and ALL connections are
    //  * notified of that listener.
    //  * <p/>
    //  * It is POSSIBLE to add a server connection ONLY (ie, not global) listener (via connection.addListener), meaning that ONLY that
    //  * listener attached to the connection is notified on that event (ie, admin type listeners)
    //  *
    //  *
    //  * // TODO: When converting to kotlin, use reified! to get the listener types
    //  * https://kotlinlang.org/docs/reference/inline-functions.html
    //  */
    // @Override
    // public final
    // Listeners add(final Listener listener) {
    //     if (listener == null) {
    //         throw new IllegalArgumentException("listener cannot be null.");
    //     }
    //
//         // this is the connection generic parameter for the listener, works for lambda expressions as well
//         Class<?> genericClass = ClassHelper.getGenericParameterAsClassForSuperClass(Listener.class, listener.getClass(), 0);
    //
//         // if we are null, it means that we have no generics specified for our listener!
//         if (genericClass == this.baseClass || genericClass == TypeResolver.Unknown.class || genericClass == null) {
//             // we are the base class, so we are fine.
//             addListener0(listener);
//             return this;
//
//         }
//         else if (ClassHelper.hasInterface(Connection.class, genericClass) && !ClassHelper.hasParentClass(this.baseClass, genericClass)) {
//             // now we must make sure that the PARENT class is NOT the base class. ONLY the base class is allowed!
//             addListener0(listener);
//             return this;
//         }
//
//         // didn't successfully add the listener.
//         throw new IllegalArgumentException("Unable to add incompatible connection type as a listener! : " + this.baseClass);
    // }
    //
    // /**
    //  * INTERNAL USE ONLY
    //  */
    // private
    // void addListener0(final Listener listener) {
    //     boolean found = false;
    //     if (listener instanceof OnConnected) {
    //         onConnectedManager.add((Listener.OnConnected<C>) listener);
    //         found = true;
    //     }
    //     if (listener instanceof Listener.OnDisconnected) {
    //         onDisconnectedManager.add((Listener.OnDisconnected<C>) listener);
    //         found = true;
    //     }
    //
    //     if (listener instanceof Listener.OnMessageReceived) {
    //         onMessageReceivedManager.add((Listener.OnMessageReceived) listener);
    //         found = true;
    //     }
    //
    //     if (found) {
    //         hasAtLeastOneListener.set(true);
    //
    //         if (logger.isTraceEnabled()) {
    //             logger.trace("listener added: {}",
    //                          listener.getClass()
    //                                  .getName());
    //         }
    //     }
    //     else {
    //         logger.error("No matching listener types. Unable to add listener: {}",
    //                      listener.getClass()
    //                              .getName());
    //     }
    // }
    //
    // /**
    //  * Removes a listener from this connection/endpoint to NO LONGER be notified of connect/disconnect/idle/receive(object) events.
    //  * <p/>
    //  * When called by a server, NORMALLY listeners are added at the GLOBAL level (meaning, I add one listener, and ALL connections are
    //  * notified of that listener.
    //  * <p/>
    //  * It is POSSIBLE to remove a server-connection 'non-global' listener (via connection.removeListener), meaning that ONLY that listener
    //  * attached to the connection is removed
    //  */
    // @Override
    // public final
    // Listeners remove(final Listener listener) {
    //     if (listener == null) {
    //         throw new IllegalArgumentException("listener cannot be null.");
    //     }
    //
    //     if (logger.isTraceEnabled()) {
    //         logger.trace("listener removed: {}",
    //                      listener.getClass()
    //                              .getName());
    //     }
    //
    //     boolean found = false;
    //     int remainingListeners = 0;
    //
    //     if (listener instanceof Listener.OnConnected) {
    //         int size = onConnectedManager.removeWithSize((OnConnected<C>) listener);
    //         if (size >= 0) {
    //             remainingListeners += size;
    //             found = true;
    //         }
    //     }
    //     if (listener instanceof Listener.OnDisconnected) {
    //         int size = onDisconnectedManager.removeWithSize((Listener.OnDisconnected<C>) listener);
    //         if (size >= 0) {
    //             remainingListeners += size;
    //             found |= true;
    //         }
    //     }
    //     if (listener instanceof Listener.OnMessageReceived) {
    //         int size =  onMessageReceivedManager.removeWithSize((Listener.OnMessageReceived) listener);
    //         if (size >= 0) {
    //             remainingListeners += size;
    //             found |= true;
    //         }
    //     }
    //
    //     if (found) {
    //         if (remainingListeners == 0) {
    //             hasAtLeastOneListener.set(false);
    //         }
    //     }
    //     else {
    //         logger.error("No matching listener types. Unable to remove listener: {}",
    //                      listener.getClass()
    //                              .getName());
    //
    //     }
    //
    //     return this;
    // }
//    /**
//     * Removes all registered listeners from this connection/endpoint to NO LONGER be notified of connect/disconnect/idle/receive(object)
//     * events.
//     */
//    override fun removeAll(): Listeners<C> {
//        // onConnectedManager.clear();
//        // onDisconnectedManager.clear();
//        // onMessageReceivedManager.clear();
//        logger.error("ALL listeners removed !!")
//        return this
//    }

//    /**
//     * Removes all registered listeners (of the object type) from this
//     * connection/endpoint to NO LONGER be notified of
//     * connect/disconnect/idle/receive(object) events.
//     */
//    override fun removeAll(classType: Class<*>): Listeners<C> {
//        val logger2 = logger
//        // if (onMessageReceivedManager.removeAll(classType)) {
//        //     if (logger2.isTraceEnabled()) {
//        //         logger2.trace("All listeners removed for type: {}",
//        //                       classType.getClass()
//        //                                .getName());
//        //     }
//        // } else {
//        //     logger2.warn("No listeners found to remove for type: {}",
//        //                   classType.getClass()
//        //                            .getName());
//        // }
//        return this
//    }


    /**
     * Invoked when aeron successfully connects to a remote address.
     *
     * @param connection the connection to add
     */
    fun addConnection(connection: C) {
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
    fun removeConnection(connection: C) {
        connectionLock.write {
            connections.remove(connection)
        }
    }

    /**
     * Performs an action on each connection in the list inside a read lock
     */
    suspend fun forEachConnectionDoRead(function: suspend (connection: C) -> Unit) {
        connectionLock.read {
            connections.forEach {
                function(it)
            }
        }
    }

    /**
     * Performs an action on each connection in the list.
     */
    private val connectionsToRemove = mutableListOf<C>()
    internal suspend fun forEachConnectionCleanup(function: suspend (connection: C) -> Boolean, cleanup: suspend (connection: C) -> Unit) {
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
                    notifyDisconnect(it)
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
        // TODO: USE AERON add.dataPublisher thingy, so it's areon pusing messages out (way, WAY faster than if we are to iterate over
        //  the connections
//        for (connection in connections) {
//            connection.send(message)
//        }
    }
}
