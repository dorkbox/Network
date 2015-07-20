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
package dorkbox.network.connection.wrapper;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.ConnectionPointWriter;
import dorkbox.network.connection.ISessionManager;
import io.netty.channel.EventLoop;
import org.bouncycastle.crypto.params.ParametersWithIV;

public
interface ChannelWrapper {

    ConnectionPointWriter tcp();

    ConnectionPointWriter udp();

    ConnectionPointWriter udt();

    /**
     * Initialize the connection with any extra info that is needed but was unavailable at the channel construction.
     */
    void init();

    /**
     * Flushes the contents of the TCP/UDP/UDT/etc pipes to the actual transport.
     */
    void flush();

    EventLoop getEventLoop();

    ParametersWithIV cryptoParameters();

    /**
     * @return the remote host (can be local, tcp, udp, udt)
     */
    String getRemoteHost();

    void close(final Connection connection, final ISessionManager sessionManager);

    int id();

    @Override
    boolean equals(Object obj);

    @Override
    String toString();
}
