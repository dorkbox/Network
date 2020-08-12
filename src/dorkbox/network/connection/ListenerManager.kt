package dorkbox.network.connection

import dorkbox.network.ipFilter.IpFilterRule
import dorkbox.network.ipFilter.IpSubnetFilterRule
import dorkbox.util.Property
import dorkbox.util.classes.ClassHelper
import dorkbox.util.classes.ClassHierarchy
import dorkbox.util.collections.IdentityMap
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KLogger
import net.jodah.typetools.TypeResolver

/**
 * Manages all of the different connect/disconnect/etc listeners
 */
internal class ListenerManager<CONNECTION: Connection>(private val logger: KLogger, private val exceptionGetter: (String, Throwable?) -> Throwable) {
    companion object {
        /**
         * Specifies the load-factor for the IdentityMap used to manage keeping track of the number of connections + listeners
         */
        @Property
        val LOAD_FACTOR = 0.8f


        /**
         * Remove from the stacktrace (going in reverse), kotlin coroutine info + dorkbox network call stack.
         *
         * Neither of these are useful in resolving exception handling from a users perspective, and only clutter the stacktrace.
         */
        fun cleanStackTrace(throwable: Throwable) {
            // NOTE: when we remove stuff, we ONLY want to remove the "tail" of the stacktrace, not ALL parts of the stacktrace
            val stackTrace = throwable.stackTrace
            var newEndIndex = stackTrace.size - 1
            for (i in newEndIndex downTo 0) {
                val stackName = stackTrace[i].className
                if (i == newEndIndex) {
                    if (stackName.startsWith("kotlinx.coroutines.") ||
                        stackName.startsWith("kotlin.coroutines.") ||
                        stackName.startsWith("dorkbox.network.")) {
                        newEndIndex--
                    } else {
                        break
                    }
                }
            }

            // tailToChopIndex will also remove the VERY LAST CachedMethod or CachedAsmMethod access invocation (because it's offset by 1)
            // NOTE: we want to do this!
            throwable.stackTrace = stackTrace.copyOfRange(0, newEndIndex)
        }
    }

    // initialize a emtpy arrays
    private val onConnectIpFilterList = atomic(Array<IpFilterRule>(0) { IpSubnetFilterRule.fake })
    private val onConnectIpFilterMutex = Mutex()

    private val onConnectFilterList = atomic(Array<(suspend (CONNECTION) -> Boolean)>(0) { { true } })
    private val onConnectFilterMutex = Mutex()

    private val onConnectList = atomic(Array<suspend ((CONNECTION) -> Unit)>(0) { { } })
    private val onConnectMutex = Mutex()

    private val onDisconnectList = atomic(Array<suspend (CONNECTION) -> Unit>(0) { { } })
    private val onDisconnectMutex = Mutex()

    private val onErrorList = atomic(Array<suspend (CONNECTION, Throwable) -> Unit>(0) { { _, _ -> } })
    private val onErrorMutex = Mutex()

    private val onErrorGlobalList = atomic(Array<suspend (Throwable) -> Unit>(0) { { _ -> } })
    private val onErrorGlobalMutex = Mutex()

    private val onMessageMap = atomic(IdentityMap<Class<*>, Array<suspend (CONNECTION, Any) -> Unit>>(32, LOAD_FACTOR))
    private val onMessageMutex = Mutex()

    // used to keep a cache of class hierarchy for distributing messages
    private val classHierarchyCache = ClassHierarchy(LOAD_FACTOR)

    private inline fun <reified T> add(thing: T, array: Array<T>): Array<T> {
        val currentLength: Int = array.size

        // add the new subscription to the array
        @Suppress("UNCHECKED_CAST")
        val newMessageArray = array.copyOf(currentLength + 1) as Array<T>
        newMessageArray[currentLength] = thing

        return newMessageArray
    }

    /**
     * Adds an IP+subnet rule that defines if that IP+subnet is allowed/denied connectivity to this server.
     *
     * If there are any IP+subnet added to this list - then ONLY those are permitted (all else are denied)
     *
     * If there is nothing added to this list - then ALL are permitted
     */
    suspend fun filter(ipFilterRule: IpFilterRule) {
        onConnectIpFilterMutex.withLock {
            // we have to follow the single-writer principle!
            onConnectIpFilterList.lazySet(add(ipFilterRule, onConnectIpFilterList.value))
        }
    }


    /**
     * Adds a function that will be called BEFORE a client/server "connects" with each other, and used to determine if a connection
     * should be allowed
     *
     * It is the responsibility of the custom filter to write the error, if there is one
     *
     * If the function returns TRUE, then the connection will continue to connect.
     * If the function returns FALSE, then the other end of the connection will
     *   receive a connection error
     *
     * For a server, this function will be called for ALL clients.
     */
    suspend fun filter(function: suspend (CONNECTION) -> Boolean) {
        onConnectFilterMutex.withLock {
            // we have to follow the single-writer principle!
            onConnectFilterList.lazySet(add(function, onConnectFilterList.value))
        }
    }

    /**
     * Adds a function that will be called when a client/server "connects" with each other
     *
     * For a server, this function will be called for ALL clients.
     */
    suspend fun onConnect(function: suspend (CONNECTION) -> Unit) {
        onConnectMutex.withLock {
            // we have to follow the single-writer principle!
            onConnectList.lazySet(add(function, onConnectList.value))
        }
    }

    /**
     * Called when the remote end is no longer connected.
     *
     * Do not try to send messages! The connection will already be closed, resulting in an error if you attempt to do so.
     */
    suspend fun onDisconnect(function: suspend (CONNECTION) -> Unit) {
        onDisconnectMutex.withLock {
            // we have to follow the single-writer principle!
            onDisconnectList.lazySet(add(function, onDisconnectList.value))
        }
    }

