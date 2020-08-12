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

interface Ping {
    /**
     * Wait for the ping to return, and returns the ping response time in MS or -1 if it failed.
     */
    val response: Int

    /**
     * Adds a ping listener to this future. The listener is notified when this future is done. If this future is already completed,
     * then the listener is notified immediately.
     */
    fun <C : Connection?> add(listener: PingListener<C>?)

    /**
     * Removes a ping listener from this future. The listener is no longer notified when this future is done. If the listener
     * was not previously associated with this future, this method does nothing and returns silently.
     */
    fun <C : Connection?> remove(listener: PingListener<C>?)

    /**
     * Cancel this Ping.
     */
    fun cancel()
}
