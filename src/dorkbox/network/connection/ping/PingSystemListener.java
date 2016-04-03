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
package dorkbox.network.connection.ping;

import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.Listener;

public
class PingSystemListener implements Listener.OnMessageReceived<ConnectionImpl, PingMessage> {

    public
    PingSystemListener() {
    }

    @Override
    public void received(ConnectionImpl connection, PingMessage ping) {
        if (ping.isReply) {
            connection.updatePingResponse(ping);
        } else {
            // return the ping from whence it came
            ping.isReply = true;

            connection.ping0(ping);
        }
    }
}
