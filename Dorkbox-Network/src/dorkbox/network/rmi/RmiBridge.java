package dorkbox.network.rmi;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

import org.slf4j.Logger;

import com.esotericsoftware.kryo.util.IntMap;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.ListenerRaw;
import dorkbox.network.util.exceptions.NetException;
import dorkbox.util.collections.ObjectIntMap;
import dorkbox.util.objectPool.ObjectPool;
import dorkbox.util.objectPool.ObjectPoolFactory;

/**
 * Allows methods on objects to be invoked remotely over TCP, UDP, or UDT. Objects are
 * {@link #register(int, Object)} registered with an ID, and endpoint connections
 * can then {@link Connection#getRemoteObject(int, Class)} for registered
 * objects.
 * <p/>
 * It costs at least 2 bytes more to use remote method invocation than just
 * sending the parameters. If the method has a return value which is not
 * {@link RemoteObject#setNonBlocking(boolean) ignored}, an extra byte is
 * written. If the type of a parameter is not final (note primitives are final)
 * then an extra byte is written for that parameter.
 *
 * @author Nathan Sweet <misc@n4te.com>, Nathan Robinson
 */
public
class RmiBridge  {
    static final int returnValueMask = 1 << 7;
    static final int returnExceptionMask = 1 << 6;
    static final int responseIdMask = 0xFF & ~returnValueMask & ~returnExceptionMask;


    // the name of who created this RmiBridge
    private final org.slf4j.Logger logger;

    // can be accessed by DIFFERENT threads.
    private final ReentrantReadWriteLock objectLock = new ReentrantReadWriteLock();
    private final IntMap<Object> idToObject = new IntMap<Object>();
    private final ObjectIntMap<Object> objectToID = new ObjectIntMap<Object>();

    private final Executor executor;

    // 4096 concurrent method invocations max
    private final ObjectPool<InvokeMethod> invokeMethodPool = ObjectPoolFactory.create(new InvokeMethodPoolable(), 4096);

    private final ListenerRaw<Connection, InvokeMethod> invokeListener = new ListenerRaw<Connection, InvokeMethod>() {
        @Override
        public
        void received(final Connection connection, final InvokeMethod invokeMethod) {
            ReadLock readLock = RmiBridge.this.objectLock.readLock();
            readLock.lock();

            final Object target = RmiBridge.this.idToObject.get(invokeMethod.objectID);

            readLock.unlock();


            if (target == null) {
                Logger logger2 = RmiBridge.this.logger;
                if (logger2.isWarnEnabled()) {
                    logger2.warn("Ignoring remote invocation request for unknown object ID: {}", invokeMethod.objectID);
                }

                return;
            }

            Executor executor2 = RmiBridge.this.executor;
            if (executor2 == null) {
                invoke(connection, target, invokeMethod);
            }
            else {
                executor2.execute(new Runnable() {
                    @Override
                    public
                    void run() {
                        invoke(connection, target, invokeMethod);
                    }
                });
            }
        }
    };

    /**
     * Creates an RmiBridge with no connections. Connections must be
     * {@link RmiBridge#register(int, Object)} added to allow the remote end of
     * the connections to access objects in this ObjectSpace.
     *
     * @param executor Sets the executor used to invoke methods when an invocation is received
     *                 from a remote endpoint. By default, no executor is set and invocations
     *                 occur on the network thread, which should not be blocked for long, May be null.
     */
    public
    RmiBridge(final org.slf4j.Logger logger, final Executor executor) {
        this.logger = logger;
        this.executor = executor;
    }

    /**
     * @return the invocation listener
     */
    @SuppressWarnings("rawtypes")
    public
    ListenerRaw getListener() {
        return this.invokeListener;
    }

    /**
     * Invokes the method on the object and, if necessary, sends the result back
     * to the connection that made the invocation request. This method is
     * invoked on the update thread of the {@link EndPoint} for this RmiBridge
     * and unless an executor has been set.
     *
     * @param connection The remote side of this connection requested the invocation.
     */
    protected
    void invoke(Connection connection, Object target, InvokeMethod invokeMethod) {
        Logger logger2 = this.logger;
        if (logger2.isDebugEnabled()) {
            String argString = "";
            if (invokeMethod.args != null) {
                argString = Arrays.deepToString(invokeMethod.args);
                argString = argString.substring(1, argString.length() - 1);
            }

            StringBuilder stringBuilder = new StringBuilder(128);
            stringBuilder.append(connection.toString())
                         .append(" received: ")
                         .append(target.getClass()
                                       .getSimpleName());
            stringBuilder.append(":")
                         .append(invokeMethod.objectID);
            stringBuilder.append("#")
                         .append(invokeMethod.cachedMethod.method.getName());
            stringBuilder.append("(")
                         .append(argString)
                         .append(")");
            logger2.debug(stringBuilder.toString());
        }


        byte responseData = invokeMethod.responseData;
        boolean transmitReturnVal = (responseData & returnValueMask) == returnValueMask;
        boolean transmitExceptions = (responseData & returnExceptionMask) == returnExceptionMask;
        int responseID = responseData & responseIdMask;

        Object result;
        CachedMethod cachedMethod = invokeMethod.cachedMethod;

        try {
            result = cachedMethod.invoke(target, invokeMethod.args);
        } catch (Exception ex) {
            if (transmitExceptions) {
                Throwable cause = ex.getCause();
                // added to prevent a stack overflow when references is false
                // (because cause = "this").
                // See:
                // https://groups.google.com/forum/?fromgroups=#!topic/kryo-users/6PDs71M1e9Y
                if (cause == null) {
                    cause = ex;
                }
                else {
                    cause.initCause(null);
                }
                result = cause;
            }
            else {
                throw new NetException("Error invoking method: " + cachedMethod.method.getDeclaringClass()
                                                                                      .getName() + "." + cachedMethod.method.getName(), ex);
            }
        }

        if (responseID == 0) {
            return;
        }

        InvokeMethodResult invokeMethodResult = new InvokeMethodResult();
        invokeMethodResult.objectID = invokeMethod.objectID;
        invokeMethodResult.responseID = (byte) responseID;


        // Do not return non-primitives if transmitReturnVal is false
        if (!transmitReturnVal && !invokeMethod.cachedMethod.method.getReturnType()
                                                                   .isPrimitive()) {
            invokeMethodResult.result = null;
        }
        else {
            invokeMethodResult.result = result;
        }

        // System.err.println("Sending: " + invokeMethod.responseID);
        connection.send()
                  .TCP(invokeMethodResult)
                  .flush();

        // logger.error("{} sent data: {}  with id ({})", connection, result, invokeMethod.responseID);
    }

