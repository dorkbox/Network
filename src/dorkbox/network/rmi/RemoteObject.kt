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
 *
 * Copyright (c) 2008, Nathan Sweet
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * - Neither the name of Esoteric Software nor the names of its contributors may be used to endorse or promote products derived
 * from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
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
