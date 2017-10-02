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
package dorkbox.network.rmi;

/**
 * Provides access to various settings on a remote object.
 *
 * @author Nathan Sweet <misc@n4te.com>
 */
public
interface RemoteObject {
    /**
     * Sets the milliseconds to wait for a method to return a value. Default is 3000, 0 disables (waits forever)
     *
     * @param timeoutMillis how long to wait for a method to return a value.
     */
    void setResponseTimeout(int timeoutMillis);

    /**
     * Sets the blocking behavior when invoking a remote method. Default is false (blocking)
     *
     * @param enable
     *                 If false, the invoking thread will wait for the remote method to return or timeout (default). If true, the invoking
     *                 thread will not wait for a response. The method will return immediately and the return value should be ignored. If
     *                 return values are being transmitted, the return value or any thrown exception can later be retrieved with {@link
     *                 #waitForLastResponse()} or {@link #waitForResponse(byte)}. The responses will be stored until retrieved, so each
     *                 method call should have a matching retrieve.
     */
    void setAsync(boolean enable);

    /**
     * Sets whether return values are sent back when invoking a remote method. Default is true.
     *
     * @param transmit
     *                 If true, then the return value for async method invocations can be retrieved with {@link
     *                 #waitForLastResponse()} or {@link #waitForResponse(byte)}. If false, then non-primitive return values for remote
     *                 method invocations are not sent by the remote side of the connection and the response can never be retrieved. This
     *                 can also be used to save bandwidth if you will not check the return value of a blocking remote invocations. Note that
     *                 an exception could still be returned by {@link #waitForLastResponse()} or {@link #waitForResponse(byte)} if {@link
     *                 #setTransmitExceptions(boolean)} is true.
     */
    void setTransmitReturnValue(boolean transmit);

    /**
     * Sets whether exceptions are sent back when invoking a remote method. Default is true.
     *
     * @param transmit
     *                 If false, exceptions will be unhandled and rethrown as RuntimeExceptions inside the invoking thread. This is the
     *                 legacy behavior. If true, behavior is dependent on whether {@link #setAsync(boolean)}. If non-blocking is true,
     *                 the exception will be serialized and sent back to the call site of the remotely invoked method, where it will be
     *                 re-thrown. If non-blocking is false, an exception will not be thrown in the calling thread but instead can be
     *                 retrieved with {@link #waitForLastResponse()} or {@link #waitForResponse(byte)}, similar to a return value.
     */
    void setTransmitExceptions(boolean transmit);

    /**
     * Specifies that remote method invocation will happen over TCP. This is the default.
     * <p>
     * TCP remote method invocations <b>will</b> return a response and the invoking thread <b>will</b> wait for a response. See {@link
     * #setAsync(boolean)} if you do not want to wait for a response, which can be retrieved later with {@link #waitForLastResponse()} or
     * {@link #waitForResponse(byte)}.
     */
    void setTCP();

    /**
     * Specifies that remote method invocation will happen over UDP. Default is {@link #setTCP()}
     * <p>
     * UDP remote method invocations <b>will</b> return a response and the invoking thread <b>will</b> wait for a response. See {@link
     * #setAsync(boolean)} if you do not want to wait for a response, which can be retrieved later with {@link #waitForLastResponse()} or
     * {@link #waitForResponse(byte)}.
     */
    void setUDP();

    /**
     * Permits calls to {@link Object#toString()} to actually return the `toString()` method on the object.
     *
     * @param enableDetailedToString
     *                 If false, calls to {@link Object#toString()} will return "<proxy #id>" (where `id` is the remote object ID) instead
     *                 of invoking the remote `toString()` method on the object.
     */
    void enableToString(boolean enableDetailedToString);

    /**
     * Waits for the response to the last method invocation to be received or the response timeout to be reached.
     *
     * @return the response of the last method invocation
     */
    Object waitForLastResponse();

    /**
     * @return the ID of response for the last method invocation.
     */
    byte getLastResponseID();

    /**
     * Waits for the specified method invocation response to be received or the response timeout to be reached.
     * <p>
     * Response IDs use a six bit identifier, with one identifier reserved for "no response". This means that
     * this method should be called to get the result for a non-blocking call before an additional 63 non-blocking calls are made, or risk
     * undefined behavior due to identical IDs.
     *
     * @param responseID this is the response ID obtained via {@link #getLastResponseID()}
     *
     * @return the response of the last method invocation
     */
    Object waitForResponse(byte responseID);

    /**
     * Causes this RemoteObject to stop listening to the connection for method invocation response messages.
     */
    void close();
}
