package dorkbox.network.connection;


import dorkbox.network.rmi.RmiMessages;
import dorkbox.network.util.ConcurrentHashMapFactory;
import dorkbox.network.util.exceptions.NetException;
import dorkbox.util.ClassHelper;
import org.slf4j.Logger;

import java.lang.reflect.Type;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.CopyOnWriteArrayList;

//note that we specifically DO NOT implement equals/hashCode, because we cannot create two separate
// objects that are somehow equal to each other.
public
class ConnectionManager implements ListenerBridge, ISessionManager {

    public static Listener<?> unRegisteredType_Listener = null;

    // these are final, because the REFERENCE to these will never change. They ARE NOT immutable objects (meaning their content can change)
    private final ConcurrentHashMapFactory<Type, CopyOnWriteArrayList<ListenerRaw<Connection, Object>>> listeners;
    private final ConcurrentHashMapFactory<Connection, ConnectionManager> localManagers;
    private final CopyOnWriteArrayList<Connection> connections = new CopyOnWriteArrayList<Connection>();

    /**
     * Used by the listener subsystem to determine types.
     */
    private final Class<?> baseClass;
    protected final org.slf4j.Logger logger;
    volatile boolean shutdown = false;

    public
    ConnectionManager(final String loggerName, final Class<?> baseClass) {
        this.logger = org.slf4j.LoggerFactory.getLogger(loggerName);

        this.baseClass = baseClass;

        this.listeners = new ConcurrentHashMapFactory<Type, CopyOnWriteArrayList<ListenerRaw<Connection, Object>>>() {
            private static final long serialVersionUID = 1L;

            @Override
            public
            CopyOnWriteArrayList<ListenerRaw<Connection, Object>> createNewOject(Object... args) {
                return new CopyOnWriteArrayList<ListenerRaw<Connection, Object>>();
            }
        };

        this.localManagers = new ConcurrentHashMapFactory<Connection, ConnectionManager>() {
            private static final long serialVersionUID = 1L;

            @Override
            public
            ConnectionManager createNewOject(Object... args) {
                return new ConnectionManager(loggerName + "-" + args[0] + " Specific", ConnectionManager.this.baseClass);
            }
        };
    }

    /**
     * Adds a listener to this connection/endpoint to be notified of
     * connect/disconnect/idle/receive(object) events.
     * <p/>
     * If the listener already exists, it is not added again.
     * <p/>
     * When called by a server, NORMALLY listeners are added at the GLOBAL level
     * (meaning, I add one listener, and ALL connections are notified of that
     * listener.
     * <p/>
     * It is POSSIBLE to add a server connection ONLY (ie, not global) listener
     * (via connection.addListener), meaning that ONLY that listener attached to
     * the connection is notified on that event (ie, admin type listeners)
     */
    @SuppressWarnings("rawtypes")
    @Override
    public final
    void add(ListenerRaw listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null.");
        }

        // find the class that uses Listener.class.
        Class<?> clazz = listener.getClass();
        while (clazz.getSuperclass() != ListenerRaw.class) {
            clazz = clazz.getSuperclass();
        }

        // this is the connection generic parameter for the listener
        Class<?> genericClass = ClassHelper.getGenericParameterAsClassForSuperClass(clazz, 0);

        // if we are null, it means that we have no generics specified for our listener!
        if (genericClass == this.baseClass || genericClass == null) {
            // we are the base class, so we are fine.
            addListener0(listener);
            return;

        }
        else if (ClassHelper.hasInterface(Connection.class, genericClass) && !ClassHelper.hasParentClass(this.baseClass, genericClass)) {
            // now we must make sure that the PARENT class is NOT the base class. ONLY the base class is allowed!
            addListener0(listener);
            return;
        }

