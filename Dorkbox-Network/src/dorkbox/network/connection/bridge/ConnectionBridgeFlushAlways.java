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
import dorkbox.network.connection.Ping;

public
class ConnectionBridgeFlushAlways implements ConnectionBridge {

    private final ConnectionBridge originalBridge;

    public
    ConnectionBridgeFlushAlways(ConnectionBridge originalBridge) {
        this.originalBridge = originalBridge;
    }

    @Override
    public
    void self(Object message) {
        this.originalBridge.self(message);
        flush();
    }

    @Override
    public
    ConnectionPoint TCP(Object message) {
        ConnectionPoint connection = this.originalBridge.TCP(message);
        connection.flush();
        return connection;
    }

    @Override
    public
    ConnectionPoint UDP(Object message) {
        ConnectionPoint connection = this.originalBridge.UDP(message);
        connection.flush();
        return connection;
    }

    @Override
    public
    ConnectionPoint UDT(Object message) {
        ConnectionPoint connection = this.originalBridge.UDT(message);
        connection.flush();
        return connection;
    }

    @Override
    public
    Ping ping() {
        Ping ping = this.originalBridge.ping();
        flush();
        return ping;
    }

    @Override
    public
    void flush() {
        this.originalBridge.flush();
    }
}
