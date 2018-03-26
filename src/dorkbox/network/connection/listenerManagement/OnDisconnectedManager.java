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
package dorkbox.network.connection.listenerManagement;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Listener.OnDisconnected;

/**
 * Called when the remote end has been connected. This will be invoked before any objects are received by the network.
 * This method should not block for long periods as other network activity will not be processed
 * until it returns.
 */
public final
class OnDisconnectedManager<C extends Connection> extends ConcurrentManager<C, OnDisconnected<C>> {

    private static final AtomicBoolean disconnectBoolean = new AtomicBoolean(false);

    public
    OnDisconnectedManager(final Logger logger) {
        super(logger);
    }

    /**
     * @return true if a listener was found, false otherwise
     */
    public
    boolean notifyDisconnected(final C connection) {
        // we override the boolean, because we ALWAYS want to call the disconnect listeners!
        return doAction(connection, disconnectBoolean);
    }

    @Override
    void listenerAction(final C connection, final OnDisconnected<C> listener) throws Exception {
        listener.disconnected(connection);
    }
}