        // didn't successfully add the listener.
        throw new IllegalArgumentException("Unable to add incompatible connection type as a listener! : " + this.baseClass);
    }

    /**
     * INTERNAL USE ONLY
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private
    void addListener0(ListenerRaw listener) {
        Class<?> type = listener.getObjectType();

        CopyOnWriteArrayList<ListenerRaw<Connection, Object>> list = this.listeners.getOrCreate(type);
        list.addIfAbsent(listener);

        Logger logger2 = this.logger;
        if (logger2.isTraceEnabled()) {
            logger2.trace("listener added: {} <{}>",
                          listener.getClass()
                                  .getName(),
                          listener.getObjectType());
        }
    }

    /**
     * Removes a listener from this connection/endpoint to NO LONGER be notified
     * of connect/disconnect/idle/receive(object) events.
     * <p/>
     * When called by a server, NORMALLY listeners are added at the GLOBAL level
     * (meaning, I add one listener, and ALL connections are notified of that
     * listener.
     * <p/>
     * It is POSSIBLE to remove a server-connection 'non-global' listener (via
     * connection.removeListener), meaning that ONLY that listener attached to
     * the connection is removed
     */
    @SuppressWarnings("rawtypes")
    @Override
    public final
    void remove(ListenerRaw listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null.");
        }

        Class<?> type = listener.getObjectType();

        CopyOnWriteArrayList<ListenerRaw<Connection, Object>> list = this.listeners.get(type);
        if (list != null) {
            list.remove(listener);
        }

        Logger logger2 = this.logger;
        if (logger2.isTraceEnabled()) {
            logger2.trace("listener removed: {} <{}>",
                          listener.getClass()
                                  .getName(),
                          listener.getObjectType());
        }
    }

    /**
     * Removes all registered listeners from this connection/endpoint to NO
     * LONGER be notified of connect/disconnect/idle/receive(object) events.
     */
    @Override
    public final
    void removeAll() {
        this.listeners.clear();

        Logger logger2 = this.logger;
        if (logger2.isTraceEnabled()) {
            logger2.trace("all listeners removed !!");
        }
    }

    /**
     * Removes all registered listeners (of the object type) from this
     * connection/endpoint to NO LONGER be notified of
     * connect/disconnect/idle/receive(object) events.
     */
    @Override
    public final
    void removeAll(Class<?> classType) {
        if (classType == null) {
            throw new IllegalArgumentException("classType cannot be null.");
        }

        this.listeners.remove(classType);

        Logger logger2 = this.logger;
        if (logger2.isTraceEnabled()) {
            logger2.trace("all listeners removed for type: {}",
                          classType.getClass()
                                   .getName());
        }
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
    void notifyOnMessage(Connection connection, Object message) {
        notifyOnMessage0(connection, message, false);
    }

    private
    boolean notifyOnMessage0(Connection connection, Object message, boolean foundListener) {
        Class<?> objectType = message.getClass();

        // this is the GLOBAL version (unless it's the call from below, then it's the connection scoped version)
        CopyOnWriteArrayList<ListenerRaw<Connection, Object>> list = this.listeners.get(objectType);
        if (list != null) {
            for (ListenerRaw<Connection, Object> listener : list) {
                if (this.shutdown) {
                    return true;
                }

                listener.received(connection, message);
            }
            foundListener = true;
        }

        if (!(message instanceof RmiMessages)) {
            // we march through all super types of the object, and find the FIRST set
            // of listeners that are registered and cast it as that, and notify the method.
            // NOTICE: we do NOT call ALL TYPE -- meaning, if we have Object->Foo->Bar
            // and have listeners for Object and Foo
            // we will call Bar (from the above code)
            // we will call Foo (from this code)
            // we will NOT call Object (since we called Foo). If Foo was not registered, THEN we would call object!

            list = null;
            objectType = objectType.getSuperclass();
            while (objectType != null) {
                // check to see if we have what we are looking for in our CURRENT class
                list = this.listeners.get(objectType);

                if (list != null) {
                    break;
                }

                // NO MATCH, so walk up.
                objectType = objectType.getSuperclass();
            }

            if (list != null) {
                for (ListenerRaw<Connection, Object> listener : list) {
                    if (this.shutdown) {
                        return true;
                    }

                    listener.received(connection, message);
                    foundListener = true;
                }
            }
        }


        // now have to account for additional connection listener managers (non-global).
        ConnectionManager localManager = this.localManagers.get(connection);
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
        else if (unRegisteredType_Listener != null) {
            unRegisteredType_Listener.received(connection, null);
        }
        else {
            Logger logger2 = this.logger;
            if (logger2.isErrorEnabled()) {
                this.logger.error("----------- LISTENER NOT REGISTERED FOR TYPE: {}",
                                  message.getClass()
                                         .getSimpleName());
            }
        }
        return foundListener;
    }

    public static
    void setUnregisteredTypeListener(Listener<?> listener) {
        unRegisteredType_Listener = listener;
    }

    /**
     * Invoked when a Connection has been idle for a while.
     * <p/>
     * {@link ISessionManager}
     */
    @Override
    public final
    void notifyOnIdle(Connection connection) {
        Set<Entry<Type, CopyOnWriteArrayList<ListenerRaw<Connection, Object>>>> entrySet = this.listeners.entrySet();
        CopyOnWriteArrayList<ListenerRaw<Connection, Object>> list;
        for (Entry<Type, CopyOnWriteArrayList<ListenerRaw<Connection, Object>>> entry : entrySet) {
            list = entry.getValue();
            if (list != null) {
                for (ListenerRaw<Connection, Object> listener : list) {
                    if (this.shutdown) {
                        return;
                    }

                    listener.idle(connection);
                }
                connection.send()
                          .flush();
            }
        }

        // now have to account for additional (local) listener managers.
        ConnectionManager localManager = this.localManagers.get(connection);
        if (localManager != null) {
            localManager.notifyOnIdle(connection);
        }
    }

    /**
     * Invoked when a Channel is open, bound to a local address, and connected to a remote address.
     * <p/>
     * {@link ISessionManager}
     */
    @Override
    public
    void connectionConnected(Connection connection) {
        // create a new connection!
        this.connections.add(connection);

        try {
            Set<Entry<Type, CopyOnWriteArrayList<ListenerRaw<Connection, Object>>>> entrySet = this.listeners.entrySet();
            CopyOnWriteArrayList<ListenerRaw<Connection, Object>> list;
            for (Entry<Type, CopyOnWriteArrayList<ListenerRaw<Connection, Object>>> entry : entrySet) {
                list = entry.getValue();
                if (list != null) {
                    for (ListenerRaw<Connection, Object> listener : list) {
                        if (this.shutdown) {
                            return;
                        }
                        listener.connected(connection);
                    }
                    connection.send()
                              .flush();
                }
            }

            // now have to account for additional (local) listener managers.
            ConnectionManager localManager = this.localManagers.get(connection);
            if (localManager != null) {
                localManager.connectionConnected(connection);
            }
        } catch (Throwable t) {
            connectionError(connection, t);
        }
    }

    /**
     * Invoked when a Channel was disconnected from its remote peer.
     * <p/>
     * {@link ISessionManager}
     */
    @Override
    public
    void connectionDisconnected(Connection connection) {
        Set<Entry<Type, CopyOnWriteArrayList<ListenerRaw<Connection, Object>>>> entrySet = this.listeners.entrySet();
        CopyOnWriteArrayList<ListenerRaw<Connection, Object>> list;
        for (Entry<Type, CopyOnWriteArrayList<ListenerRaw<Connection, Object>>> entry : entrySet) {
            list = entry.getValue();
            if (list != null) {
                for (ListenerRaw<Connection, Object> listener : list) {
                    if (this.shutdown) {
                        return;
                    }

                    listener.disconnected(connection);
                }
            }
        }

        // now have to account for additional (local) listener managers.
        ConnectionManager localManager = this.localManagers.get(connection);
        if (localManager != null) {
            localManager.connectionDisconnected(connection);

            // remove myself from the "global" listeners so we can have our memory cleaned up.
            this.localManagers.remove(connection);
        }

        this.connections.remove(connection);
    }


    /**
     * Invoked when there is an error of some kind during the up/down stream process
     * <p/>
     * {@link ISessionManager}
     */
    @Override
    public
    void connectionError(Connection connection, Throwable throwable) {
        Set<Entry<Type, CopyOnWriteArrayList<ListenerRaw<Connection, Object>>>> entrySet = this.listeners.entrySet();
        CopyOnWriteArrayList<ListenerRaw<Connection, Object>> list;
        for (Entry<Type, CopyOnWriteArrayList<ListenerRaw<Connection, Object>>> entry : entrySet) {
            list = entry.getValue();
            if (list != null) {
                for (ListenerRaw<Connection, Object> listener : list) {
                    if (this.shutdown) {
                        return;
                    }

                    listener.error(connection, throwable);
                }
                connection.send()
                          .flush();
            }
        }

        // now have to account for additional (local) listener managers.
        ConnectionManager localManager = this.localManagers.get(connection);
        if (localManager != null) {
            localManager.connectionError(connection, throwable);
        }
    }

    /**
     * Returns a non-modifiable list of active connections
     * <p/>
     * {@link ISessionManager}
     */
    @Override
    public
    List<Connection> getConnections() {
        return Collections.unmodifiableList(this.connections);
    }



    final
    ConnectionManager addListenerManager(Connection connection) {
        // when we are a server, NORMALLY listeners are added at the GLOBAL level (meaning, I add one listener, and ALL connections
        // are notified of that listener.
        // it is POSSIBLE to add a connection-specfic listener (via connection.addListener), meaning that ONLY
        // that listener is notified on that event (ie, admin type listeners)

        ConnectionManager lm = this.localManagers.getOrCreate(connection, connection.toString());

        Logger logger2 = this.logger;
        if (logger2.isDebugEnabled()) {
            this.logger.debug("Connection specific Listener Manager added on connection: {}", connection);
        }

        return lm;
    }

    final
    void removeListenerManager(Connection connection) {
        this.localManagers.remove(connection);
    }


    /**
     * BE CAREFUL! Only for internal use!
     *
     * @return Returns a FAST list of active connections.
     */
    public final
    Collection<Connection> getConnections0() {
        return this.connections;
    }

    /**
     * BE CAREFUL! Only for internal use!
     *
     * @return Returns a FAST first connection (for client!).
     */
    public final
    Connection getConnection0() {
        if (this.connections.iterator()
                            .hasNext()) {
            return this.connections.iterator()
                                   .next();
        }
        else {
            throw new NetException("Not connected to a remote computer. Unable to continue!");
        }
    }

    /**
     * BE CAREFUL! Only for internal use!
     *
     * @return a boolean indicating if there are any listeners registered with this manager.
     */
    final
    boolean hasListeners() {
        return this.listeners.isEmpty();
    }

    /**
     * Closes all associated resources/threads/connections
     */
    final
    void stop() {
        this.shutdown = true;

        // disconnect the sessions
        closeConnections();

        this.listeners.clear();
    }

    /**
     * Close all connections ONLY
     */
    final
    void closeConnections() {
        // close the sessions
        Iterator<Connection> iterator = this.connections.iterator();
        while (iterator.hasNext()) {
            Connection connection = iterator.next();
            // Close the connection.  Make sure the close operation ends because
            // all I/O operations are asynchronous in Netty.
            // Necessary otherwise workers won't close.
            connection.close();
        }
        this.connections.clear();
    }

    @Override
    public
    boolean equals(final Object o) {
        return false;

    }

    @Override
    public
    int hashCode() {
        return 0;
    }
}
