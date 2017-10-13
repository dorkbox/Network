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
package dorkbox.network.connection;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.slf4j.Logger;

import com.esotericsoftware.kryo.util.IdentityMap;

import dorkbox.network.connection.bridge.ConnectionBridgeServer;
import dorkbox.network.connection.bridge.ConnectionExceptSpecifiedBridgeServer;
import dorkbox.network.connection.listenerManagement.OnConnectedManager;
import dorkbox.network.connection.listenerManagement.OnDisconnectedManager;
import dorkbox.network.connection.listenerManagement.OnIdleManager;
import dorkbox.network.connection.listenerManagement.OnMessageReceivedManager;
import dorkbox.util.ClassHelper;
import dorkbox.util.Property;
import dorkbox.util.collections.ConcurrentEntry;

// .equals() compares the identity on purpose,this because we cannot create two separate objects that are somehow equal to each other.
@SuppressWarnings("unchecked")
public
class ConnectionManager<C extends Connection> implements ListenerBridge, ISessionManager<C>, ConnectionPoint, ConnectionBridgeServer<C>,
                                                         ConnectionExceptSpecifiedBridgeServer<C> {
    /**
     * Specifies the load-factor for the IdentityMap used to manage keeping track of the number of connections + listeners
     */
    @Property
    public static final float LOAD_FACTOR = 0.8F;

    private final String loggerName;

    private final OnConnectedManager<C> onConnectedManager;
    private final OnDisconnectedManager<C> onDisconnectedManager;
    private final OnIdleManager<C> onIdleManager;
    private final OnMessageReceivedManager<C> onMessageReceivedManager;



    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private volatile ConcurrentEntry<C> connectionsHead = null; // reference to the first element

    // This is ONLY touched by a single thread, maintains a map of entries for FAST lookup during connection remove.
    private final IdentityMap<C, ConcurrentEntry> connectionEntries = new IdentityMap<C, ConcurrentEntry>(32, ConnectionManager.LOAD_FACTOR);


    @SuppressWarnings("unused")
    private volatile IdentityMap<Connection, ConnectionManager<C>> localManagers = new IdentityMap<Connection, ConnectionManager<C>>(8, ConnectionManager.LOAD_FACTOR);


    // synchronized is used here to ensure the "single writer principle", and make sure that ONLY one thread at a time can enter this
    // section. Because of this, we can have unlimited reader threads all going at the same time, without contention (which is our
    // use-case 99% of the time)
    private final Object singleWriterLock2 = new Object();
    private final Object singleWriterLock3 = new Object();


    // Recommended for best performance while adhering to the "single writer principle". Must be static-final
    private static final AtomicReferenceFieldUpdater<ConnectionManager, IdentityMap> localManagersREF =
                    AtomicReferenceFieldUpdater.newUpdater(ConnectionManager.class,
                                                           IdentityMap.class,
                                                           "localManagers");



    // Recommended for best performance while adhering to the "single writer principle". Must be static-final
    private static final AtomicReferenceFieldUpdater<ConnectionManager, ConcurrentEntry> connectionsREF =
                    AtomicReferenceFieldUpdater.newUpdater(ConnectionManager.class,
                                                           ConcurrentEntry.class,
                                                           "connectionsHead");


    /**
     * Used by the listener subsystem to determine types.
     */
    private final Class<?> baseClass;
    protected final org.slf4j.Logger logger;
    private final AtomicBoolean hasAddedAtLeastOnce = new AtomicBoolean(false);
    final AtomicBoolean shutdown = new AtomicBoolean(false);


    ConnectionManager(final String loggerName, final Class<?> baseClass) {
        this.loggerName = loggerName;
        this.logger = org.slf4j.LoggerFactory.getLogger(loggerName);
        this.baseClass = baseClass;

        onConnectedManager = new OnConnectedManager<C>(logger);
        onDisconnectedManager = new OnDisconnectedManager<C>(logger);
        onIdleManager = new OnIdleManager<C>(logger);
        onMessageReceivedManager = new OnMessageReceivedManager<C>(logger);
    }

    /**
     * Adds a listener to this connection/endpoint to be notified of connect/disconnect/idle/receive(object) events.
     * <p/>
     * When called by a server, NORMALLY listeners are added at the GLOBAL level (meaning, I add one listener, and ALL connections are
     * notified of that listener.
     * <p/>
     * It is POSSIBLE to add a server connection ONLY (ie, not global) listener (via connection.addListener), meaning that ONLY that
     * listener attached to the connection is notified on that event (ie, admin type listeners)
     */
    @SuppressWarnings("rawtypes")
    @Override
    public final
    ListenerBridge add(final Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null.");
        }

        // find the class that uses Listener.class.
        Class<?> clazz = listener.getClass();

//        Class<?>[] interfaces = clazz.getInterfaces();
//        for (Class<?> anInterface : interfaces) {
//        }
//
//        while (!(clazz.getSuperclass() != Object.class)) {
//            clazz = clazz.getSuperclass();
//        }

        // this is the connection generic parameter for the listener
        Class<?> genericClass = ClassHelper.getGenericParameterAsClassForSuperClass(clazz, 0);

        // if we are null, it means that we have no generics specified for our listener!
        //noinspection IfStatementWithIdenticalBranches
        if (genericClass == this.baseClass || genericClass == null) {
            // we are the base class, so we are fine.
            addListener0(listener);
            return this;

        }
        else if (ClassHelper.hasInterface(Connection.class, genericClass) && !ClassHelper.hasParentClass(this.baseClass, genericClass)) {
            // now we must make sure that the PARENT class is NOT the base class. ONLY the base class is allowed!
            addListener0(listener);
            return this;
        }

        // didn't successfully add the listener.
        throw new IllegalArgumentException("Unable to add incompatible connection type as a listener! : " + this.baseClass);
    }

    /**
     * INTERNAL USE ONLY
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private
    void addListener0(final Listener listener) {
        boolean found = false;
        if (listener instanceof Listener.OnConnected) {
            onConnectedManager.add((Listener.OnConnected<C>) listener);
            found = true;
        }
        if (listener instanceof Listener.OnDisconnected) {
            onDisconnectedManager.add((Listener.OnDisconnected<C>) listener);
            found = true;
        }
        if (listener instanceof Listener.OnIdle) {
            onIdleManager.add((Listener.OnIdle<C>) listener);
            found = true;
        }

        if (listener instanceof Listener.OnMessageReceived) {
            onMessageReceivedManager.add((Listener.OnMessageReceived<C, Object>) listener);
            found = true;
        }

        final Logger logger2 = this.logger;
        if (!found) {
            logger2.error("No matching listener types. Unable to add listener: {}",
                          listener.getClass()
                                  .getName());
        }
        else {
            hasAddedAtLeastOnce.set(true);
            if (logger2.isTraceEnabled()) {
                logger2.trace("listener added: {}",
                              listener.getClass()
                                      .getName());
            }
        }
    }

    /**
     * Removes a listener from this connection/endpoint to NO LONGER be notified of connect/disconnect/idle/receive(object) events.
     * <p/>
     * When called by a server, NORMALLY listeners are added at the GLOBAL level (meaning, I add one listener, and ALL connections are
     * notified of that listener.
     * <p/>
     * It is POSSIBLE to remove a server-connection 'non-global' listener (via connection.removeListener), meaning that ONLY that listener
     * attached to the connection is removed
     */
    @SuppressWarnings("rawtypes")
    @Override
    public final
    ListenerBridge remove(final Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null.");
        }

        boolean found = false;
        if (listener instanceof Listener.OnConnected) {
            found = onConnectedManager.remove((Listener.OnConnected<C>) listener);
        }
        if (listener instanceof Listener.OnDisconnected) {
            found |= onDisconnectedManager.remove((Listener.OnDisconnected<C>) listener);
        }
        if (listener instanceof Listener.OnIdle) {
            found |= onIdleManager.remove((Listener.OnIdle<C>) listener);
        }
        if (listener instanceof Listener.OnMessageReceived) {
            found |= onMessageReceivedManager.remove((Listener.OnMessageReceived<C, Object>) listener);
        }

        final Logger logger2 = this.logger;
        if (!found) {
            logger2.error("No matching listener types. Unable to remove listener: {}",
                          listener.getClass()
                                  .getName());

        }
        else if (logger2.isTraceEnabled()) {
            logger2.trace("listener removed: {}",
                          listener.getClass()
                                  .getName());
        }

        return this;
    }

    /**
     * Removes all registered listeners from this connection/endpoint to NO LONGER be notified of connect/disconnect/idle/receive(object)
     * events.
     */
    @Override
    public final
    ListenerBridge removeAll() {
        onMessageReceivedManager.removeAll();

        Logger logger2 = this.logger;
        if (logger2.isTraceEnabled()) {
            logger2.trace("ALL listeners removed !!");
        }

        return this;
    }

    /**
     * Removes all registered listeners (of the object type) from this
     * connection/endpoint to NO LONGER be notified of
     * connect/disconnect/idle/receive(object) events.
     */
    @Override
    public final
    ListenerBridge removeAll(final Class<?> classType) {
        if (classType == null) {
            throw new IllegalArgumentException("classType cannot be null.");
        }

        final Logger logger2 = this.logger;
        if (onMessageReceivedManager.removeAll(classType)) {
            if (logger2.isTraceEnabled()) {
                logger2.trace("All listeners removed for type: {}",
                              classType.getClass()
                                       .getName());
            }
        } else {
            logger2.warn("No listeners found to remove for type: {}",
                          classType.getClass()
                                   .getName());
        }

        return this;
    }


    /**
     * Invoked when a message object was received from a remote peer.
     * <p/>
     * If data is sent in response to this event, the connection data is automatically flushed to the wire. If the data is sent in a separate thread,
     * {@link EndPoint#send().flush()} must be called manually.
     * <p/>
     * {@link ISessionManager}
     */
    @Override
    public final
    void notifyOnMessage(final C connection, final Object message) {
        notifyOnMessage0(connection, message, false);
    }

    @SuppressWarnings("Duplicates")
    private
    boolean notifyOnMessage0(final C connection, final Object message, boolean foundListener) {
        foundListener |= onMessageReceivedManager.notifyReceived(connection, message, shutdown);

        // now have to account for additional connection listener managers (non-global).
        // access a snapshot of the managers (single-writer-principle)
        final IdentityMap<Connection, ConnectionManager<C>> localManagers = localManagersREF.get(this);
        ConnectionManager<C> localManager = localManagers.get(connection);
        if (localManager != null) {
            // if we found a listener during THIS method call, we need to let the NEXT method call know,
            // so it doesn't spit out error for not handling a message (since that message MIGHT have
            // been found in this method).
            foundListener |= localManager.notifyOnMessage0(connection, message, foundListener);
        }

        // only run a flush once
        if (foundListener) {
            connection.send()
                      .flush();
        }
        else {
            if (this.logger.isErrorEnabled()) {
                this.logger.warn("----------- LISTENER NOT REGISTERED FOR TYPE: {}",
                                  message.getClass()
                                         .getSimpleName());
            }
        }
        return foundListener;
    }

    /**
     * Invoked when a Connection has been idle for a while.
     * <p/>
     * {@link ISessionManager}
     */
    @Override
    public final
    void notifyOnIdle(final C connection) {
        boolean foundListener = onIdleManager.notifyIdle(connection, shutdown);

        if (foundListener) {
            connection.send()
                      .flush();
        }

        // now have to account for additional (local) listener managers.
        // access a snapshot of the managers (single-writer-principle)
        final IdentityMap<Connection, ConnectionManager<C>> localManagers = localManagersREF.get(this);
        ConnectionManager<C> localManager = localManagers.get(connection);
        if (localManager != null) {
            localManager.notifyOnIdle(connection);
        }
    }

    /**
     * Invoked when a Channel is open, bound to a local address, and connected to a remote address.
     * <p/>
     * {@link ISessionManager}
     */
    @SuppressWarnings("Duplicates")
    @Override
    public
    void connectionConnected(final C connection) {
        addConnection(connection);

        boolean foundListener = onConnectedManager.notifyConnected(connection, shutdown);

        if (foundListener) {
            connection.send()
                      .flush();
        }

        // now have to account for additional (local) listener managers.
        // access a snapshot of the managers (single-writer-principle)
        final IdentityMap<Connection, ConnectionManager<C>> localManagers = localManagersREF.get(this);
        ConnectionManager<C> localManager = localManagers.get(connection);
        if (localManager != null) {
            localManager.connectionConnected(connection);
        }
    }

    /**
     * Invoked when a Channel was disconnected from its remote peer.
     * <p/>
     * {@link ISessionManager}
     */
    @SuppressWarnings("Duplicates")
    @Override
    public
    void connectionDisconnected(final C connection) {
        boolean foundListener = onDisconnectedManager.notifyDisconnected(connection, shutdown);

        if (foundListener) {
            connection.send()
                      .flush();
        }

        // now have to account for additional (local) listener managers.

        // access a snapshot of the managers (single-writer-principle)
        final IdentityMap<Connection, ConnectionManager<C>> localManagers = localManagersREF.get(this);
        ConnectionManager<C> localManager = localManagers.get(connection);
        if (localManager != null) {
            localManager.connectionDisconnected(connection);

            // remove myself from the "global" listeners so we can have our memory cleaned up.
            removeListenerManager(connection);
        }

        removeConnection(connection);
    }

    /**
     * Adds a custom connection to the server.
     * <p>
     * This should only be used in situations where there can be DIFFERENT types of connections (such as a 'web-based' connection) and
     * you want *this* server instance to manage listeners + message dispatch
     *
     * @param connection the connection to add
     */
    void addConnection(final C connection) {
        // synchronized is used here to ensure the "single writer principle", and make sure that ONLY one thread at a time can enter this
        // section. Because of this, we can have unlimited reader threads all going at the same time, without contention (which is our
        // use-case 99% of the time)
        synchronized (singleWriterLock2) {
            // access a snapshot of the connections (single-writer-principle)
            ConcurrentEntry head = connectionsREF.get(this);

            if (!connectionEntries.containsKey(connection)) {
                head = new ConcurrentEntry<Object>(connection, head);

                connectionEntries.put(connection, head);

                // save this snapshot back to the original (single writer principle)
                connectionsREF.lazySet(this, head);
            }
        }
    }

    /**
     * Removes a custom connection to the server.
     * <p>
     * This should only be used in situations where there can be DIFFERENT types of connections (such as a 'web-based' connection) and
     * you want *this* server instance to manage listeners + message dispatch
     *
     * @param connection the connection to remove
     */
    private
    void removeConnection(C connection) {
        // synchronized is used here to ensure the "single writer principle", and make sure that ONLY one thread at a time can enter this
        // section. Because of this, we can have unlimited reader threads all going at the same time, without contention (which is our
        // use-case 99% of the time)
        synchronized (singleWriterLock2) {
            // access a snapshot of the connections (single-writer-principle)
            ConcurrentEntry concurrentEntry = connectionEntries.get(connection);

            if (concurrentEntry != null) {
                ConcurrentEntry head1 = connectionsREF.get(this);

                if (concurrentEntry == head1) {
                    // if it was second, now it's first
                    head1 = head1.next();
                    //oldHead.clear(); // optimize for GC not possible because of potentially running iterators
                }
                else {
                    concurrentEntry.remove();
                }

                // save this snapshot back to the original (single writer principle)
                connectionsREF.lazySet(this, head1);
                this.connectionEntries.remove(connection);
            }
        }
    }


    /**
     * Returns a non-modifiable list of active connections. This is extremely slow, and not recommended!
     */
    @Override
    public
    List<C> getConnections() {
        synchronized (singleWriterLock2) {
            final IdentityMap.Keys<C> keys = this.connectionEntries.keys();
            return keys.toArray();
        }
    }


    final
    ConnectionManager<C> addListenerManager(final Connection connection) {
        // when we are a server, NORMALLY listeners are added at the GLOBAL level (meaning, I add one listener, and ALL connections
        // are notified of that listener.
        // it is POSSIBLE to add a connection-specific listener (via connection.addListener), meaning that ONLY
        // that listener is notified on that event (ie, admin type listeners)

        ConnectionManager<C> manager;
        boolean created = false;


        // synchronized is used here to ensure the "single writer principle", and make sure that ONLY one thread at a time can enter this
        // section. Because of this, we can have unlimited reader threads all going at the same time, without contention (which is our
        // use-case 99% of the time)
        synchronized (singleWriterLock3) {
            // access a snapshot of the managers (single-writer-principle)
            final IdentityMap<Connection, ConnectionManager<C>> localManagers = localManagersREF.get(this);

            manager = localManagers.get(connection);
            if (manager == null) {
                created = true;
                manager = new ConnectionManager<C>(loggerName + "-" + connection.toString() + " Specific",
                                                   ConnectionManager.this.baseClass);
                localManagers.put(connection, manager);

                // save this snapshot back to the original (single writer principle)
                localManagersREF.lazySet(this, localManagers);
            }
        }

        if (created) {
            Logger logger2 = this.logger;
            if (logger2.isTraceEnabled()) {
                logger2.trace("Connection specific Listener Manager added for connection: {}", connection);
            }
        }

        return manager;
    }

    final
    void removeListenerManager(final Connection connection) {
        boolean wasRemoved = false;

        // synchronized is used here to ensure the "single writer principle", and make sure that ONLY one thread at a time can enter this
        // section. Because of this, we can have unlimited reader threads all going at the same time, without contention (which is our
        // use-case 99% of the time)
        synchronized (singleWriterLock3) {
            // access a snapshot of the managers (single-writer-principle)
            final IdentityMap<Connection, ConnectionManager<C>> localManagers = localManagersREF.get(this);

            final ConnectionManager<C> removed = localManagers.remove(connection);
            if (removed != null) {
                wasRemoved = true;

                // save this snapshot back to the original (single writer principle)
                localManagersREF.lazySet(this, localManagers);
            }
        }

        if (wasRemoved) {
            Logger logger2 = this.logger;
            if (logger2.isTraceEnabled()) {
                logger2.trace("Connection specific Listener Manager removed for connection: {}", connection);
            }
        }
    }


    /**
     * BE CAREFUL! Only for internal use!
     *
     * @return Returns a FAST first connection (for client!).
     */
    public final
    C getConnection0() throws IOException {
        ConcurrentEntry<C> head1 = connectionsREF.get(this);
        if (head1 != null) {
            return head1.getValue();
        }
        else {
            throw new IOException("Not connected to a remote computer. Unable to continue!");
        }
    }

    /**
     * BE CAREFUL! Only for internal use!
     *
     * @return a boolean indicating if there are any listeners registered with this manager.
     */
    final
    boolean hasListeners() {
        return hasAddedAtLeastOnce.get();
    }

    /**
     * Closes all associated resources/threads/connections
     */
    final
    void stop() {
        this.shutdown.set(true);

        // disconnect the sessions
        closeConnections();

        onConnectedManager.clear();
        onDisconnectedManager.clear();
        onIdleManager.clear();
        onMessageReceivedManager.clear();
    }

    /**
     * Close all connections ONLY
     */
    final
    void closeConnections() {

        // synchronized is used here to ensure the "single writer principle", and make sure that ONLY one thread at a time can enter this
        // section. Because of this, we can have unlimited reader threads all going at the same time, without contention (which is our
        // use-case 99% of the time)
        synchronized (singleWriterLock2) {
            // don't need anything fast or fancy here, because this method will only be called once
            final IdentityMap.Keys<C> keys = connectionEntries.keys();
            for (C connection : keys) {
                // Close the connection.  Make sure the close operation ends because
                // all I/O operations are asynchronous in Netty.
                // Also necessary otherwise workers won't close.
                connection.close();
            }

            this.connectionEntries.clear();
            this.connectionsHead = null;
        }
    }

    /**
     * Not implemented, since this would cause horrendous problems.
     *
     * @see dorkbox.network.connection.ConnectionPoint#isWritable()
     */
    @Override
    public
    boolean isWritable() {
        throw new UnsupportedOperationException("Method not implemented");
    }

    /**
     * Exposes methods to send the object to all server connections (except the specified one) over the network. (or via LOCAL when it's a
     * local channel).
     */
    @Override
    public
    ConnectionExceptSpecifiedBridgeServer<C> except() {
        return this;
    }

    /**
     * This will flush the data from EVERY connection on this server.
     * <p/>
     * THIS WILL BE SLOW!
     *
     * @see dorkbox.network.connection.ConnectionPoint#flush()
     */
    @Override
    public
    void flush() {
        ConcurrentEntry<C> current = connectionsREF.get(this);
        C c;
        while (current != null) {
            c = current.getValue();
            current = current.next();

            c.send()
             .flush();
        }
    }

    /**
     * Sends the object to all server connections (except the specified one) over the network using TCP. (or via LOCAL when it's a local
     * channel).
     */
    @Override
    public
    ConnectionPoint TCP(final C connection, final Object message) {
        ConcurrentEntry<C> current = connectionsREF.get(this);
        C c;
        while (current != null) {
            c = current.getValue();
            current = current.next();

            if (c != connection) {
                c.send()
                 .TCP(message);
            }
        }
        return this;
    }

    /**
     * Sends the object to all server connections (except the specified one) over the network using UDP (or via LOCAL when it's a local
     * channel).
     */
    @Override
    public
    ConnectionPoint UDP(final C connection, final Object message) {
        ConcurrentEntry<C> current = connectionsREF.get(this);
        C c;
        while (current != null) {
            c = current.getValue();
            current = current.next();

            if (c != connection) {
                c.send()
                 .UDP(message);
            }
        }
        return this;
    }

    /**
     * Sends the message to other listeners INSIDE this endpoint for EVERY connection. It does not send it to a remote address.
     */
    @Override
    public
    void self(final Object message) {
        ConcurrentEntry<C> current = connectionsREF.get(this);
        C c;
        while (current != null) {
            c = current.getValue();
            current = current.next();

            notifyOnMessage(c, message);
        }
    }

    /**
     * Sends the object all server connections over the network using TCP. (or via LOCAL when it's a local channel).
     */
    @Override
    public
    ConnectionPoint TCP(final Object message) {
        ConcurrentEntry<C> current = connectionsREF.get(this);
        C c;
        while (current != null) {
            c = current.getValue();
            current = current.next();

            c.send()
             .TCP(message);
        }
        return this;
    }

    /**
     * Sends the object all server connections over the network using UDP. (or via LOCAL when it's a local channel).
     */
    @Override
    public
    ConnectionPoint UDP(final Object message) {
        ConcurrentEntry<C> current = connectionsREF.get(this);
        C c;
        while (current != null) {
            c = current.getValue();
            current = current.next();

            c.send()
             .UDP(message);
        }
        return this;
    }

    @Override
    public
    boolean equals(final Object o) {
        return this == o;

    }

    @Override
    public
    int hashCode() {
        return 0;
    }
}
