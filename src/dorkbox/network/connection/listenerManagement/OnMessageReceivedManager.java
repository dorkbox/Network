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

import static dorkbox.util.collections.ConcurrentIterator.headREF;

import java.lang.reflect.Type;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.slf4j.Logger;

import com.esotericsoftware.kryo.util.IdentityMap;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.ConnectionManager;
import dorkbox.network.connection.Listener;
import dorkbox.network.connection.Listener.OnError;
import dorkbox.network.connection.Listener.OnMessageReceived;
import dorkbox.network.connection.Listener.SelfDefinedType;
import dorkbox.util.collections.ConcurrentEntry;
import dorkbox.util.collections.ConcurrentIterator;
import dorkbox.util.generics.ClassHelper;

/**
 * Called when the remote end has been connected. This will be invoked before any objects are received by the network.
 * This method should not block for long periods as other network activity will not be processed
 * until it returns.
 */
@SuppressWarnings("Duplicates")
public final
class OnMessageReceivedManager<C extends Connection> {
    // Recommended for best performance while adhering to the "single writer principle". Must be static-final
    private static final AtomicReferenceFieldUpdater<OnMessageReceivedManager, IdentityMap> REF = AtomicReferenceFieldUpdater.newUpdater(
            OnMessageReceivedManager.class,
            IdentityMap.class,
            "listeners");

    /**
     * Gets the referenced object type for a specific listener, but ONLY necessary for listeners that receive messages
     * <p/>
     * This works for compile time code. The generic type parameter #2 (index 1) is pulled from type arguments.
     * generic parameters cannot be primitive types
     */
    private static
    Class<?> identifyType(final OnMessageReceived listener) {
        if (listener instanceof SelfDefinedType) {
            return ((SelfDefinedType) listener).getType();
        }

        final Class<?> clazz = listener.getClass();
        Class<?> objectType = ClassHelper.getGenericParameterAsClassForSuperClass(Listener.OnMessageReceived.class, clazz, 1);

        if (objectType != null) {
            // SOMETIMES generics get confused on which parameter we actually mean (when sub-classing)
            if (objectType != Object.class && ClassHelper.hasInterface(Connection.class, objectType)) {
                Class<?> objectType2 = ClassHelper.getGenericParameterAsClassForSuperClass(Listener.OnMessageReceived.class, clazz, 2);
                if (objectType2 != null) {
                    objectType = objectType2;
                }
            }

            return objectType;
        }
        else {
            // there was no defined parameters
            return Object.class;
        }
    }

    private final Logger logger;

    //
    // The iterators for IdentityMap are NOT THREAD SAFE!
    //
    private volatile IdentityMap<Type, ConcurrentIterator> listeners = new IdentityMap<Type, ConcurrentIterator>(32, ConnectionManager.LOAD_FACTOR);

    // synchronized is used here to ensure the "single writer principle", and make sure that ONLY one thread at a time can enter this
    // section. Because of this, we can have unlimited reader threads all going at the same time, without contention (which is our
    // use-case 99% of the time)

    public
    OnMessageReceivedManager(final Logger logger) {
        this.logger = logger;
    }

    public
    void add(final OnMessageReceived listener) {
        final Class<?> type = identifyType(listener);

        synchronized (this) {
            ConcurrentIterator subscribedListeners = listeners.get(type);
            if (subscribedListeners == null) {
                subscribedListeners = new ConcurrentIterator();
                listeners.put(type, subscribedListeners);
            }

            //noinspection unchecked
            subscribedListeners.add(listener);
        }
    }

    /**
     * The returned value indicates how many listeners are left in this manager
     *
     * @return >= 0 if the listener was removed, -1 otherwise
     */
    public
    int removeWithSize(final OnMessageReceived listener) {
        final Class<?> type = identifyType(listener);

        int size = -1; // default is "not found"
        synchronized (this) {
            // access a snapshot of the listeners (single-writer-principle)
            final ConcurrentIterator concurrentIterator = listeners.get(type);
            if (concurrentIterator != null) {
                //noinspection unchecked
                boolean removed = concurrentIterator.remove(listener);
                if (removed) {
                    size = concurrentIterator.size();
                }
            }
        }

        return size;
    }

