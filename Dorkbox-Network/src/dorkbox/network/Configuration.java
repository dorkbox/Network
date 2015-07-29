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
package dorkbox.network;

import dorkbox.network.util.store.SettingsStore;

import java.util.concurrent.Executor;

public
class Configuration {
    /**
     * On the server, if host is null, it will bind to the "any" address, otherwise you must specify the hostname/IP to bind to.
     */
    public String host = null;
    public int tcpPort = -1;

    /**
     * UDP requires TCP to handshake
     */
    public int udpPort = -1;

    /**
     * UDT requires TCP to handshake
     */
    public int udtPort = -1;

    public String localChannelName = null;

    public SettingsStore settingsStore = null;

    /**
     * Enable remote method invocation (RMI) for this connection. This is additional overhead to using RMI.
     * <p/>
     * Specifically, It costs at least 2 bytes more to use remote method invocation than just sending the parameters. If the method has a
     * return value which is not {@link dorkbox.network.rmi.RemoteObject#setNonBlocking(boolean) ignored}, an extra byte is written. If the
     * type of a parameter is not final (note primitives are final) then an extra byte is written for that parameter.
     */
    public boolean rmiEnabled = false;

    /**
     * Sets the executor used to invoke methods when an invocation is received from a remote endpoint. By default, no executor is set and
     * invocations occur on the network thread, which should not be blocked for long.
     */
    public Executor rmiExecutor = null;

    public
    Configuration() {
    }

    public
    Configuration(String localChannelName) {
        this.localChannelName = localChannelName;
    }

    public
    Configuration(String host, int tcpPort, int udpPort, int udtPort, String localChannelName) {
        this.host = host;
        this.tcpPort = tcpPort;
        this.udpPort = udpPort;
        this.udtPort = udtPort;
        this.localChannelName = localChannelName;
    }
}
