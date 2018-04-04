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

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.slf4j.Logger;

import com.esotericsoftware.kryo.util.IdentityMap;

import dorkbox.network.connection.Listener.OnConnected;
import dorkbox.network.connection.bridge.ConnectionBridgeServer;
import dorkbox.network.connection.bridge.ConnectionExceptSpecifiedBridgeServer;
import dorkbox.network.connection.listenerManagement.OnConnectedManager;
import dorkbox.network.connection.listenerManagement.OnDisconnectedManager;
import dorkbox.network.connection.listenerManagement.OnIdleManager;
import dorkbox.network.connection.listenerManagement.OnMessageReceivedManager;
import dorkbox.network.connection.ping.PingMessage;
import dorkbox.util.Property;
import dorkbox.util.collections.ConcurrentEntry;
import dorkbox.util.generics.ClassHelper;
import dorkbox.util.generics.TypeResolver;
import io.netty.bootstrap.DatagramCloseMessage;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;

// .equals() compares the identity on purpose,this because we cannot create two separate objects that are somehow equal to each other.
@SuppressWarnings("unchecked")
public
class ConnectionManager<C extends Connection> implements Listeners, ISessionManager, ConnectionPoint, ConnectionBridgeServer,
                                                         ConnectionExceptSpecifiedBridgeServer {
    /**
     * Specifies the load-factor for the IdentityMap used to manage keeping track of the number of connections + listeners
     */
    @Property
    public static final float LOAD_FACTOR = 0.8F;

    // Recommended for best performance while adhering to the "single writer principle". Must be static-final
    private static final AtomicReferenceFieldUpdater<ConnectionManager, IdentityMap> localManagersREF = AtomicReferenceFieldUpdater.newUpdater(
            ConnectionManager.class,
            IdentityMap.class,
            "localManagers");



    // Recommended for best performance while adhering to the "single writer principle". Must be static-final
    private static final AtomicReferenceFieldUpdater<ConnectionManager, ConcurrentEntry> connectionsREF = AtomicReferenceFieldUpdater.newUpdater(
            ConnectionManager.class,
            ConcurrentEntry.class,
            "connectionsHead");


    private final String loggerName;

    private final OnConnectedManager<C> onConnectedManager;
    private final OnDisconnectedManager<C> onDisconnectedManager;
    private final OnIdleManager<C> onIdleManager;
    private final OnMessageReceivedManager<C> onMessageReceivedManager;

    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private volatile ConcurrentEntry<Connection> connectionsHead = null; // reference to the first element

    // This is ONLY touched by a single thread, maintains a map of entries for FAST lookup during connection remove.
    private final IdentityMap<Connection, ConcurrentEntry> connectionEntries = new IdentityMap<Connection, ConcurrentEntry>(32, ConnectionManager.LOAD_FACTOR);


    @SuppressWarnings("unused")
    private volatile IdentityMap<Connection, ConnectionManager> localManagers = new IdentityMap<Connection, ConnectionManager>(8, ConnectionManager.LOAD_FACTOR);


    // synchronized is used here to ensure the "single writer principle", and make sure that ONLY one thread at a time can enter this
    // section. Because of this, we can have unlimited reader threads all going at the same time, without contention (which is our
    // use-case 99% of the time)
    private final Object singleWriterConnectionsLock = new Object();
    private final Object singleWriterLocalManagerLock = new Object();


    /**
     * Used by the listener subsystem to determine types.
     */
    private final Class<?> baseClass;
    protected final org.slf4j.Logger logger;
    private final AtomicBoolean hasAtLeastOneListener = new AtomicBoolean(false);
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
    @Override
    public final
    Listeners add(final Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null.");
        }

        // this is the connection generic parameter for the listener, works for lambda expressions as well
        Class<?> genericClass = ClassHelper.getGenericParameterAsClassForSuperClass(Listener.class, listener.getClass(), 0);

        // if we are null, it means that we have no generics specified for our listener!
        //noinspection IfStatementWithIdenticalBranches
        if (genericClass == this.baseClass || genericClass == TypeResolver.Unknown.class || genericClass == null) {
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
    private
    void addListener0(final Listener listener) {
        boolean found = false;
        if (listener instanceof Listener.OnConnected) {
            onConnectedManager.add((Listener.OnConnected) listener);
            found = true;
        }
        if (listener instanceof Listener.OnDisconnected) {
            onDisconnectedManager.add((Listener.OnDisconnected) listener);
            found = true;
        }
        if (listener instanceof Listener.OnIdle) {
            onIdleManager.add((Listener.OnIdle) listener);
            found = true;
        }

        if (listener instanceof Listener.OnMessageReceived) {
            onMessageReceivedManager.add((Listener.OnMessageReceived) listener);
            found = true;
        }

        if (found) {
            hasAtLeastOneListener.set(true);

            if (logger.isTraceEnabled()) {
                logger.trace("listener added: {}",
                             listener.getClass()
                                     .getName());
            }
        }
        else {
            logger.error("No matching listener types. Unable to add listener: {}",
                         listener.getClass()
                                 .getName());
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
    @Override
    public final
    Listeners remove(final Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null.");
        }

        if (logger.isTraceEnabled()) {
            logger.trace("listener removed: {}",
                         listener.getClass()
                                 .getName());
        }

        boolean found = false;
        int remainingListeners = 0;

        if (listener instanceof Listener.OnConnected) {
            int size = onConnectedManager.removeWithSize((OnConnected) listener);
            if (size >= 0) {
                remainingListeners += size;
                found = true;
            }
        }
        if (listener instanceof Listener.OnDisconnected) {
            int size = onDisconnectedManager.removeWithSize((Listener.OnDisconnected) listener);
            if (size >= 0) {
                remainingListeners += size;
                found |= true;
            }
        }
        if (listener instanceof Listener.OnIdle) {
            int size = onIdleManager.removeWithSize((Listener.OnIdle) listener);
            if (size >= 0) {
                remainingListeners += size;
                found |= true;
            }
        }
        if (listener instanceof Listener.OnMessageReceived) {
            int size =  onMessageReceivedManager.removeWithSize((Listener.OnMessageReceived) listener);
            if (size >= 0) {
                remainingListeners += size;
                found |= true;
            }
        }

        if (found) {
            if (remainingListeners == 0) {
                hasAtLeastOneListener.set(false);
            }
        }
        else {
            logger.error("No matching listener types. Unable to remove listener: {}",
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
    Listeners removeAll() {
        onConnectedManager.clear();
        onDisconnectedManager.clear();
        onIdleManager.clear();
        onMessageReceivedManager.clear();

        logger.trace("ALL listeners removed !!");

        return this;
    }

    /**
     * Removes all registered listeners (of the object type) from this
     * connection/endpoint to NO LONGER be notified of
     * connect/disconnect/idle/receive(object) events.
     */
    @Override
    public final
    Listeners removeAll(final Class<?> classType) {
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
    void onMessage(final ConnectionImpl connection, final Object message) {
        // add the ping listener (internal use only!)
        Class<?> messageClass = message.getClass();
        if (messageClass == PingMessage.class) {
            PingMessage ping = (PingMessage) message;
            if (ping.isReply) {
                connection.updatePingResponse(ping);
            }
            else {
                // return the ping from whence it came
                ping.isReply = true;

                connection.ping0(ping);
            }
        }

        // add the UDP "close hint" to close remote connections (internal use only!)
        else if (messageClass == DatagramCloseMessage.class) {
            connection.forceClose();
        }

        else {
            notifyOnMessage0(connection, message, false);
        }
    }

    @SuppressWarnings("Duplicates")
    private
    boolean notifyOnMessage0(final ConnectionImpl connection, Object message, boolean foundListener) {
        if (connection.manageRmi(message)) {
            // if we are an RMI message/registration, we have very specific, defined behavior. We do not use the "normal" listener callback pattern
            // because these methods are rare, and require special functionality

            // make sure we flush the message to the socket!
            connection.flush();
            return true;
        }

        message = connection.fixupRmi(message);


        foundListener |= onMessageReceivedManager.notifyReceived((C) connection, message, shutdown);

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
            connection.flush();
        }
        else {
            this.logger.warn("----------- LISTENER NOT REGISTERED FOR TYPE: {}",
                              message.getClass()
                                     .getSimpleName());
        }
        return foundListener;
    }

    /**
     * Invoked when a Connection has been idle for a while.
     */
    @Override
    public final
    void onIdle(final ConnectionImpl connection) {
        boolean foundListener = onIdleManager.notifyIdle((C) connection, shutdown);

        if (foundListener) {
            connection.flush();
        }

        // now have to account for additional (local) listener managers.
        // access a snapshot of the managers (single-writer-principle)
        final IdentityMap<Connection, ConnectionManager> localManagers = localManagersREF.get(this);
        ConnectionManager localManager = localManagers.get(connection);
        if (localManager != null) {
            localManager.onIdle(connection);
        }
    }


    /**
     * Invoked when a Channel is open, bound to a local address, and connected to a remote address.
     */
    @Override
    public
    void onConnected(final ConnectionImpl connection) {
        // we add the connection in a different step!

        boolean foundListener = onConnectedManager.notifyConnected((C) connection, shutdown);

        if (foundListener) {
            connection.flush();
        }

        // now have to account for additional (local) listener managers.
        // access a snapshot of the managers (single-writer-principle)
        final IdentityMap<Connection, ConnectionManager> localManagers = localManagersREF.get(this);
        ConnectionManager localManager = localManagers.get(connection);
        if (localManager != null) {
            localManager.onConnected(connection);
        }
    }

    /**
     * Invoked when a Channel was disconnected from its remote peer.
     */
    @Override
    public
    void onDisconnected(final ConnectionImpl connection) {
        logger.trace("onDisconnected({})", connection.id());

        boolean foundListener = onDisconnectedManager.notifyDisconnected((C) connection);

        if (foundListener) {
            connection.flush();
        }

        // now have to account for additional (local) listener managers.

        // access a snapshot of the managers (single-writer-principle)
        final IdentityMap<Connection, ConnectionManager> localManagers = localManagersREF.get(this);
        ConnectionManager localManager = localManagers.get(connection);
        if (localManager != null) {
            localManager.onDisconnected(connection);

            // remove myself from the "global" listeners so we can have our memory cleaned up.
            removeListenerManager(connection);
        }

        removeConnection(connection);
    }

    /**
     * Invoked when a Channel is open, bound to a local address, and connected to a remote address.
     */
    @Override
    public
    void addConnection(ConnectionImpl connection) {
        addConnection0(connection);

        // now have to account for additional (local) listener managers.
        // access a snapshot of the managers (single-writer-principle)
        final IdentityMap<Connection, ConnectionManager> localManagers = localManagersREF.get(this);
        ConnectionManager localManager = localManagers.get(connection);
        if (localManager != null) {
            localManager.addConnection(connection);
        }
    }

    /**
     * Adds a custom connection to the server.
     * <p>
     * This should only be used in situations where there can be DIFFERENT types of connections (such as a 'web-based' connection) and
     * you want *this* server instance to manage listeners + message dispatch
     *
     * @param connection the connection to add
     */
    void addConnection0(final Connection connection) {
        // synchronized is used here to ensure the "single writer principle", and make sure that ONLY one thread at a time can enter this
        // section. Because of this, we can have unlimited reader threads all going at the same time, without contention (which is our
        // use-case 99% of the time)
        synchronized (singleWriterConnectionsLock) {
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
    void removeConnection(Connection connection) {
        // synchronized is used here to ensure the "single writer principle", and make sure that ONLY one thread at a time can enter this
        // section. Because of this, we can have unlimited reader threads all going at the same time, without contention (which is our
        // use-case 99% of the time)
        synchronized (singleWriterConnectionsLock) {
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
        synchronized (singleWriterConnectionsLock) {
            final IdentityMap.Keys<Connection> keys = this.connectionEntries.keys();
            return (List<C>) keys.toArray();
        }
    }

    final
    ConnectionManager addListenerManager(final Connection connection) {
        // when we are a server, NORMALLY listeners are added at the GLOBAL level (meaning, I add one listener, and ALL connections
        // are notified of that listener.
        // it is POSSIBLE to add a connection-specific listener (via connection.addListener), meaning that ONLY
        // that listener is notified on that event (ie, admin type listeners)

        ConnectionManager manager;
        boolean created = false;


        // synchronized is used here to ensure the "single writer principle", and make sure that ONLY one thread at a time can enter this
        // section. Because of this, we can have unlimited reader threads all going at the same time, without contention (which is our
        // use-case 99% of the time)
        synchronized (singleWriterLocalManagerLock) {
            // access a snapshot of the managers (single-writer-principle)
            final IdentityMap<Connection, ConnectionManager> localManagers = localManagersREF.get(this);

            manager = localManagers.get(connection);
            if (manager == null) {
                created = true;
                manager = new ConnectionManager(loggerName + "-" + connection.toString() + " Specific", ConnectionManager.this.baseClass);
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
        synchronized (singleWriterLocalManagerLock) {
            // access a snapshot of the managers (single-writer-principle)
            final IdentityMap<Connection, ConnectionManager> localManagers = localManagersREF.get(this);

            final ConnectionManager removed = localManagers.remove(connection);
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
     * @return a boolean indicating if there are any listeners registered with this manager.
     */
    final
    boolean hasListeners() {
        return hasAtLeastOneListener.get();
    }

    /**
     * Closes all associated resources/threads/connections
     */
    final
    void stop() {
        this.shutdown.set(true);

        // disconnect the sessions
        closeConnections(false);

        onConnectedManager.clear();
        onDisconnectedManager.clear();
        onIdleManager.clear();
        onMessageReceivedManager.clear();
    }

    /**
     * Close all connections ONLY.
     *
     * Only keep the listeners for connections IF we are the client. If we remove listeners as a client, ALL of the client logic will
     * be lost. The server is reactive, so listeners are added to connections as needed (instead of before startup, which is what the client does).
     */
    final
    void closeConnections(boolean keepListeners) {
        LinkedList<ConnectionImpl> closeConnections = new LinkedList<ConnectionImpl>();

        // synchronized is used here to ensure the "single writer principle", and make sure that ONLY one thread at a time can enter this
        // section. Because of this, we can have unlimited reader threads all going at the same time, without contention (which is our
        // use-case 99% of the time)
        synchronized (singleWriterConnectionsLock) {
            // don't need anything fast or fancy here, because this method will only be called once
            final IdentityMap.Keys<Connection> keys = connectionEntries.keys();
            for (Connection connection : keys) {
                // Close the connection.  Make sure the close operation ends because
                // all I/O operations are asynchronous in Netty.
                // Also necessary otherwise workers won't close.
                if (connection instanceof ConnectionImpl) {
                    closeConnections.add((ConnectionImpl) connection);
                }
            }

            this.connectionEntries.clear();
            this.connectionsHead = null;
        }

        // must be outside of the synchronize, otherwise we can potentially deadlock
        for (ConnectionImpl connection : closeConnections) {
            connection.close(keepListeners);
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

    @Override
    public
    void write(final Object object) {
        throw new UnsupportedOperationException("Method not implemented");
    }

    /**
     * Exposes methods to send the object to all server connections (except the specified one) over the network. (or via LOCAL when it's a
     * local channel).
     */
    @Override
    public
    ConnectionExceptSpecifiedBridgeServer except() {
        return this;
    }

    @Override
    public
    <V> Promise<V> newPromise() {
        return ImmediateEventExecutor.INSTANCE.newPromise();
    }

    /**
     * Sends the object to all server connections (except the specified one) over the network using TCP. (or via LOCAL when it's a local
     * channel).
     */
    @Override
    public
    ConnectionPoint TCP(final Connection connection, final Object message) {
        ConcurrentEntry<Connection> current = connectionsREF.get(this);
        Connection c;
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
    ConnectionPoint UDP(final Connection connection, final Object message) {
        ConcurrentEntry<Connection> current = connectionsREF.get(this);
        Connection c;
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
    ConnectionPoint self(final Object message) {
        ConcurrentEntry<ConnectionImpl> current = connectionsREF.get(this);
        ConnectionImpl c;
        while (current != null) {
            c = current.getValue();
            current = current.next();

            onMessage(c, message);
        }
        return this;
    }

    /**
     * Sends the object all server connections over the network using TCP. (or via LOCAL when it's a local channel).
     */
    @Override
    public
    ConnectionPoint TCP(final Object message) {
        ConcurrentEntry<Connection> current = connectionsREF.get(this);
        Connection c;
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
        ConcurrentEntry<Connection> current = connectionsREF.get(this);
        Connection c;
        while (current != null) {
            c = current.getValue();
            current = current.next();

            c.send()
             .UDP(message);
        }
        return this;
    }

    /**
     * Safely sends objects to a destination (such as a custom object or a standard ping). This will automatically choose which protocol
     * is available to use. If you want specify the protocol, use {@link ConnectionManager#TCP(Object)}, etc.
     * <p>
     * By default, this will try in the following order:
     * - TCP (if available)
     * - UDP (if available)
     * - LOCAL
     */
    protected
    ConnectionPoint send(final Object message) {
        ConcurrentEntry<Connection> current = connectionsREF.get(this);
        Connection c;
        while (current != null) {
            c = current.getValue();
            current = current.next();

            c.send(message);
        }
        return this;
    }

    /**
     * Flushes the contents of the TCP/UDP/etc pipes to the actual transport socket.
     */
    @Override
    public
    void flush() {
        ConcurrentEntry<ConnectionImpl> current = connectionsREF.get(this);
        ConnectionImpl c;
        while (current != null) {
            c = current.getValue();
            current = current.next();

            c.flush();
        }
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
