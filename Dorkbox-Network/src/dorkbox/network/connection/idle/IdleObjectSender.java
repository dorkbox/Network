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

import dorkbox.network.connection.Connection;
import dorkbox.util.exceptions.NetException;

public
class IdleObjectSender<C extends Connection, M> extends IdleSender<C, M> {

    private final M message;

    public
    IdleObjectSender(M message) {
        this.message = message;
    }

    @Override
    public
    void idle(C connection) {
        if (!this.started) {
            if (this.idleListener != null) {
                // haven't defined TCP/UDP/UDT yet. It's a race condition, but we don't care.
                return;
            }
            this.started = true;
            start();
        }

        connection.listeners()
                  .remove(this);

        if (this.idleListener != null) {
            this.idleListener.send(connection, this.message);
        }
        else {
            throw new NetException("Invalid idle listener. Please specify .TCP(), .UDP(), or .UDT()");
        }
    }

    @Override
    protected
    M next() {
        return null;
    }
}
