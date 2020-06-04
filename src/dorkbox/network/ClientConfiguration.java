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
class ClientConfiguration extends Configuration {

    /**
     * The network or IPC address for the client to connect to.
     *
     * For a network connection, it can be:
     *  - a network name ("localhost", "loopback", "lo", "bob.example.org")
     *  - an IP address ("127.0.0.1", "123.123.123.123")
     *
     *  For the IPC (Inter-Process-Communication) connection. it must be:
     *  - the IPC designation, "ipc"
     *
     *  Note: Case does not matter, and "localhost" is the default
     */
    public String remoteAddress = "localhost";

    public
    ClientConfiguration() {
        super();
    }
}
