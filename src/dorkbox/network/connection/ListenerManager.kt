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
internal class ListenerManager<CONNECTION: Connection>(private val logger: KLogger) {
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
            var newEndIndex = Math.max(0, stackTrace.size - 1)

            if (newEndIndex > 0) {
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
    suspend fun <MESSAGE> onMessage(function: suspend (CONNECTION, MESSAGE) -> Unit) {
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

//        val address = connection.remoteAddressInt

        // it's possible for a remote address to match MORE than 1 rule.
//        var isAllowed = false

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
                // NOTE: when we remove stuff, we ONLY want to remove the "tail" of the stacktrace, not ALL parts of the stacktrace
                cleanStackTrace(t)
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
            } catch (t: Throwable) {
                // NOTE: when we remove stuff, we ONLY want to remove the "tail" of the stacktrace, not ALL parts of the stacktrace
                cleanStackTrace(t)
                notifyError(connection, t)
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
     *
     * @return true if there were listeners assigned for this message type
     */
    suspend fun notifyOnMessage(connection: CONNECTION, message: Any): Boolean {
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

        // cache the lookup
        //   we don't care about race conditions, since the object hierarchy will be ALREADY established at this exact moment
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

        return hasListeners
    }
}
