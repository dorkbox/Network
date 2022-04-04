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

import dorkbox.collections.IdentityMap
import dorkbox.network.ipFilter.IpFilterRule
import dorkbox.os.OS
import dorkbox.util.classes.ClassHelper
import dorkbox.util.classes.ClassHierarchy
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
        val LOAD_FACTOR = OS.getFloat(ListenerManager::class.qualifiedName + "LOAD_FACTOR", 0.8f)

        /**
         * Remove from the stacktrace kotlin coroutine info + dorkbox network call stack. This is NOT used by RMI
         *
         * Neither of these are useful in resolving exception handling from a users perspective, and only clutter the stacktrace.
         */
        fun cleanStackTrace(throwable: Throwable, adjustedStartOfStack: Int = 0) {
            // we never care about coroutine stacks, so filter then to start with.
            val origStackTrace = throwable.stackTrace
            val size = origStackTrace.size

            if (size == 0) {
                return
            }

            val stackTrace = origStackTrace.filterNot {
                val stackName = it.className
                stackName.startsWith("kotlinx.coroutines.") ||
                stackName.startsWith("kotlin.coroutines.")
            }.toTypedArray()


            var newEndIndex = stackTrace.size - 1

            // maybe offset by 1 because we have to adjust coroutine calls
            var newStartIndex = adjustedStartOfStack

            // sometimes we want to see the VERY first invocation, but not always
            val savedFirstStack =
                if (newEndIndex > 1 && newStartIndex < newEndIndex &&  // this fixes some out-of-bounds errors that can potentially occur
                    stackTrace[newStartIndex].methodName == "invokeSuspend") {
                newStartIndex++
                stackTrace.copyOfRange(adjustedStartOfStack, newStartIndex)
            } else {
                null
            }

            for (i in newEndIndex downTo 0) {
                val stackName = stackTrace[i].className
                if (stackName.startsWith("dorkbox.network.")) {
                    newEndIndex--
                } else {
                    break
                }
            }

            if (newEndIndex > 0) {
                if (savedFirstStack != null) {
                    // we want to save the FIRST stack frame also, maybe
                    throwable.stackTrace = savedFirstStack + stackTrace.copyOfRange(newStartIndex, newEndIndex)
                } else {
                    throwable.stackTrace = stackTrace.copyOfRange(newStartIndex, newEndIndex)
                }

            } else {
                // keep just one, since it's a stack frame INSIDE our network library, and we need that!
                throwable.stackTrace = stackTrace.copyOfRange(0, 1)
            }
        }

        /**
         * Remove from the stacktrace kotlin coroutine info ONLY that is inside the network stack. This is for internal logs when a problem happens INSIDE the network stack.
         *
         * Neither of these are useful in resolving exception handling from a users perspective, and only clutter the stacktrace.
         */
        fun cleanStackTraceInternal(throwable: Throwable) {
            // NOTE: when we remove stuff, we ONLY want to remove the "tail" of the stacktrace, not ALL parts of the stacktrace
            val stackTrace = throwable.stackTrace
            val size = stackTrace.size

            if (size == 0) {
                return
            }

            // we want to ONLY filter stuff that is past the highest dorkbox index AND is a coroutine.
            val firstDorkboxIndex = stackTrace.indexOfFirst { it.className.startsWith("dorkbox.network.") }
            val lastDorkboxIndex = stackTrace.indexOfLast { it.className.startsWith("dorkbox.network.") }
                throwable.stackTrace = stackTrace.filterIndexed { index, element ->
                val stackName = element.className
                if (index <= firstDorkboxIndex && index >= lastDorkboxIndex) {
                    false
                } else {
                    val isCoroutine = !(stackName.startsWith("kotlinx.coroutines.") ||
                      stackName.startsWith("kotlin.coroutines."))
                    isCoroutine && element.methodName != "invokeSuspend"
                }
            }.toTypedArray()
        }
    }

    // initialize a emtpy arrays
    private val onConnectFilterList = atomic(Array<(CONNECTION.() -> Boolean)>(0) { { true } })
    private val onConnectFilterMutex = Mutex()

    private val onConnectList = atomic(Array<suspend (CONNECTION.() -> Unit)>(0) { { } })
    private val onConnectMutex = Mutex()

    private val onDisconnectList = atomic(Array<suspend CONNECTION.() -> Unit>(0) { { } })
    private val onDisconnectMutex = Mutex()

    private val onErrorList = atomic(Array<CONNECTION.(Throwable) -> Unit>(0) { {  } })
    private val onErrorMutex = Mutex()

    private val onErrorGlobalList = atomic(Array<Throwable.() -> Unit>(0) { { } })
    private val onErrorGlobalMutex = Mutex()

    private val onMessageMap = atomic(IdentityMap<Class<*>, Array<suspend CONNECTION.(Any) -> Unit>>(32, LOAD_FACTOR))
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
     * Adds an IP+subnet rule that defines if that IP+subnet is allowed or denied connectivity to this server.
     *
     * If there are no rules added, then all connections are allowed
     * If there are rules added, then a rule MUST be matched to be allowed
     */
    suspend fun filter(ipFilterRule: IpFilterRule) {
        filter {
            // IPC will not filter, so this is OK to coerce to not-null
            ipFilterRule.matches(remoteAddress!!)
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
    suspend fun filter(function: CONNECTION.() -> Boolean) {
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
    suspend fun onConnect(function: suspend CONNECTION.() -> Unit) {
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
    suspend fun onDisconnect(function: suspend CONNECTION.() -> Unit) {
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
    suspend fun onError(function: CONNECTION.(Throwable) -> Unit) {
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
    suspend fun onError(function: Throwable.() -> Unit) {
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
    suspend fun <MESSAGE> onMessage(function: suspend CONNECTION.(MESSAGE) -> Unit) {
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
     fun notifyFilter(connection: CONNECTION): Boolean {
        // remote address will NOT be null at this stage, but best to verify.
        val remoteAddress = connection.remoteAddress
        if (remoteAddress == null) {
            logger.error("Connection ${connection.id}: Unable to attempt connection stages when no remote address is present")
            return false
        }

        // by default, there is a SINGLE rule that will always exist, and will always ACCEPT ALL connections.
        // This is so the array types can be setup (the compiler needs SOMETHING there)
        val arrayOfIpFilterRules = onConnectFilterList.value

        // if there is a rule, a connection must match for it to connect
        arrayOfIpFilterRules.forEach {
            if (it.invoke(connection)) {
                return true
            }
        }

        // default if nothing matches
        // NO RULES ADDED -> ACCEPT
        //    RULES ADDED -> DENY
        return arrayOfIpFilterRules.isEmpty()
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
                logger.error("Connection ${connection.id} error", t)
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
                logger.error("Connection ${connection.id} error", t)
            }
        }
    }

    /**
     * Invoked when there is an error for a specific connection
     *
     * The error is also sent to an error log before notifying callbacks
     */
    fun notifyError(connection: CONNECTION, exception: Throwable) {
        onErrorList.value.forEach {
            try {
                it(connection, exception)
            } catch (t: Throwable) {
                // NOTE: when we remove stuff, we ONLY want to remove the "tail" of the stacktrace, not ALL parts of the stacktrace
                cleanStackTrace(t)
                logger.error("Connection ${connection.id} error", t)
            }
        }
    }

    /**
     * Invoked when there is a global error (no connection information)
     *
     * The error is also sent to an error log before notifying callbacks
     */
    val notifyError: (exception: Throwable) -> Unit = { exception ->
        onErrorGlobalList.value.forEach {
            try {
                it(exception)
            } catch (t: Throwable) {
                // NOTE: when we remove stuff, we ONLY want to remove the "tail" of the stacktrace, not ALL parts of the stacktrace
                cleanStackTrace(t)
                logger.error("Global error", t)
            }
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
                        cleanStackTrace(t)
                        logger.error("Connection ${connection.id} error", t)
                        notifyError(connection, t)
                    }
                }
            }
        }

        return hasListeners
    }
}
