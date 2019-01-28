/*
 * Copyright 2019 dorkbox, llc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.network.rmi;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.AbstractMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.KryoExtra;
import dorkbox.network.connection.Listener;
import dorkbox.network.connection.Listener.OnMessageReceived;
import dorkbox.network.serialization.NetworkSerializationManager;
import dorkbox.util.collections.LockFreeHashMap;
import dorkbox.util.collections.LockFreeIntMap;
import dorkbox.util.generics.ClassHelper;

public abstract
class ConnectionRmiImplSupport implements ConnectionRmiSupport {
    private final RmiBridge rmiGlobalBridge;
    private final RmiBridge rmiLocalBridge;

    private final Map<Integer, RemoteObject> proxyIdCache;
    private final List<OnMessageReceived<Connection, InvokeMethodResult>> proxyListeners;


    private final LockFreeIntMap<RemoteObjectCallback> rmiRegistrationCallbacks;
    private volatile int rmiCallbackId = 0;

    final ConnectionImpl connection;
    protected final Logger logger;

    ConnectionRmiImplSupport(final ConnectionImpl connection, final RmiBridge rmiGlobalBridge) {
        this.connection = connection;

        if (rmiGlobalBridge == null ) {
            throw new NullPointerException("RMI cannot be null if using RMI support!");
        }

        this.rmiGlobalBridge = rmiGlobalBridge;

        logger = rmiGlobalBridge.logger;

        //     * @param executor
        //      *                 Sets the executor used to invoke methods when an invocation is received from a remote endpoint. By default, no
        //      *                 executor is set and invocations occur on the network thread, which should not be blocked for long, May be null.


        rmiLocalBridge = new RmiBridge(logger, false);


        proxyIdCache = new LockFreeHashMap<Integer, RemoteObject>();
        proxyListeners = new CopyOnWriteArrayList<OnMessageReceived<Connection, InvokeMethodResult>>();
        rmiRegistrationCallbacks = new LockFreeIntMap<RemoteObjectCallback>();
    }

    abstract InvokeMethod getInvokeMethod(final NetworkSerializationManager serialization, final ConnectionImpl connection, final InvokeMethod invokeMethod);

    abstract void registration(final ConnectionImpl connection, final RmiRegistration message);

    public
    void close() {
        // proxy listeners are cleared in the removeAll() call (which happens BEFORE close)
        proxyIdCache.clear();

        rmiRegistrationCallbacks.clear();
    }

    /**
     * This will remove the invoke and invoke response listeners for this remote object
     */
    public
    void removeAllListeners() {
        proxyListeners.clear();
    }

    public
    <Iface> void createRemoteObject(final ConnectionImpl connection, final Class<Iface> interfaceClass, final RemoteObjectCallback<Iface> callback) {
        if (!interfaceClass.isInterface()) {
            throw new IllegalArgumentException("Cannot create a proxy for RMI access. It must be an interface.");
        }

        // because this is PER CONNECTION, there is no need for synchronize(), since there will not be any issues with concurrent
        // access, but there WILL be issues with thread visibility because a different worker thread can be called for different connections
        //noinspection NonAtomicOperationOnVolatileField
        int nextRmiCallbackId = rmiCallbackId++;
        rmiRegistrationCallbacks.put(nextRmiCallbackId, callback);
        RmiRegistration message = new RmiRegistration(interfaceClass, RmiBridge.INVALID_RMI, nextRmiCallbackId);

        // We use a callback to notify us when the object is ready. We can't "create this on the fly" because we
        // have to wait for the object to be created + ID to be assigned on the remote system BEFORE we can create the proxy instance here.

        // this means we are creating a NEW object on the server, bound access to only this connection
        connection.send(message).flush();
    }

    public
    <Iface> void getRemoteObject(final ConnectionImpl connection, final int objectId, final RemoteObjectCallback<Iface> callback) {
        if (objectId < 0) {
            throw new IllegalStateException("Object ID cannot be < 0");
        }
        if (objectId >= RmiBridge.INVALID_RMI) {
            throw new IllegalStateException("Object ID cannot be >= " + RmiBridge.INVALID_RMI);
        }

        Class<?> iFaceClass = ClassHelper.getGenericParameterAsClassForSuperClass(RemoteObjectCallback.class, callback.getClass(), 0);

        // because this is PER CONNECTION, there is no need for synchronize(), since there will not be any issues with concurrent
        // access, but there WILL be issues with thread visibility because a different worker thread can be called for different connections
        //noinspection NonAtomicOperationOnVolatileField
        int nextRmiCallbackId = rmiCallbackId++;
        rmiRegistrationCallbacks.put(nextRmiCallbackId, callback);
        RmiRegistration message = new RmiRegistration(iFaceClass, objectId, nextRmiCallbackId);

        // We use a callback to notify us when the object is ready. We can't "create this on the fly" because we
        // have to wait for the object to be created + ID to be assigned on the remote system BEFORE we can create the proxy instance here.

        // this means we are getting an EXISTING object on the server, bound access to only this connection
        connection.send(message).flush();
    }

    /**
     * Manages the RMI stuff for a connection.
     */
    public
    boolean manage(final ConnectionImpl connection, final Object message) {
        if (message instanceof InvokeMethod) {
            NetworkSerializationManager serialization = connection.getEndPoint().getSerialization();

            InvokeMethod invokeMethod = getInvokeMethod(serialization, connection, (InvokeMethod) message);

            int objectID = invokeMethod.objectID;

            // have to make sure to get the correct object (global vs local)
            // This is what is overridden when registering interfaces/classes for RMI.
            // objectID is the interface ID, and this returns the implementation ID.
            final Object target = getImplementationObject(objectID);

            if (target == null) {
                logger.warn("Ignoring remote invocation request for unknown object ID: {}", objectID);

                return true; // maybe false?
            }

            try {
                InvokeMethodResult result = RmiBridge.invoke(connection, target, invokeMethod, logger);
                if (result != null) {
                    // System.err.println("Sending: " + invokeMethod.responseID);
                    connection.send(result).flush();
                }

            } catch (IOException e) {
                logger.error("Unable to invoke method.", e);
            }

            return true;
        }
        else if (message instanceof InvokeMethodResult) {
            for (Listener.OnMessageReceived<Connection, InvokeMethodResult> proxyListener : proxyListeners) {
                proxyListener.received(connection, (InvokeMethodResult) message);
            }
            return true;
        }
        else if (message instanceof RmiRegistration) {
            registration(connection, (RmiRegistration) message);
            return true;
        }

        // not the correct type
        return false;
    }

    void runCallback(final Class<?> interfaceClass, final int callbackId, final Object remoteObject) {
        RemoteObjectCallback callback = rmiRegistrationCallbacks.remove(callbackId);

        try {
            //noinspection unchecked
            callback.created(remoteObject);
        } catch (Exception e) {
            logger.error("Error getting or creating the remote object " + interfaceClass, e);
        }
    }

    /**
     * Used by RMI by the LOCAL side when setting up the to fetch an object for the REMOTE side
     *
     * @return the registered ID for a specific object, or RmiBridge.INVALID_RMI if there was no ID.
     */
    public
    <T> int getRegisteredId(final T object) {
        // always check global before checking local, because less contention on the synchronization
        int objectId = rmiGlobalBridge.getRegisteredId(object);
        if (objectId != RmiBridge.INVALID_RMI) {
            return objectId;
        }
        else {
            // might return RmiBridge.INVALID_RMI;
            return rmiLocalBridge.getRegisteredId(object);
        }
    }

    /**
     * This is used by RMI for the REMOTE side, to get the implementation
     *
     * @param objectId this is the RMI object ID
     */
    public
    Object getImplementationObject(final int objectId) {
        if (RmiBridge.isGlobal(objectId)) {
            return rmiGlobalBridge.getRegisteredObject(objectId);
        } else {
            return rmiLocalBridge.getRegisteredObject(objectId);
        }
    }

    /**
     * Removes a proxy object from the system
     */
    void removeProxyObject(final RmiProxyHandler rmiProxyHandler) {
        proxyListeners.remove(rmiProxyHandler.getListener());
        proxyIdCache.remove(rmiProxyHandler.rmiObjectId);
    }

    /**
     * For network connections, the interface class kryo ID == implementation class kryo ID, so they switch automatically.
     * For local connections, we have to switch it appropriately in the LocalRmiProxy
     */
    RmiRegistration createNewRmiObject(final NetworkSerializationManager serialization, final Class<?> interfaceClass, final int callbackId) {
        KryoExtra kryo = null;
        Object object = null;
        int rmiId = 0;

        Class<?> implementationClass = serialization.getRmiImpl(interfaceClass);

        try {
            kryo = serialization.takeKryo();

            // because the INTERFACE is what is registered with kryo (not the impl) we have to temporarily permit unregistered classes (which have an ID of -1)
            // so we can cache the instantiator for this class.
            boolean registrationRequired = kryo.isRegistrationRequired();

            kryo.setRegistrationRequired(false);

            // this is what creates a new instance of the impl class, and stores it as an ID.
            object = kryo.newInstance(implementationClass);

            if (registrationRequired) {
                // only if it's different should we call this again.
                kryo.setRegistrationRequired(true);
            }


            rmiId = rmiLocalBridge.register(object);

            if (rmiId == RmiBridge.INVALID_RMI) {
                // this means that there are too many RMI ids (either global or connection specific!)
                object = null;
            }
            else {
                // if we are invalid, skip going over fields that might also be RMI objects, BECAUSE our object will be NULL!

                // the @Rmi annotation allows an RMI object to have fields with objects that are ALSO RMI objects
                LinkedList<Entry<Class<?>, Object>> classesToCheck = new LinkedList<Map.Entry<Class<?>, Object>>();
                classesToCheck.add(new AbstractMap.SimpleEntry<Class<?>, Object>(implementationClass, object));


                Map.Entry<Class<?>, Object> remoteClassObject;
                while (!classesToCheck.isEmpty()) {
                    remoteClassObject = classesToCheck.removeFirst();

                    // we have to check the IMPLEMENTATION for any additional fields that will have proxy information.
                    // we use getDeclaredFields() + walking the object hierarchy, so we get ALL the fields possible (public + private).
                    for (Field field : remoteClassObject.getKey()
                                                        .getDeclaredFields()) {
                        if (field.getAnnotation(Rmi.class) != null) {
                            final Class<?> type = field.getType();

                            if (!type.isInterface()) {
                                // the type must be an interface, otherwise RMI cannot create a proxy object
                                logger.error("Error checking RMI fields for: {}.{} -- It is not an interface!",
                                             remoteClassObject.getKey(),
                                             field.getName());
                                continue;
                            }


                            boolean prev = field.isAccessible();
                            field.setAccessible(true);
                            final Object o;
                            try {
                                o = field.get(remoteClassObject.getValue());

                                rmiLocalBridge.register(o);
                                classesToCheck.add(new AbstractMap.SimpleEntry<Class<?>, Object>(type, o));
                            } catch (IllegalAccessException e) {
                                logger.error("Error checking RMI fields for: {}.{}", remoteClassObject.getKey(), field.getName(), e);
                            } finally {
                                field.setAccessible(prev);
                            }
                        }
                    }


                    // have to check the object hierarchy as well
                    Class<?> superclass = remoteClassObject.getKey()
                                                           .getSuperclass();
                    if (superclass != null && superclass != Object.class) {
                        classesToCheck.add(new AbstractMap.SimpleEntry<Class<?>, Object>(superclass, remoteClassObject.getValue()));
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error registering RMI class " + implementationClass, e);
        } finally {
            if (kryo != null) {
                // we use kryo to create a new instance - so only return it on error or when it's done creating a new instance
                serialization.returnKryo(kryo);
            }
        }

        return new RmiRegistration(interfaceClass, rmiId, callbackId, object);
    }

    /**
     * Warning. This is an advanced method. You should probably be using {@link Connection#createRemoteObject(Class, RemoteObjectCallback)}
     * <p>
     * <p>
     * Returns a proxy object that implements the specified interface, and the methods invoked on the proxy object will be invoked
     * remotely.
     * <p>
     * Methods that return a value will throw {@link TimeoutException} if the response is not received with the {@link
     * RemoteObject#setResponseTimeout(int) response timeout}.
     * <p/>
     * If {@link RemoteObject#setAsync(boolean) non-blocking} is false (the default), then methods that return a value must not be
     * called from the update thread for the connection. An exception will be thrown if this occurs. Methods with a void return value can be
     * called on the update thread.
     * <p/>
     * If a proxy returned from this method is part of an object graph sent over the network, the object graph on the receiving side will
     * have the proxy object replaced with the registered object.
     *
     * @see RemoteObject
     * @param rmiId this is the remote object ID (assigned by RMI). This is NOT the kryo registration ID
     * @param iFace this is the RMI interface
     */
    public
    RemoteObject getProxyObject(final int rmiId, final Class<?> iFace) {
        if (iFace == null) {
            throw new IllegalArgumentException("iface cannot be null.");
        }
        if (!iFace.isInterface()) {
            throw new IllegalArgumentException("iface must be an interface.");
        }

        // we want to have a connection specific cache of IDs
        // because this is PER CONNECTION, there is no need for synchronize(), since there will not be any issues with concurrent
        // access, but there WILL be issues with thread visibility because a different worker thread can be called for different connections
        RemoteObject remoteObject = proxyIdCache.get(rmiId);

        if (remoteObject == null) {
            // duplicates are fine, as they represent the same object (as specified by the ID) on the remote side.

            // the ACTUAL proxy is created in the connection impl.
            RmiProxyHandler proxyObject = new RmiProxyHandler(this.connection, this, rmiId, iFace);
            proxyListeners.add(proxyObject.getListener());

            // This is the interface inheritance by the proxy object
            Class<?>[] temp = new Class<?>[2];
            temp[0] = RemoteObject.class;
            temp[1] = iFace;

            remoteObject = (RemoteObject) Proxy.newProxyInstance(RmiBridge.class.getClassLoader(), temp, proxyObject);

            proxyIdCache.put(rmiId, remoteObject);
        }

        return remoteObject;
    }
}
