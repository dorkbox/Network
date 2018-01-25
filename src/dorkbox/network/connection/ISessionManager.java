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
interface ISessionManager {
    /**
     * Called when a message is received
     */
    void onMessage(ConnectionImpl connection, Object message);

    /**
     * Called when the connection has been idle (read & write) for 2 seconds
     */
    void onIdle(Connection connection);

    /**
     * Invoked when a Channel is open, bound to a local address, and connected to a remote address.
     */
    void onConnected(Connection connection);

    /**
     * Invoked when a Channel was disconnected from its remote peer.
     */
    void onDisconnected(Connection connection);

    /**
     * Returns a non-modifiable list of active connections. This is extremely slow, and not recommended!
     */
    <C extends Connection> Collection<C> getConnections();
}
