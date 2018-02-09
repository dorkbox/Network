/*
 * Copyright 2010 dorkbox, llc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package dorkbox.network.connection.listenerManagement;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Listener;
import dorkbox.network.connection.Listener.OnError;
import dorkbox.util.collections.ConcurrentEntry;
import dorkbox.util.collections.ConcurrentIterator;

public abstract
class ConcurrentManager<C extends Connection, T extends Listener> extends ConcurrentIterator<T> {

    private final Logger logger;

    ConcurrentManager(final Logger logger) {
        this.logger = logger;
    }

    @Override
    public synchronized
    void add(final T listener) {
        super.add(listener);
    }

    /**
     * The returned value indicates how many listeners are left in this manager
     *
     * @return >= 0 if the listener was removed, -1 otherwise
     */
    public synchronized
    int removeWithSize(final T listener) {
        boolean removed = super.remove(listener);

        if (removed) {
            return super.size();
        }
        else {
            return -1;
        }
    }

    /**
     * @return true if a listener was found, false otherwise
     */
    @SuppressWarnings("unchecked")
    boolean doAction(final C connection, final AtomicBoolean shutdown) {
        // access a snapshot (single-writer-principle)
        ConcurrentEntry<T> head = headREF.get(this);
        ConcurrentEntry<T> current = head;

        T listener;
        while (current != null && !shutdown.get()) {
            listener = current.getValue();
            current = current.next();

            // Concurrent iteration...
            try {
                listenerAction(connection, listener);
            } catch (Exception e) {
                if (listener instanceof OnError) {
                    ((OnError) listener).error(connection, e);
                }
                else {
                    logger.error("Unable to notify listener '{}', connection '{}'.", listener, connection, e);
                }
            }
        }

        return head != null;  // true if we have something, otherwise false
    }

    abstract void listenerAction(final C connection, final T listener) throws Exception;
}
