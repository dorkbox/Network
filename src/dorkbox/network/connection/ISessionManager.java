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

import java.util.Collection;

public
interface ISessionManager<C extends Connection> {
    /**
     * Called when a message is received
     */
    void onMessage(C connection, Object message);

    /**
     * Called when the connection has been idle (read & write) for 2 seconds
     */
    void onIdle(C connection);


    void onConnected(C connection);

    void onDisconnected(C connection);

    /**
     * Returns a non-modifiable list of active connections. This is extremely slow, and not recommended!
     */
    Collection<C> getConnections();
}
