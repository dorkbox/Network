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
package dorkbox.network.connection.idle;

public
interface IdleBridge {
    /**
     * Sends the object over the network using TCP (or via LOCAL when it's a
     * local channel) when the socket is in an "idle" state.
     */
    void TCP();

    /**
     * Sends the object over the network using UDP when the socket is in an
     * "idle" state.
     */
    void UDP();

    /**
     * Sends the object over the network using UDT (or via LOCAL when it's a
     * local channel) when the socket is in an "idle" state.
     */
    void UDT();
}
