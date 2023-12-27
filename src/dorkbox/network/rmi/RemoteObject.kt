/*
 * Copyright 2023 dorkbox, llc
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
package dorkbox.network.rmi

/**
 * Provides access to various settings on a remote object.
 *
 * @author Nathan Sweet <misc></misc>@n4te.com>
 * @author dorkbox, llc
 */
interface RemoteObject<T> {
    companion object {
        fun <T> cast(remoteObject: T): RemoteObject<T> {
            @Suppress("UNCHECKED_CAST")
            return remoteObject as RemoteObject<T>
        }
    }

    /**
     * This is the milliseconds to wait for a method to return a value. Default is 3000, 0 disables (waits forever)
     */
    var responseTimeout: Int

    /**
     * Sets the behavior when invoking a remote method. DEFAULT is false. THIS CAN BE MODIFIED CONCURRENTLY AND IS NOT SAFE!
     * This is meant to be used to set the communication state "permanently", and not to be regularly changed as this is
     * not safe for concurrent use (one thread sets it, and another thread reads the correct, but unexpected value)
     *
     * If true, the invoking thread will NOT WAIT for a response. The method will return immediately and the return value
     *    should be ignored.
     *
     * If false, the invoking thread will wait for the remote method to return or timeout.
     *
     * If the return value or an exception needs to be retrieved, then DO NOT set async=true, and change the response timeout to 0 instead
     */
    var async: Boolean

    /**
     * Sets the ASYNC behavior when invoking remote methods, for whichever remote methods are in the unit function. THIS IS CONCURRENT/THREAD SAFE!
     * The primary use-case for this, is when calling the RMI methods of a different type (sync/async) than is currently configured
     * for this connection via the initial setting of `async` (default is false)
     *
     * For these methods, invoking thread WILL NOT wait for a response. The method will return immediately and the return value
     *    should be ignored.
     */
    fun async(action: T.() -> Unit)


    /**
     * Sets the ASYNC behavior when invoking remote methods, for whichever remote methods are in the unit function. THIS IS CONCURRENT/THREAD SAFE!
     * The primary use-case for this, is when calling the RMI methods of a different type (sync/async) than is currently configured
     * for this connection via the initial setting of `async` (default is false)
     *
     * For these methods, invoking thread WILL NOT wait for a response. The method will return immediately and the return value
     *    should be ignored.
     */
    suspend fun asyncSuspend(action: suspend T.() -> Unit)


    /**
     * Sets the SYNC behavior when invoking remote methods, for whichever remote methods are in the unit function. THIS IS CONCURRENT/THREAD SAFE!
     * The primary use-case for this, is when calling the RMI methods of a different type (sync/async) than is currently configured
     * for this connection via the initial setting of `async` (default is false)
     *
     * For these methods, the invoking thread WILL wait for a response or timeout.
     *
     * If the return value or an exception needs to be retrieved, then DO NOT set async=true, and change the response timeout to 0 instead
     */
    fun sync(action: T.() -> Unit)


    /**
     * Sets the SYNC behavior when invoking remote methods, for whichever remote methods are in the unit function. THIS IS CONCURRENT/THREAD SAFE!
     * The primary use-case for this, is when calling the RMI methods of a different type (sync/async) than is currently configured
     * for this connection via the initial setting of `async` (default is false)
     *
     * For these methods, the invoking thread WILL wait for a response or timeout.
     *
     * If the return value or an exception needs to be retrieved, then DO NOT set async=true, and change the response timeout to 0 instead
     */
    suspend fun syncSuspend(action: suspend T.() -> Unit)


    /**
     * Permits calls to [Object.toString] to actually return the `toString()` method on the object.
     *
     * @param enabled  If false, calls to [Object.toString] will return "<proxy #id>" (where `id` is the remote object ID)
     *      instead of invoking the remote `toString()` method on the object.
     */
    fun enableToString(enabled: Boolean)

    /**
     * Permits calls to [Object.hashCode] to actually return the `hashCode()` method on the object.
     *
     * @param enabled  If false, calls to [Object.hashCode] will return "id" (where `id` is the remote object ID)
     *      instead of invoking the remote `hashCode()` method on the object.
     */
    fun enableHashCode(enabled: Boolean)

    /**
     * Permits calls to [Object.equals] to actually return the `equals()` method on the object. This ONLY is applicable for *TWO* RMI objects.
     *
     * This will return false if one of those objects is NOT an RMI object!
     *
     * @param enabled  If false, calls to [Object.equals] will compare the "id" (where `id` is the remote object ID)
     *      instead of invoking the remote `equals()` method on the object.
     */
    fun enableEquals(enabled: Boolean)
}
