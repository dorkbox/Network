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

import dorkbox.network.connection.registration.MetaChannel;

import java.net.InetSocketAddress;

public
interface UdpServer {
    /**
     * Called when creating a connection.
     */
    void registerServerUDP(MetaChannel metaChannel);

    /**
     * Called when closing a connection.
     */
    void unRegisterServerUDP(InetSocketAddress udpRemoteAddress);

    /**
     * @return the connection for a remote UDP address
     */
    ConnectionImpl getServerUDP(InetSocketAddress udpRemoteAddress);
}
