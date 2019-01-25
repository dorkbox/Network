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

import dorkbox.network.connection.EndPoint;
import dorkbox.network.serialization.NetworkSerializationManager;
import dorkbox.network.store.SettingsStore;

public
class Configuration {
    /**
     * On the server, if host is null, it will bind to the "any" address, otherwise you must specify the hostname/IP to bind to.
     */
    public String host = null;

    /**
     * Specify the TCP port to use. The server will listen on this port, the client will connect to it.
     * <p>
     * Must be > 0 to be used
     */
    public int tcpPort = 0;

    /**
     * Specify the UDP port to use. The server will listen on this port, the client will connect to it.
     * <p>
     * Must be > 0 to be used
     * <p>
     * UDP requires TCP to handshake
     */
    public int udpPort = 0;

    /**
     * Specify the local channel name to use, if the default is not wanted.
     * <p>
     * Local/remote configurations are incompatible with each other.
     */
    public String localChannelName = null;

    /**
     * Allows the end user to change how server settings are stored. For example, a custom database instead of the default.
     */
    public SettingsStore settingsStore = null;

    /**
     * Specify the serialization manager to use. If null, it uses the default.
     */
    public NetworkSerializationManager serialization = null;

    /**
     * The number of threads used for the worker threads by the end point. By default, this is the CPU_COUNT/2 or 1, whichever is larger.
     */
    public int workerThreadPoolSize = Math.max(Runtime.getRuntime().availableProcessors() / 2, 1);


    public
    Configuration() {
    }

    /**
     * Creates a new configuration for a connection that is local inside the JVM using the default name.
     * <p>
     * Local/remote configurations are incompatible with each other when running as a client. Servers can listen on all of them.
     */
    public static
    Configuration localOnly() {
        Configuration configuration = new Configuration();
        configuration.localChannelName = EndPoint.LOCAL_CHANNEL;

        return configuration;
    }

    public
    Configuration(String host, int tcpPort, int udpPort, String localChannelName) {
        this.host = host;
        this.tcpPort = tcpPort;
        this.udpPort = udpPort;
        this.localChannelName = localChannelName;
    }
}
