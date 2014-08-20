package dorkbox.network.connection;


import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import dorkbox.network.rmi.RmiMessages;
import dorkbox.network.util.ConcurrentHashMapFactory;
import dorkbox.util.ClassHelper;

//note that we specifically DO NOT implement equals/hashCode, because we cannot create two separate
// objects that are somehow equal to each other.
public class ConnectionManager implements ListenerBridge, ISessionManager {

    private volatile ConcurrentHashMapFactory<Type, CopyOnWriteArrayList<Listener<Connection, Object>>> listeners;
    private volatile ConcurrentHashMapFactory<Connection, ConnectionManager> localManagers;

    private volatile CopyOnWriteArrayList<Connection> connections = new CopyOnWriteArrayList<Connection>();

    /** Used by the listener subsystem to determine types. */
    private final Class<?> baseClass;
    protected final org.slf4j.Logger logger;
    private final String name;
    volatile boolean shutdown = false;

    public ConnectionManager(String name, Class<?> baseClass) {
        this.name = name;
        logger = org.slf4j.LoggerFactory.getLogger(name);

        this.baseClass = baseClass;

        listeners = new ConcurrentHashMapFactory<Type, CopyOnWriteArrayList<Listener<Connection, Object>>>() {
            private static final long serialVersionUID = 8404650379739727012L;

            @Override
            public CopyOnWriteArrayList<Listener<Connection, Object>> createNewOject(Object... args) {
                return new CopyOnWriteArrayList<Listener<Connection, Object>>();
            }
        };

        localManagers = new ConcurrentHashMapFactory<Connection, ConnectionManager>() {
            private static final long serialVersionUID = -1656860453153611896L;

            @Override
            public ConnectionManager createNewOject(Object... args) {
                return new ConnectionManager(ConnectionManager.this.name + "-" + args[0] + " Specific", ConnectionManager.this.baseClass);
            }
        };
    }

    /**
     * Adds a listener to this connection/endpoint to be notified of
     * connect/disconnect/idle/receive(object) events.
     * <p>
     * If the listener already exists, it is not added again.
     * <p>
     * When called by a server, NORMALLY listeners are added at the GLOBAL level
     * (meaning, I add one listener, and ALL connections are notified of that
     * listener.
     * <p>
     * It is POSSIBLE to add a server connection ONLY (ie, not global) listener
     * (via connection.addListener), meaning that ONLY that listener attached to
     * the connection is notified on that event (ie, admin type listeners)
     */
    @SuppressWarnings("rawtypes")
    @Override
    public final void add(Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null.");
        }

        // find the class that uses Listener.class.
        Class<?> clazz = listener.getClass();
        while (clazz.getSuperclass() != Listener.class) {
            clazz = clazz.getSuperclass();
        }

        // this is the connection generic parameter for the listener
        Class<?> genericClass = ClassHelper.getGenericParameterAsClassForSuperClass(clazz, 0);

        // if we are null, it means that we have no generics specified for our listener!
        if (genericClass == baseClass || genericClass == null) {
            // we are the base class, so we are fine.
            addListener0(listener);
            return;

        } else if (ClassHelper.hasInterface(Connection.class, genericClass) &&
                  !ClassHelper.hasParentClass(baseClass, genericClass)) {

            // now we must make sure that the PARENT class is NOT the base class. ONLY the base class is allowed!
            addListener0(listener);
            return;
        }

