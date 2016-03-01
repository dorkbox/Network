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
package dorkbox.network.connection;

public
interface Ping {
    /**
     * Wait for the ping to return, and returns the ping response time or -1 if it failed failed.
     */
    int getResponse();

    /**
     * Adds the specified listener to this future. The specified listener is
     * notified when this future is done. If this future is already completed,
     * the specified listener is notified immediately.
     */
    <C extends Connection> void addListener(PingListener<C> listener);

    /**
     * Removes the specified listener from this future. The specified listener
     * is no longer notified when this future is done. If the specified listener
     * is not associated with this future, this method does nothing and returns
     * silently.
     */
    <C extends Connection> void removeListener(PingListener<C> listener);

    /**
     * Cancel this Ping.
     */
    void cancel();
}
