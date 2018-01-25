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
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.slf4j.Logger;

import com.esotericsoftware.kryo.util.IdentityMap;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.ConnectionManager;
import dorkbox.network.connection.Listener.OnDisconnected;
import dorkbox.network.connection.Listener.OnError;
import dorkbox.util.collections.ConcurrentEntry;

/**
 * Called when the remote end has been connected. This will be invoked before any objects are received by the network.
 * This method should not block for long periods as other network activity will not be processed
 * until it returns.
 */
@SuppressWarnings("Duplicates")
public final
class OnDisconnectedManager {
    // Recommended for best performance while adhering to the "single writer principle". Must be static-final
    private static final AtomicReferenceFieldUpdater<OnDisconnectedManager, ConcurrentEntry> REF = AtomicReferenceFieldUpdater.newUpdater(
            OnDisconnectedManager.class,
            ConcurrentEntry.class,
            "head_");

    private final Logger logger;

    //
    // The iterators for IdentityMap are NOT THREAD SAFE!
    //
    // This is only touched by a single thread, maintains a map of entries for FAST lookup during listener remove.
    private final IdentityMap<OnDisconnected, ConcurrentEntry> entries = new IdentityMap<OnDisconnected, ConcurrentEntry>(32,
                                                                                                                          ConnectionManager.LOAD_FACTOR);

    private volatile ConcurrentEntry<OnDisconnected> head_ = null; // reference to the first element

    // synchronized is used here to ensure the "single writer principle", and make sure that ONLY one thread at a time can enter this
    // section. Because of this, we can have unlimited reader threads all going at the same time, without contention (which is our
    // use-case 99% of the time)
    public
    OnDisconnectedManager(final Logger logger) {
        this.logger = logger;
    }

    public synchronized
    void add(final OnDisconnected listener) {
        // access a snapshot (single-writer-principle)
        ConcurrentEntry head = REF.get(this);

        if (!entries.containsKey(listener)) {
            head = new ConcurrentEntry<Object>(listener, head);

            entries.put(listener, head);

            // save this snapshot back to the original (single writer principle)
            REF.lazySet(this, head);
        }
    }

    /**
     * @return true if the listener was removed, false otherwise
     */
    public synchronized
    boolean remove(final OnDisconnected<Connection> listener) {
        // access a snapshot (single-writer-principle)
        ConcurrentEntry concurrentEntry = entries.get(listener);

        if (concurrentEntry != null) {
            ConcurrentEntry head = REF.get(this);

            if (concurrentEntry == head) {
                // if it was second, now it's first
                head = head.next();
                //oldHead.clear(); // optimize for GC not possible because of potentially running iterators
            }
            else {
                concurrentEntry.remove();
            }

            // save this snapshot back to the original (single writer principle)
            REF.lazySet(this, head);
            entries.remove(listener);
            return true;
        }
        else {
            return false;
        }
    }


    /**
     * @return true if a listener was found, false otherwise
     */
    public
    <C extends Connection> boolean notifyDisconnected(final C connection, final AtomicBoolean shutdown) {
        ConcurrentEntry<OnDisconnected<C>> head = REF.get(this);
        ConcurrentEntry<OnDisconnected<C>> current = head;

        OnDisconnected<C> listener;
        while (current != null && !shutdown.get()) {
            listener = current.getValue();
            current = current.next();

            try {
                listener.disconnected(connection);
            } catch (Exception e) {
                if (listener instanceof OnError) {
                    ((OnError<C>) listener).error(connection, e);
                }
                else {
                    logger.error("Unable to notify listener on 'disconnected' for listener '{}', connection '{}'.",
                                 listener,
                                 connection,
                                 e);
                }
            }
        }

        return head != null;  // true if we have something, otherwise false
    }

    /**
     * called on shutdown
     */
    public synchronized
    void clear() {
        this.entries.clear();
        this.head_ = null;
    }
}