        // didn't successfully add the listener.
        throw new RuntimeException("Unable to add incompatible connection types as a listener!");
    }

    /**
     * INTERNAL USE ONLY
     */
    @SuppressWarnings({"unchecked","rawtypes"})
    private final void addListener0(Listener listener) {
        Class<?> type = listener.getObjectType();

        CopyOnWriteArrayList<Listener<Connection, Object>> list = listeners.getOrCreate(type);
        list.addIfAbsent(listener);

        logger.trace("listener added: {} <{}>", listener.getClass().getName(), listener.getObjectType());
    }

    /**
     * Removes a listener from this connection/endpoint to NO LONGER be notified
     * of connect/disconnect/idle/receive(object) events.
     * <p>
     * When called by a server, NORMALLY listeners are added at the GLOBAL level
     * (meaning, I add one listener, and ALL connections are notified of that
     * listener.
     * <p>
     * It is POSSIBLE to remove a server-connection 'non-global' listener (via
     * connection.removeListener), meaning that ONLY that listener attached to
     * the connection is removed
     */
    @SuppressWarnings("rawtypes")
    @Override
    public final void remove(Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null.");
        }

        Class<?> type = listener.getObjectType();

        CopyOnWriteArrayList<Listener<Connection, Object>> list = listeners.get(type);
        if (list != null) {
            list.remove(listener);
        }

        logger.trace("listener removed: {} <{}>", listener.getClass().getName(), listener.getObjectType());
    }

    /**
     * Removes all registered listeners from this connection/endpoint to NO
     * LONGER be notified of connect/disconnect/idle/receive(object) events.
     */
   @Override
   public final void removeAll() {
       listeners.clear();

       logger.trace("all listeners removed !!");
   }

   /**
    * Removes all registered listeners (of the object type) from this
    * connection/endpoint to NO LONGER be notified of
    * connect/disconnect/idle/receive(object) events.
    */
    @Override
    public final void removeAll(Class<?> classType) {
        if (classType == null) {
            throw new IllegalArgumentException("classType cannot be null.");
        }

        listeners.remove(classType);

        logger.trace("all listeners removed for type: {}", classType.getClass().getName());
    }


    /**
     * Invoked when a message object was received from a remote peer.
     * <p>
     * If data is sent in response to this event, the connection data is automatically flushed to the wire. If the data is sent in a separate thread,
     * {@link connection.send().flush()} must be called manually.
     *
     * {@link ISessionManager}
     */
    @Override
    public final void notifyOnMessage(Connection connection, Object message) {
        notifyOnMessage(connection, message, false);
    }

    private final void notifyOnMessage(Connection connection, Object message, boolean foundListener) {
        Class<?> objectType = message.getClass();

        // this is the GLOBAL version (unless it's the call from below, then it's the connection scoped version)
        CopyOnWriteArrayList<Listener<Connection, Object>> list = listeners.get(objectType);
        if (list != null) {
            for (Listener<Connection, Object> listener : list) {
                if (shutdown) {
                    return;
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
                list = listeners.get(objectType);

                if (list != null) {
                    break;
                }

                // NO MATCH, so walk up.
                objectType = objectType.getSuperclass();
            }

            if (list != null) {
                for (Listener<Connection, Object> listener : list) {
                    if (shutdown) {
                        return;
                    }

                    listener.received(connection, message);
                    foundListener = true;
                }
            } else if (!foundListener) {
                logger.debug("----------- LISTENER NOT REGISTERED FOR TYPE: {}", message.getClass().getSimpleName());
            }
        }

        // only run a flush once
        if (foundListener) {
            connection.send().flush();
        }

        // now have to account for additional connection listener managers (non-global).
        ConnectionManager localManager = localManagers.get(connection);
        if (localManager != null) {
            // if we found a listener during THIS method call, we need to let the NEXT method call know,
            // so it doesn't spit out error for not handling a message (since that message MIGHT have
            // been found in this method).
            localManager.notifyOnMessage(connection, message, foundListener);
        }
    }

    /**
     * Invoked when a Connection has been idle for a while.
     *
     * {@link ISessionManager}
     */
    @Override
    public final void notifyOnIdle(Connection connection) {
        Set<Entry<Type, CopyOnWriteArrayList<Listener<Connection, Object>>>> entrySet = listeners.entrySet();
        CopyOnWriteArrayList<Listener<Connection,Object>> list;
        for (Entry<Type, CopyOnWriteArrayList<Listener<Connection, Object>>> entry : entrySet) {
            list = entry.getValue();
            if (list != null) {
                for (Listener<Connection,Object> listener : list) {
                    if (shutdown) {
                        return;
                    }

                    listener.idle(connection);
                }
                connection.send().flush();
            }
        }

        // now have to account for additional (local) listener managers.
        ConnectionManager localManager = localManagers.get(connection);
        if (localManager != null) {
            localManager.notifyOnIdle(connection);
        }
    }

    /**
     * Invoked when a {@link Channel} is open, bound to a local address, and connected to a remote address.
     *
     * {@link ISessionManager}
     */
    @Override
    public void connectionConnected(Connection connection) {
        // only TCP channels are passed in.
        // create a new connection!
        connections.add(connection);

        try {
            Set<Entry<Type, CopyOnWriteArrayList<Listener<Connection, Object>>>> entrySet = listeners.entrySet();
            CopyOnWriteArrayList<Listener<Connection,Object>> list;
            for (Entry<Type, CopyOnWriteArrayList<Listener<Connection, Object>>> entry : entrySet) {
                list = entry.getValue();
                if (list != null) {
                    for (Listener<Connection,Object> listener : list) {
                        if (shutdown) {
                            return;
                        }
                        listener.connected(connection);
                    }
                    connection.send().flush();
                }
            }

            // now have to account for additional (local) listener managers.
            ConnectionManager localManager = localManagers.get(connection);
            if (localManager != null) {
                localManager.connectionConnected(connection);
            }
        } catch (Throwable t) {
            connectionError(connection, t);
        }
    }

    /**
     * Invoked when a {@link Channel} was disconnected from its remote peer.
     *
     * {@link ISessionManager}
     */
    @Override
    public void connectionDisconnected(Connection connection) {
        Set<Entry<Type, CopyOnWriteArrayList<Listener<Connection, Object>>>> entrySet = listeners.entrySet();
        CopyOnWriteArrayList<Listener<Connection,Object>> list;
        for (Entry<Type, CopyOnWriteArrayList<Listener<Connection, Object>>> entry : entrySet) {
            list = entry.getValue();
            if (list != null) {
                for (Listener<Connection, Object> listener : list) {
                    if (shutdown) {
                        return;
                    }

                    listener.disconnected(connection);
                }
            }
        }

        // now have to account for additional (local) listener managers.
        ConnectionManager localManager = localManagers.get(connection);
        if (localManager != null) {
            localManager.connectionDisconnected(connection);

            // remove myself from the "global" listeners so we can have our memory cleaned up.
            localManagers.remove(connection);
        }

        connections.remove(connection);
    }


    /**
     * Invoked when there is an error of some kind during the up/down stream process
     *
     * {@link ISessionManager}
     */
    @Override
    public void connectionError(Connection connection, Throwable throwable) {
        Set<Entry<Type, CopyOnWriteArrayList<Listener<Connection, Object>>>> entrySet = listeners.entrySet();
        CopyOnWriteArrayList<Listener<Connection,Object>> list;
        for (Entry<Type, CopyOnWriteArrayList<Listener<Connection, Object>>> entry : entrySet) {
            list = entry.getValue();
            if (list != null) {
                for (Listener<Connection, Object> listener : list) {
                    if (shutdown) {
                        return;
                    }

                    listener.error(connection, throwable);
                }
                connection.send().flush();
            }
        }

        // now have to account for additional (local) listener managers.
        ConnectionManager localManager = localManagers.get(connection);
        if (localManager != null) {
            localManager.connectionError(connection, throwable);
        }
    }

    /**
     * Returns a non-modifiable list of active connections
     *
     * {@link ISessionManager}
     */
    @Override
    public List<Connection> getConnections() {
        return Collections.unmodifiableList(connections);
    }



    final ConnectionManager addListenerManager(Connection connection) {
        // when we are a server, NORMALLY listeners are added at the GLOBAL level (meaning, I add one listener, and ALL connections
        // are notified of that listener.
        // it is POSSIBLE to add a connection-specfic listener (via connection.addListener), meaning that ONLY
        // that listener is notified on that event (ie, admin type listeners)

        ConnectionManager lm = localManagers.getOrCreate(connection, connection.toString());

        logger.debug("Connection specific Listener Manager added on connection: {}", connection);

        return lm;
    }

    final void removeListenerManager(Connection connection) {
        localManagers.remove(connection);
    }


    /**
     * BE CAREFUL! Only for internal use!
     *
     * @return Returns a FAST list of active connections.
     */
    public final Collection<Connection> getConnections0() {
        return connections;
    }

    /**
     * BE CAREFUL! Only for internal use!
     *
     * @return Returns a FAST first connection (for client!).
     */
    public final Connection getConnection0() {
        if (connections.iterator().hasNext()) {
            return connections.iterator().next();
        } else {
            throw new RuntimeException("Not connected to a remote computer. Unable to continue!");
        }
    }

    /**
     * BE CAREFUL! Only for internal use!
     *
     * @return a boolean indicating if there are any listeners registered with this manager.
     */
    final boolean hasListeners() {
        return listeners.isEmpty();
    }

    /**
     * Closes all associated resources/threads/connections
     */
    final void stop() {
        shutdown = true;

        // disconnect the sessions
        closeConnections();

        listeners.clear();
    }

    /**
     * Close all connections ONLY
     */
    final void closeConnections() {
        // close the sessions
        Iterator<Connection> iterator = connections.iterator();
        while (iterator.hasNext()) {
            Connection connection = iterator.next();
            // Close the connection.  Make sure the close operation ends because
            // all I/O operations are asynchronous in Netty.
            // Necessary otherwise workers won't close.
            connection.close();
        }
        connections.clear();
    }
}