    /**
     * Called when there is an error for a specific connection
     *
     * The error is also sent to an error log before this method is called.
     */
    suspend fun onError(function: suspend (CONNECTION, throwable: Throwable) -> Unit) {
        onErrorMutex.withLock {
            // we have to follow the single-writer principle!
            onErrorList.lazySet(add(function, onErrorList.value))
        }
    }

    /**
     * Called when there is an error in general (no connection information)
     *
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
     *
     * This method should not block for long periods as other network activity will not be processed until it returns.
     */
    suspend fun <M : Any> onMessage(function: suspend (CONNECTION, M) -> Unit) {
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

                @Suppress("UNCHECKED_CAST")
                val func = function as suspend (CONNECTION, Any) -> Unit

                val newMessageArray: Array<suspend (CONNECTION, Any) -> Unit>
                val onMessageArray: Array<suspend (CONNECTION, Any) -> Unit>? = tempMap.get(messageClass)

                if (onMessageArray != null) {
                    newMessageArray = add(function, onMessageArray)
                } else {
                    @Suppress("RemoveExplicitTypeArguments")
                    newMessageArray = Array<suspend (CONNECTION, Any) -> Unit>(1) { { _, _ -> } }
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
     * It is the responsibility of the custom filter to write the error, if there is one
     *
     * @return true if the connection will be allowed to connect. False if we should terminate this connection
     */
    suspend fun notifyFilter(connection: CONNECTION): Boolean {
        // NOTE: pass a reference to a string, so if there is an error, we can get it! (and log it, and send it to the client)

        // first run through the IP connection filters, THEN run through the "custom" filters

        val address = connection.remoteAddressInt

        // it's possible for a remote address to match MORE than 1 rule.
        var isAllowed = false

        // these are the IP filters (optimized checking based on simple IP rules)
        onConnectIpFilterList.value.forEach {
//            if (it.matches())
//
//
//                if (!it(connection)) {
//                    return false
//                }
        }


//        onConnectFilterList.value.forEach {
//            if (!it(connection)) {
//                return false
//            }
//        }
//
//        val size = ipFilterRules.size
//        if (size == 0) {
//            return true
//        }


//
//
//        for (i in 0 until size) {
//            val rule = ipFilterRules[i] ?: continue
//            if (isAllowed) {
//                break
//            }
//            if (rule.matches(remoteAddress)) {
//                isAllowed = rule.ruleType() == IpFilterRuleType.ACCEPT
//            }
//        }
//        logger.debug("Validating {}  Connection allowed: {}", address, isAllowed)
//        return isAllowed
//        return true


        // these are the custom filters
//        onConnectFilterList.value.forEach {
//            if (!it(connection)) {
//                return false
//            }
//        }

        return true
    }

    /**
     * Invoked when a connection is connected to a remote address.
     */
    suspend fun notifyConnect(connection: CONNECTION) {
        onConnectList.value.forEach {
            try {
                it(connection)
            } catch (t: Throwable) {
                //  // NOTE: when we remove stuff, we ONLY want to remove the "tail" of the stacktrace, not ALL parts of the stacktrace
                //                    val throwable = result as Throwable
                //                    val reversedList = throwable.stackTrace.reversed().toMutableList()
                //
                //                    // we have to remove kotlin stuff from the stacktrace
                //                    var reverseIter = reversedList.iterator()
                //                    while (reverseIter.hasNext()) {
                //                        val stackName = reverseIter.next().className
                //                        if (stackName.startsWith("kotlinx.coroutines") || stackName.startsWith("kotlin.coroutines")) {
                //                            // cleanup the stack elements which create the stacktrace
                //                            reverseIter.remove()
                //                        } else {
                //                            // done cleaning up the tail from kotlin
                //                            break
                //                        }
                //                    }
                //
                //                    // remove dorkbox network stuff
                //                    reverseIter = reversedList.iterator()
                //                    while (reverseIter.hasNext()) {
                //                        val stackName = reverseIter.next().className
                //                        if (stackName.startsWith("dorkbox.network")) {
                //                            // cleanup the stack elements which create the stacktrace
                //                            reverseIter.remove()
                //                        } else {
                //                            // done cleaning up the tail from network
                //                            break
                //                        }
                //                    }
                //
                //                    throwable.stackTrace = reversedList.reversed().toTypedArray()

                notifyError(connection, t)
            }
        }
    }

    /**
     * Invoked when a connection is disconnected to a remote address.
     */
    suspend fun notifyDisconnect(connection: CONNECTION) {
        onDisconnectList.value.forEach {
            try {
                it(connection)
            } catch (e: Throwable) {
                notifyError(connection, exceptionGetter("Error during notifyDisconnect", e))
            }
        }
    }

    /**
     * Invoked when there is an error for a specific connection
     * <p>
     * The error is also sent to an error log before notifying callbacks
     */
    suspend fun notifyError(connection: CONNECTION, exception: Throwable) {
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
        onErrorGlobalList.value.forEach {
            it(exception)
        }
    }



    /**
     * Invoked when a message object was received from a remote peer.
     */
    suspend fun notifyOnMessage(connection: CONNECTION, message: Any) {
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
            val onMessageArray: Array<suspend (CONNECTION, Any) -> Unit>? = tempMap.get(clazz)
            if (onMessageArray != null) {
                hasListeners = true

                onMessageArray.forEach { func ->
                    try {
                        func(connection, message)
                    } catch (t: Throwable) {
                        notifyError(connection, t)
                    }
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
}
