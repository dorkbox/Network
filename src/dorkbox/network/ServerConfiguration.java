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

public
class ServerConfiguration extends Configuration {

    /**
     * The address for the server to listen on. "*" will accept connections from all interfaces, otherwise specify
     * the hostname (or IP) to bind to.
     */
    public String listenIpAddress = "*";

    /**
     * The starting port for clients to use. The upper bound of this value is limited by the maximum number of clients allowed.
     */
    public int clientStartPort;

    /**
     * The maximum number of clients allowed for a server
     */
    public int maxClientCount;

    /**
     * The maximum number of client connection allowed per IP address
     */
    public int maxConnectionsPerIpAddress;

    public
    ServerConfiguration() {
        super();

    }

}