    /**
     * @return true if a listener was found, false otherwise
     */
    @SuppressWarnings("unchecked")
    public
    boolean notifyReceived(final C connection, final Object message, final AtomicBoolean shutdown) {
        boolean found = false;
        Class<?> objectType = message.getClass();


        // this is the GLOBAL version (unless it's the call from below, then it's the connection scoped version)
        final IdentityMap<Type, ConcurrentIterator> listeners = REF.get(this);
        ConcurrentIterator concurrentIterator = listeners.get(objectType);

        if (concurrentIterator != null) {
            ConcurrentEntry<OnMessageReceived<C, Object>> head = headREF.get(concurrentIterator);
            ConcurrentEntry<OnMessageReceived<C, Object>> current = head;

            OnMessageReceived<C, Object> listener;
            while (current != null && !shutdown.get()) {
                listener = current.getValue();
                current = current.next();

                try {
                    listener.received(connection, message);
                } catch (Exception e) {
                    if (listener instanceof OnError) {
                        ((OnError<C>) listener).error(connection, e);
                    }
                    else {
                        logger.error("Unable to notify on message '{}' for listener '{}', connection '{}'.",
                                     objectType,
                                     listener,
                                     connection,
                                     e);
                    }
                }
            }

            found = head != null;  // true if we have something to publish to, otherwise false
        }

        // we march through all super types of the object, and find the FIRST set
        // of listeners that are registered and cast it as that, and notify the method.
        // NOTICE: we do NOT call ALL TYPES -- meaning, if we have Object->Foo->Bar
        // and have listeners for Object and Foo
        // we will call Bar (from the above code)
        // we will call Foo (from this code)
        // we will NOT call Object (since we called Foo). If Foo was not registered, THEN we would call object!

        objectType = objectType.getSuperclass();
        while (objectType != null) {
            // check to see if we have what we are looking for in our CURRENT class
            concurrentIterator = listeners.get(objectType);
            if (concurrentIterator != null) {
                ConcurrentEntry<OnMessageReceived<C, Object>> head = headREF.get(concurrentIterator);
                ConcurrentEntry<OnMessageReceived<C, Object>> current = head;

                OnMessageReceived<C, Object> listener;
                while (current != null && !shutdown.get()) {
                    listener = current.getValue();
                    current = current.next();

                    try {
                        listener.received(connection, message);
                    } catch (Exception e) {
                        if (listener instanceof OnError) {
                            ((OnError<C>) listener).error(connection, e);
                        }
                        else {
                            logger.error("Unable to notify on message '{}' for listener '{}', connection '{}'.",
                                         objectType,
                                         listener,
                                         connection,
                                         e);
                        }
                    }
                }

                found = head != null;  // true if we have something to publish to, otherwise false
                break;
            }

            // NO MATCH, so walk up.
            objectType = objectType.getSuperclass();
        }

        return found;
    }

    /**
     * @return true if the listener was removed, false otherwise
     */
    public synchronized
    boolean removeAll(final Class<?> classType) {
        boolean found;

        found = listeners.remove(classType) != null;

        return found;
    }

    /**
     * called on shutdown
     */
    public synchronized
    void clear() {
        // The iterators for this map are NOT THREAD SAFE!
        // using .entries() is what we are supposed to use!
        final IdentityMap.Entries<Type, ConcurrentIterator> entries = listeners.entries();
        for (final IdentityMap.Entry<Type, ConcurrentIterator> next : entries) {
            if (next.value != null) {
                next.value.clear();
            }
        }

        listeners.clear();
    }
}
