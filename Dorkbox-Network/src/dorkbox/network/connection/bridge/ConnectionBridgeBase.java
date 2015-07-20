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

import dorkbox.network.connection.ConnectionPoint;

public
interface ConnectionBridgeBase {
    /**
     * Sends the message to other listeners INSIDE this endpoint. It does not
     * send it to a remote address.
     */
    void self(Object message);

    /**
     * Sends the message over the network using TCP. (or via LOCAL when it's a
     * local channel).
     */
    ConnectionPoint TCP(Object message);

    /**
     * Sends the message over the network using UDP (or via LOCAL when it's a
     * local channel).
     */
    ConnectionPoint UDP(Object message);

    /**
     * Sends the message over the network using UDT. (or via LOCAL when it's a
     * local channel).
     */
    ConnectionPoint UDT(Object message);
}