    /**
     * Registers an object to allow the remote end of the RmiBridge connections to access it using the specified ID.
     *
     * @param objectID Must not be Integer.MAX_VALUE.
     *
     * @return true if the new ID has not been previously registered. False if the ID was already registered
     */
    public
    void register(int objectID, Object object) {
        if (objectID == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("objectID cannot be Integer.MAX_VALUE.");
        }
        if (object == null) {
            throw new IllegalArgumentException("object cannot be null.");
        }

        WriteLock writeLock = RmiBridge.this.objectLock.writeLock();
        writeLock.lock();

        this.idToObject.put(objectID, object);
        this.objectToID.put(object, objectID);

        writeLock.unlock();

        Logger logger2 = this.logger;
        if (logger2.isTraceEnabled()) {
            logger2.trace("Object registered with ObjectSpace as {}:{}", objectID, object);
        }
        logger2.info("Object registered with ObjectSpace as {}:{}", objectID, object);
    }

    /**
     * Removes an object. The remote end of the RmiBridge connection will no longer be able to access it.
     */
    public
    void remove(int objectID) {
        WriteLock writeLock = RmiBridge.this.objectLock.writeLock();
        writeLock.lock();

        Object object = this.idToObject.remove(objectID);
        if (object != null) {
            this.objectToID.remove(object, 0);
        }

        writeLock.unlock();

        Logger logger2 = this.logger;
        if (logger2.isTraceEnabled()) {
            logger2.trace("Object {} removed from ObjectSpace: {}", objectID, object);
        }
    }

    /**
     * Removes an object. The remote end of the RmiBridge connection will no longer be able to access it.
     */
    public
    void remove(Object object) {
        WriteLock writeLock = RmiBridge.this.objectLock.writeLock();
        writeLock.lock();

        if (!this.idToObject.containsValue(object, true)) {
            writeLock.unlock();
            return;
        }

        int objectID = this.idToObject.findKey(object, true, -1);
        this.idToObject.remove(objectID);
        this.objectToID.remove(object, 0);

        writeLock.unlock();

        Logger logger2 = this.logger;
        if (logger2.isTraceEnabled()) {
            logger2.trace("Object {} removed from ObjectSpace: {}", objectID, object);
        }
    }

    /**
     * Returns a proxy object that implements the specified interfaces. Methods
     * invoked on the proxy object will be invoked remotely on the object with
     * the specified ID in the ObjectSpace for the specified connection. If the
     * remote end of the connection has not {@link RmiBridge#register(int, Object)}
     * added the connection to the ObjectSpace, the remote method invocations
     * will be ignored.
     * <p/>
     * Methods that return a value will throw {@link TimeoutException} if the
     * response is not received with the
     * {@link RemoteObject#setResponseTimeout(int) response timeout}.
     * <p/>
     * If {@link RemoteObject#setNonBlocking(boolean) non-blocking} is false
     * (the default), then methods that return a value must not be called from
     * the update thread for the connection. An exception will be thrown if this
     * occurs. Methods with a void return value can be called on the update
     * thread.
     * <p/>
     * If a proxy returned from this method is part of an object graph sent over
     * the network, the object graph on the receiving side will have the proxy
     * object replaced with the registered object.
     *
     * @see RemoteObject
     */
    public
    RemoteObject getRemoteObject(Connection connection, int objectID, Class<?> iface) {
        if (connection == null) {
            throw new IllegalArgumentException("connection cannot be null.");
        }
        if (iface == null) {
            throw new IllegalArgumentException("iface cannot be null.");
        }

        Class<?>[] temp = new Class<?>[2];
        temp[0] = RemoteObject.class;
        temp[1] = iface;

        return (RemoteObject) Proxy.newProxyInstance(RmiBridge.class.getClassLoader(),
                                                     temp,
                                                     new RemoteInvocationHandler(this.invokeMethodPool, connection, objectID));
    }



    /**
     * Returns the object registered with the specified ID.
     */
    public
    Object getRegisteredObject(final int objectID) {
        ReadLock readLock = this.objectLock.readLock();
        readLock.lock();

        // Find an object with the objectID.
        Object object = this.idToObject.get(objectID);
        readLock.unlock();

        return object;
    }

    /**
     * Returns the ID registered for the specified object, or Integer.MAX_VALUE if not found.
     */
    public
    int getRegisteredId(final Object object) {
        // Find an ID with the object.
        ReadLock readLock = this.objectLock.readLock();

        readLock.lock();
        int id = this.objectToID.get(object, Integer.MAX_VALUE);
        readLock.unlock();

        return id;
    }
}
