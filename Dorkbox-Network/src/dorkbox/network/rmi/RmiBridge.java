package dorkbox.network.rmi;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.PriorityQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.FieldSerializer;
import com.esotericsoftware.kryo.util.IntMap;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.ListenerRaw;
import dorkbox.network.util.SerializationManager;
import dorkbox.util.primativeCollections.ObjectIntMap;

/**
 * Allows methods on objects to be invoked remotely over TCP. Objects are
 * {@link #register(int, Object) registered} with an ID. The remote end of
 * connections that have been {@link #addConnection(Connection) added} are
 * allowed to {@link #getRemoteObject(Connection, int, Class) access} registered
 * objects.
 * <p>
 * It costs at least 2 bytes more to use remote method invocation than just
 * sending the parameters. If the method has a return value which is not
 * {@link RemoteObject#setNonBlocking(boolean) ignored}, an extra byte is
 * written. If the type of a parameter is not final (note primitives are final)
 * then an extra byte is written for that parameter.
 *
 * @author Nathan Sweet <misc@n4te.com>, Nathan Robinson
 */
public class RmiBridge {
    private static final String OBJECT_ID = "objectID";

    static CopyOnWriteArrayList<RmiBridge> instances = new CopyOnWriteArrayList<RmiBridge>();

    private static final HashMap<Class<?>, CachedMethod[]> methodCache = new HashMap<Class<?>, CachedMethod[]>();

    static final byte kReturnValMask = (byte) 0x80; // 1000 0000
    static final byte kReturnExMask = (byte) 0x40;  // 0100 0000


    private static final int N_THREADS = 5;
    private static final int POOL_SIZE = 5;

    private static final Executor defaultExectutor = new ThreadPoolExecutor(N_THREADS, POOL_SIZE,
                                                                              5, TimeUnit.SECONDS,
                                                                              new LinkedBlockingQueue<Runnable>(N_THREADS * POOL_SIZE));

    // can be access by DIFFERENT threads.
    volatile IntMap<Object> idToObject = new IntMap<Object>();

    volatile ObjectIntMap<Object> objectToID = new ObjectIntMap<Object>();

    private CopyOnWriteArrayList<Connection> connections = new CopyOnWriteArrayList<Connection>();

    private Executor executor;

    // the name of who created this object space.
    private final org.slf4j.Logger logger;

