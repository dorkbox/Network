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
 */
interface RemoteObject {

    /**
     * This is the the milliseconds to wait for a method to return a value. Default is 3000, 0 disables (waits forever)
     */
    var responseTimeout: Int

    /**
     * Sets the behavior when invoking a remote method. DEFAULT is false.
     *
     * If true, the invoking thread will not wait for a response. The method will return immediately and the return value
     *    should be ignored.
     *
     * If false, the invoking thread will wait (if called via suspend, then it will use coroutines) for the remote method to return or
     * timeout.
     *
     * If the return value or exception needs to be retrieved, then DO NOT set async, and change the response timeout
     */
    var async: Boolean

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
     * Permits calls to [Object.equals] to actually return the `equals()` method on the object.
     *
     * @param enabled  If false, calls to [Object.equals] will compare the "id" (where `id` is the remote object ID)
     *      instead of invoking the remote `equals()` method on the object.
     */
    fun enableEquals(enabled: Boolean)

    /**
     * Causes this RemoteObject to stop listening to the connection for method invocation response messages.
     */
    fun close()
}
