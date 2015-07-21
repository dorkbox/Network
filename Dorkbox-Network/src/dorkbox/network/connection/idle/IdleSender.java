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
import dorkbox.network.connection.ListenerRaw;

public abstract
class IdleSender<C extends Connection, M> extends ListenerRaw<C, M> {
    final IdleListener<C, M> idleListener;
    volatile boolean started;

    public
    IdleSender(final IdleListener<C, M> idleListener) {
        this.idleListener = idleListener;
    }

    @Override
    public
    void idle(C connection) {
        if (!this.started) {
            this.started = true;
            start();
        }

        M message = next();
        if (message == null) {
            connection.listeners()
                      .remove(this);
        }
        else {
            this.idleListener.send(connection, message);
        }
    }

    /**
     * Called once, before the first send. Subclasses can override this method to send something so the receiving side expects
     * subsequent objects.
     */
    protected
    void start() {
    }

    /**
     * Returns the next object to send, or null if no more objects will be sent.
     */
    protected abstract
    M next();
}
