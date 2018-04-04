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

import org.bouncycastle.crypto.params.ParametersWithIV;

import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.ConnectionPoint;
import dorkbox.network.connection.ISessionManager;
import dorkbox.network.rmi.RmiObjectHandler;

public
interface ChannelWrapper {

    ConnectionPoint tcp();
    ConnectionPoint udp();

    /**
     * Flushes the contents of the TCP/UDP/etc pipes to the actual transport.
     */
    void flush();

    /**
     * @return a threadlocal AES key + IV. key=32 byte, iv=12 bytes (AES-GCM implementation). This is a threadlocal
     * because multiple protocols can be performing crypto AT THE SAME TIME, and so we have to make sure that operations don't
     * clobber each other
     */
    ParametersWithIV cryptoParameters();

    /**
     * @return true if this connection is connection on the loopback interface. This is specifically used to dynamically enable/disable
     * encryption (it's not required on loopback, it is required on all others)
     */
    boolean isLoopback();

    RmiObjectHandler manageRmi();

    /**
     * @return the remote host (can be local, tcp, udp)
     */
    String getRemoteHost();

    void close(ConnectionImpl connection, ISessionManager sessionManager, boolean hintedClose);

    int id();

    @Override
    boolean equals(Object obj);

    @Override
    String toString();
}