    private final ListenerRaw<Connection, InvokeMethod> invokeListener= new ListenerRaw<Connection, InvokeMethod>() {
            @Override
            public void received(final Connection connection, final InvokeMethod invokeMethod) {
                boolean found = false;

                Iterator<Connection> iterator = RmiBridge.this.connections.iterator();
                while (iterator.hasNext()) {
                    Connection c = iterator.next();
                    if (c == connection) {
                        found = true;
                        break;
                    }
                }

                // The InvokeMethod message is not for a connection in this ObjectSpace.
                if (!found) {
                    return;
                }

                final Object target = RmiBridge.this.idToObject.get(invokeMethod.objectID);
                if (target == null) {
                    RmiBridge.this.logger.warn("Ignoring remote invocation request for unknown object ID: {}",
                                invokeMethod.objectID);
                    return;
                }

                if (RmiBridge.this.executor == null) {
                    defaultExectutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            invoke(connection,
                                   target,
                                   invokeMethod);
                        }
                    });
                } else {
                    RmiBridge.this.executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            invoke(connection,
                                   target,
                                   invokeMethod);
                        }
                    });
                }
            }

            @Override
            public void disconnected(Connection connection) {
                removeConnection(connection);
            }
        };

    /**
     * Creates an ObjectSpace with no connections. Connections must be
     * {@link #connectionConnected(Connection) added} to allow the remote end of
     * the connections to access objects in this ObjectSpace.
     * <p>
     * For safety, this should ONLY be called by {@link EndPoint#getRmiBridge() }
     */
    public RmiBridge(Logger logger) {
        this.logger = logger;
        instances.addIfAbsent(this);
    }

    /**
     * Sets the executor used to invoke methods when an invocation is received
     * from a remote endpoint. By default, no executor is set and invocations
     * occur on the network thread, which should not be blocked for long.
     *
     * @param executor
     *            May be null.
     */
    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    /**
     * Registers an object to allow the remote end of the ObjectSpace's
     * connections to access it using the specified ID.
     * <p>
     * If a connection is added to multiple ObjectSpaces, the same object ID
     * should not be registered in more than one of those ObjectSpaces.
     *
     * @param objectID
     *            Must not be Integer.MAX_VALUE.
     * @see #getRemoteObject(Connection, int, Class...)
     */
    public void register(int objectID, Object object) {
        if (objectID == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("objectID cannot be Integer.MAX_VALUE.");
        }
        if (object == null) {
            throw new IllegalArgumentException("object cannot be null.");
        }
        this.idToObject.put(objectID, object);
        this.objectToID.put(object, objectID);

        Logger logger2 = this.logger;
        if (logger2.isTraceEnabled()) {
            this.logger.trace("Object registered with ObjectSpace as {}:{}", objectID, object);
        }
    }

    /**
     * Causes this ObjectSpace to stop listening to the connections for method
     * invocation messages.
     */
    public void close() {
        Iterator<Connection> iterator = this.connections.iterator();
        while (iterator.hasNext()) {
            Connection connection = iterator.next();
            connection.listeners().remove(this.invokeListener);
        }

        instances.remove(this);
        Logger logger2 = this.logger;
        if (logger2.isTraceEnabled()) {
            logger2.trace("Closed ObjectSpace.");
        }
    }

    /**
     * Removes an object. The remote end of the ObjectSpace's connections will
     * no longer be able to access it.
     */
    public void remove(int objectID) {
        Object object = this.idToObject.remove(objectID);
        if (object != null) {
            this.objectToID.remove(object, 0);
        }

        Logger logger2 = this.logger;
        if (logger2.isTraceEnabled()) {
            logger2.trace("Object {} removed from ObjectSpace: {}", objectID, object);
        }
    }

    /**
     * Removes an object. The remote end of the ObjectSpace's connections will
     * no longer be able to access it.
     */
    public void remove(Object object) {
        if (!this.idToObject.containsValue(object, true)) {
            return;
        }

        int objectID = this.idToObject.findKey(object, true, -1);
        this.idToObject.remove(objectID);
        this.objectToID.remove(object, 0);

        Logger logger2 = this.logger;
        if (logger2.isTraceEnabled()) {
            logger2.trace("Object {} removed from ObjectSpace: {}", objectID, object);
        }
    }

    /**
     * Allows the remote end of the specified connection to access objects
     * registered in this ObjectSpace.
     */
    public void addConnection(Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("connection cannot be null.");
        }

        this.connections.addIfAbsent(connection);
        connection.listeners().add(this.invokeListener);

        this.logger.trace("Added connection to ObjectSpace: {}", connection);
    }

    /**
     * Removes the specified connection, it will no longer be able to access
     * objects registered in this ObjectSpace.
     */
    public void removeConnection(Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("connection cannot be null.");
        }

        connection.listeners().remove(this.invokeListener);
        this.connections.remove(connection);

        this.logger.trace("Removed connection from ObjectSpace: {}", connection);
    }

    /**
     * Invokes the method on the object and, if necessary, sends the result back
     * to the connection that made the invocation request. This method is
     * invoked on the update thread of the {@link EndPoint} for this ObjectSpace
     * and unless an {@link #setExecutor(Executor) executor} has been set.
     *
     * @param connection
     *            The remote side of this connection requested the invocation.
     */
    protected void invoke(Connection connection, Object target, InvokeMethod invokeMethod) {
        if (this.logger.isDebugEnabled()) {
            String argString = "";
            if (invokeMethod.args != null) {
                argString = Arrays.deepToString(invokeMethod.args);
                argString = argString.substring(1, argString.length() - 1);
            }

            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append(connection.toString()).append(" received: ").append(target.getClass().getSimpleName());
            stringBuilder.append(":").append(invokeMethod.objectID);
            stringBuilder.append("#").append(invokeMethod.method.getName());
            stringBuilder.append("(").append(argString).append(")");
            this.logger.debug(stringBuilder.toString());
        }

        byte responseID = invokeMethod.responseID;
        boolean transmitReturnVal = (responseID & kReturnValMask) == kReturnValMask;
        boolean transmitExceptions = (responseID & kReturnExMask) == kReturnExMask;

        Object result = null;
        Method method = invokeMethod.method;
        try {
            result = method.invoke(target, invokeMethod.args);
            // Catch exceptions caused by the Method#invoke
        } catch (InvocationTargetException ex) {
            if (transmitExceptions) {
                // added to prevent a stack overflow when references is false
                // (because cause = "this").
                // See:
                // https://groups.google.com/forum/?fromgroups=#!topic/kryo-users/6PDs71M1e9Y
                Throwable cause = ex.getCause();
                cause.initCause(null);
                result = cause;
            } else {
                throw new RuntimeException("Error invoking method: " + method.getDeclaringClass().getName() + "."
                        + method.getName(), ex);
            }
        } catch (Exception ex) {
            throw new RuntimeException("Error invoking method: " + method.getDeclaringClass().getName() + "."
                    + method.getName(), ex);
        }

        if (responseID == 0) {
            return;
        }

        InvokeMethodResult invokeMethodResult = new InvokeMethodResult();
        invokeMethodResult.objectID = invokeMethod.objectID;
        invokeMethodResult.responseID = responseID;

        // Do not return non-primitives if transmitReturnVal is false
        if (!transmitReturnVal && !invokeMethod.method.getReturnType().isPrimitive()) {
            invokeMethodResult.result = null;
        } else {
            invokeMethodResult.result = result;
        }

        // System.err.println("Sending: " + invokeMethod.responseID);
        connection.send().TCP(invokeMethodResult).flush();

        // logger.error("{} sent data: {}  with id ({})", connection, result,
        // invokeMethod.responseID);
        // if (invokeMethod.responseID == -52) {
        // System.err.println("ASDASD");
        // }
    }

    /**
     * Identical to {@link #getRemoteObject(C, int, Class...)} except returns
     * the object cast to the specified interface type. The returned object
     * still implements {@link RemoteObject}.
     */

    static public <T, C extends Connection> T getRemoteObject(final C connection, int objectID, Class<T> iface) {
        @SuppressWarnings({"unchecked"})
        T remoteObject = (T) getRemoteObject(connection, objectID, new Class<?>[] {iface});
        return remoteObject;
    }

    /**
     * Returns a proxy object that implements the specified interfaces. Methods
     * invoked on the proxy object will be invoked remotely on the object with
     * the specified ID in the ObjectSpace for the specified connection. If the
     * remote end of the connection has not {@link #addConnection(Connection)
     * added} the connection to the ObjectSpace, the remote method invocations
     * will be ignored.
     * <p>
     * Methods that return a value will throw {@link TimeoutException} if the
     * response is not received with the
     * {@link RemoteObject#setResponseTimeout(int) response timeout}.
     * <p>
     * If {@link RemoteObject#setNonBlocking(boolean) non-blocking} is false
     * (the default), then methods that return a value must not be called from
     * the update thread for the connection. An exception will be thrown if this
     * occurs. Methods with a void return value can be called on the update
     * thread.
     * <p>
     * If a proxy returned from this method is part of an object graph sent over
     * the network, the object graph on the receiving side will have the proxy
     * object replaced with the registered object.
     *
     * @see RemoteObject
     */
    public static RemoteObject getRemoteObject(Connection connection, int objectID, Class<?>... ifaces) {
        if (connection == null) {
            throw new IllegalArgumentException("connection cannot be null.");
        }
        if (ifaces == null) {
            throw new IllegalArgumentException("ifaces cannot be null.");
        }

        Class<?>[] temp = new Class<?>[ifaces.length + 1];
        temp[0] = RemoteObject.class;
        System.arraycopy(ifaces, 0, temp, 1, ifaces.length);

        return (RemoteObject) Proxy.newProxyInstance(RmiBridge.class.getClassLoader(),
                                                     temp,
                                                     new RemoteInvocationHandler(connection, objectID));
    }

    static CachedMethod[] getMethods(Kryo kryo, Class<?> type) {
        CachedMethod[] cachedMethods = methodCache.get(type);
        if (cachedMethods != null) {
            return cachedMethods;
        }

        ArrayList<Method> allMethods = new ArrayList<Method>();

        Class<?> nextClass = type;
        while (nextClass != null && nextClass != Object.class) {
            Collections.addAll(allMethods, nextClass.getDeclaredMethods());
            nextClass = nextClass.getSuperclass();
        }

        PriorityQueue<Method> methods = new PriorityQueue<Method>(Math.max(1, allMethods.size()),
          new Comparator<Method>() {
              @Override
              public int compare(Method o1, Method o2) {
                  // Methods are sorted so they can be represented as an index.
                  int diff = o1.getName().compareTo(o2.getName());
                  if (diff != 0) {
                      return diff;
                  }
                  Class<?>[] argTypes1 = o1.getParameterTypes();
                  Class<?>[] argTypes2 = o2.getParameterTypes();
                  if (argTypes1.length > argTypes2.length) {
                      return 1;
                  }
                  if (argTypes1.length < argTypes2.length) {
                      return -1;
                  }
                  for (int i = 0; i < argTypes1.length; i++) {
                      diff = argTypes1[i].getName().compareTo(argTypes2[i].getName());
                      if (diff != 0) {
                          return diff;
                      }
                  }
                  throw new RuntimeException("Two methods with same signature!"); // Impossible.
              }
          });
        for (int i = 0, n = allMethods.size(); i < n; i++) {
            Method method = allMethods.get(i);
            int modifiers = method.getModifiers();
            if (Modifier.isStatic(modifiers)) {
                continue;
            }
            if (Modifier.isPrivate(modifiers)) {
                continue;
            }
            if (method.isSynthetic()) {
                continue;
            }
            methods.add(method);
        }

        int n = methods.size();
        cachedMethods = new CachedMethod[n];
        for (int i = 0; i < n; i++) {
            CachedMethod cachedMethod = new CachedMethod();
            cachedMethod.method = methods.poll();

            // Store the serializer for each final parameter.
            Class<?>[] parameterTypes = cachedMethod.method.getParameterTypes();
            cachedMethod.serializers = new Serializer<?>[parameterTypes.length];
            for (int ii = 0, nn = parameterTypes.length; ii < nn; ii++) {
                if (kryo.isFinal(parameterTypes[ii])) {
                    cachedMethod.serializers[ii] = kryo.getSerializer(parameterTypes[ii]);
                }
            }

            cachedMethods[i] = cachedMethod;
        }

        methodCache.put(type, cachedMethods);
        return cachedMethods;
    }

    /**
     * Returns the first object registered with the specified ID in any of the
     * ObjectSpaces the specified connection belongs to.
     */
    static Object getRegisteredObject(Connection connection, int objectID) {
        CopyOnWriteArrayList<RmiBridge> instances = RmiBridge.instances;
        for (RmiBridge objectSpace : instances) {
            // Check if the connection is in this ObjectSpace.
            Iterator<Connection> iterator = objectSpace.connections.iterator();
            while (iterator.hasNext()) {
                Connection c = iterator.next();
                if (c != connection) {
                    continue;
                }

                // Find an object with the objectID.
                Object object = objectSpace.idToObject.get(objectID);
                if (object != null) {
                    return object;
                }
            }
        }

        return null;
    }

    /**
     * Returns the first ID registered for the specified object with any of the
     * ObjectSpaces the specified connection belongs to, or Integer.MAX_VALUE
     * if not found.
     */
    public static int getRegisteredId(Connection connection, Object object) {
        CopyOnWriteArrayList<RmiBridge> instances = RmiBridge.instances;
        for (RmiBridge objectSpace : instances) {
            // Check if the connection is in this ObjectSpace.
            Iterator<Connection> iterator = objectSpace.connections.iterator();
            while (iterator.hasNext()) {
                Connection c = iterator.next();
                if (c != connection) {
                    continue;
                }

                // Find an ID with the object.
                int id = objectSpace.objectToID.get(object, Integer.MAX_VALUE);
                if (id != Integer.MAX_VALUE) {
                    return id;
                }
            }
        }

        return Integer.MAX_VALUE;
    }

    /**
     * Registers the classes needed to use ObjectSpaces. This should be called
     * before any connections are opened.
     */
    public static <C extends Connection> void registerClasses(final SerializationManager smanager) {
        smanager.registerForRmiClasses(new RmiRegisterClassesCallback() {
            @Override
            public void registerForClasses(Kryo kryo) {
                kryo.register(Object[].class);
                kryo.register(InvokeMethod.class);

                FieldSerializer<InvokeMethodResult> resultSerializer = new FieldSerializer<InvokeMethodResult>(kryo, InvokeMethodResult.class) {
                    @Override
                    public void write(Kryo kryo, Output output, InvokeMethodResult result) {
                        super.write(kryo, output, result);
                        output.writeInt(result.objectID, true);
                    }

                    @Override
                    public InvokeMethodResult read(Kryo kryo, Input input, Class<InvokeMethodResult> type) {
                        InvokeMethodResult result = super.read(kryo, input, type);
                        result.objectID = input.readInt(true);
                        return result;
                    }
                };
                resultSerializer.removeField(OBJECT_ID);
                kryo.register(InvokeMethodResult.class, resultSerializer);

                kryo.register(InvocationHandler.class, new Serializer<Object>() {
                    @Override
                    public void write(Kryo kryo, Output output, Object object) {
                        RemoteInvocationHandler handler = (RemoteInvocationHandler) Proxy.getInvocationHandler(object);
                        output.writeInt(handler.objectID, true);
                    }

                    @Override
                    @SuppressWarnings({"unchecked"})
                    public Object read(Kryo kryo, Input input, Class<Object> type) {
                        int objectID = input.readInt(true);
                        Connection connection = (Connection) kryo.getContext().get(Connection.connection);
                        Object object = getRegisteredObject(connection, objectID);
                        if (object == null) {
                            final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(RmiBridge.class);
                            logger.warn("Unknown object ID {} for connection: {}", objectID, connection);
                        }
                        return object;
                    }
                });
            }
        });
    }
}
