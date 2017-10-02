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
package dorkbox.network.connection.bridge;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.ConnectionPoint;

public
interface ConnectionExceptSpecifiedBridgeServer<C extends Connection> {

    /**
     * Sends the object to all server connections (except the specified one) over the network using TCP. (or via LOCAL when it's a local
     * channel).
     */
    ConnectionPoint TCP(C connection, Object message);

    /**
     * Sends the object to all server connections (except the specified one) over the network using UDP (or via LOCAL when it's a local
     * channel).
     */
    ConnectionPoint UDP(C connection, Object message);
}
