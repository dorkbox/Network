/*
 * Copyright 2018 dorkbox, llc.
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
package dorkbox.network.rmi;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.slf4j.Logger;

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.util.IdentityMap;

import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.KryoExtra;
import dorkbox.network.connection.Listener;
import dorkbox.network.serialization.CryptoSerializationManager;

/**
 * This is for a local-connection (same-JVM) RMI method invocation
 *
 * Uses the "single writer principle" for fast access, but disregards 'single writer' for field cache, because duplicates are OK
 * <p>
 * This is for a LOCAL connection (same-JVM)
 */
public
class RmiObjectLocalHandler extends RmiObjectHandler {
    private static final boolean ENABLE_PROXY_OBJECTS = RmiBridge.ENABLE_PROXY_OBJECTS;
    private static final Field[] NO_REMOTE_FIELDS = new Field[0];

    private static final AtomicReferenceFieldUpdater<RmiObjectLocalHandler, IdentityMap> implToProxyREF = AtomicReferenceFieldUpdater.newUpdater(
            RmiObjectLocalHandler.class,
            IdentityMap.class,
            "implToProxy");

    private static final AtomicReferenceFieldUpdater<RmiObjectLocalHandler, IdentityMap> remoteObjectREF = AtomicReferenceFieldUpdater.newUpdater(
            RmiObjectLocalHandler.class,
            IdentityMap.class,
            "objectHasRemoteObjects");

    private volatile IdentityMap<Object, Object> implToProxy = new IdentityMap<Object, Object>();
    private volatile IdentityMap<Object, Field[]> objectHasRemoteObjects = new IdentityMap<Object, Field[]>();
    private final Logger logger;


    public
    RmiObjectLocalHandler(final Logger logger) {
        this.logger = logger;
    }

    @Override
    public
    void invoke(final ConnectionImpl connection, final InvokeMethod invokeMethod, final Listener.OnMessageReceived<ConnectionImpl, InvokeMethod> rmiInvokeListener) {
        int methodClassID = invokeMethod.cachedMethod.methodClassID;
        int methodIndex = invokeMethod.cachedMethod.methodIndex;
        // have to replace the cached methods with the correct (remote) version, otherwise the wrong methods CAN BE invoked.

        CryptoSerializationManager serialization = connection.getEndPoint()
                                                             .getSerialization();


        CachedMethod cachedMethod;
        try {
            cachedMethod = serialization.getMethods(methodClassID)[methodIndex];
        } catch (Exception ex) {
            String errorMessage;
            KryoExtra kryo = null;
            try {
                kryo = serialization.takeKryo();

                Class<?> methodClass = kryo.getRegistration(methodClassID)
                                           .getType();

                errorMessage = "Invalid method index " + methodIndex + " for class: " + methodClass.getName();
            } finally {
                serialization.returnKryo(kryo);
            }

            throw new KryoException(errorMessage);
        }


        Object[] args;
        Serializer<?>[] serializers = cachedMethod.serializers;

        int argStartIndex;

        if (cachedMethod.overriddenMethod) {
            // did we override our cached method? This is not common.
            // this is specifically when we override an interface method, with an implementation method + Connection parameter (@ index 0)
            argStartIndex = 1;

            args = new Object[serializers.length + 1];
            args[0] = connection;
        }
        else {
            argStartIndex = 0;
            args = new Object[serializers.length];
        }

        for (int i = 0, n = serializers.length, j = argStartIndex; i < n; i++, j++) {
            args[j] = invokeMethod.args[i];
        }

        // overwrite the invoke method fields with UPDATED versions that have the correct (remote side) implementation/args
        invokeMethod.cachedMethod = cachedMethod;
        invokeMethod.args = args;

        // default action, now that we have swapped out things
        rmiInvokeListener.received(connection, invokeMethod);
    }

    @Override
    public
    void registration(final ConnectionImpl connection, final RmiRegistration registration) {
        // manage creating/getting/notifying this RMI object

        // these fields are ALWAYS present!
        final Class<?> interfaceClass = registration.interfaceClass;
        final int callbackId = registration.callbackId;
        if (registration.isRequest) {
            // Check if we are creating a new REMOTE object. This check is always first.
            if (registration.rmiId == RmiBridge.INVALID_RMI) {
                // THIS IS ON THE REMOTE CONNECTION (where the object will really exist as an implementation)
                //
                // CREATE a new ID, and register the ID and new object (must create a new one) in the object maps


                // have to convert the iFace -> Impl
                EndPoint endPoint = connection.getEndPoint();
                CryptoSerializationManager serialization = endPoint.getSerialization();

                Class<?> rmiImpl = serialization.getRmiImpl(registration.interfaceClass);

                RmiRegistration registrationResult = connection.createNewRmiObject(interfaceClass, rmiImpl, callbackId);
                connection.TCP(registrationResult);
            }

            // Check if we are getting an already existing REMOTE object. This check is always AFTER the check to create a new object
            else {
                // THIS IS ON THE REMOTE CONNECTION (where the object implementation will really exist)
                //
                // GET a LOCAL rmi object, if none get a specific, GLOBAL rmi object (objects that are not bound to a single connection).
                RmiRegistration registrationResult = connection.getExistingRmiObject(interfaceClass, registration.rmiId, callbackId);

                connection.TCP(registrationResult);
            }
        }
        else {
            // this is the response.
            // THIS IS ON THE LOCAL CONNECTION SIDE, which is the side that called 'getRemoteObject()'   This can be Server or Client.


            // on "local" connections (as opposed to "network" connections), the objects ARE NOT serialized, so we never
            // generate a proxy via the rmiID - so the LocalRmiProxy (in this use case) overwrites the remoteObject with a proxy object
            // if we are the response, we want to create a proxy object instead of just passing the ACTUAL object.
            // On the "network" (RemoteObjectSerializer.java) stack, this process is automatic -- and here we have to mimic this behavior.


            // if PROXY objects are enabled, we replace the IMPLEMENTATION with a proxy object, so that the network logic == local logic.
            if (ENABLE_PROXY_OBJECTS) {
                RemoteObject proxyObject = null;

                if (registration.rmiId == RmiBridge.INVALID_RMI) {
                    logger.error("RMI ID '{}' is invalid. Unable to create RMI object.", registration.rmiId);
                }
                else {
                    // override the implementation object with the proxy. This is required because RMI must be the same between "network" and "local"
                    // connections -- even if this "slows down" the speed/performance of what "local" connections offer.
                    proxyObject = connection.getProxyObject(registration.rmiId, interfaceClass);

                    if (proxyObject != null) {
                        // have to save A and B so we can correctly switch as necessary
                        //noinspection SynchronizeOnNonFinalField
                        synchronized (implToProxy) {
                            // i know what I'm doing. This must be synchronized.
                            implToProxy.put(registration.remoteObject, proxyObject);
                        }
                    }
                }

                connection.runRmiCallback(interfaceClass, callbackId, proxyObject);
            }
            else {
                connection.runRmiCallback(interfaceClass, callbackId, registration.remoteObject);
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public
    Object normalMessages(final ConnectionImpl connection, final Object message) {
        // else, this was "just a local message"

        // because we NORMALLY pass around just the object (there is no serialization going on...) we have to explicitly check to see
        // if this object, or any of it's fields MIGHT HAVE BEEN an RMI Proxy (or should be on), and switcheroo it here.
        // NORMALLY this is automatic since the kryo IDs on each side point to the "correct object" for serialization, but here we don't do that.

        // maybe this object is supposed to switch to a proxy object?? (note: we cannot send proxy objects over local/network connections)

        IdentityMap<Object, Object> implToProxy = implToProxyREF.get(this);
        IdentityMap<Object, Field[]> objectHasRemoteObjects = remoteObjectREF.get(this);


        Object proxy = implToProxy.get(message);
        if (proxy != null) {
            // we have a proxy object. nothing left to do.
            return proxy;
        }


        // otherwise we MIGHT have to modify the fields in the object...


        Class<?> messageClass = message.getClass();

        // are there any fields of this message class that COULD contain remote object fields? (NOTE: not RMI fields yet...)
        final Field[] remoteObjectFields = objectHasRemoteObjects.get(messageClass);
        if (remoteObjectFields == null) {
            // maybe one of it's fields is a proxy object?

            // we cache the fields that have to be replaced, so subsequent invocations are significantly more preformat
            final ArrayList<Field> fields = new ArrayList<Field>();

            // we have to walk the hierarchy of this object to check ALL fields, public and private, using getDeclaredFields()
            while (messageClass != Object.class) {
                // this will get ALL fields that are
                for (Field field : messageClass.getDeclaredFields()) {
                    final Class<?> type = field.getType();

                    if (type.isInterface()) {
                        boolean prev = field.isAccessible();
                        final Object o;
                        try {
                            field.setAccessible(true);
                            o = field.get(message);

                            if (o instanceof RemoteObject) {
                                RmiProxyHandler handler = (RmiProxyHandler) Proxy.getInvocationHandler(o);

                                int id = handler.objectID;
                                field.set(message, connection.getImplementationObject(id));
                                fields.add(field);
                            }
                            else {
                                // is a field supposed to be a proxy?
                                proxy = implToProxy.get(o);
                                if (proxy != null) {
                                    field.set(message, proxy);
                                    fields.add(field);
                                }
                            }

                        } catch (IllegalAccessException e) {
                            e.printStackTrace();
                            // logger.error("Error checking RMI fields for: {}.{}", remoteClassObject.getKey(), field.getName(), e);
                        } finally {
                            field.setAccessible(prev);
                        }
                    }
                }

                messageClass = messageClass.getSuperclass();
            }

            Field[] array;
            if (fields.isEmpty()) {
                // no need to ever process this class again.
                array = NO_REMOTE_FIELDS;
            }
            else {
                array = fields.toArray(new Field[fields.size()]);
            }

            //noinspection SynchronizeOnNonFinalField
            synchronized (this.objectHasRemoteObjects) {
                // i know what I'm doing. This must be synchronized.
                this.objectHasRemoteObjects.put(messageClass, array);
            }
        }
        else if (remoteObjectFields != NO_REMOTE_FIELDS) {
            // quickly replace objects as necessary

            for (Field field : remoteObjectFields) {
                boolean prev = field.isAccessible();
                final Object o;
                try {
                    field.setAccessible(true);
                    o = field.get(message);

                    if (o instanceof RemoteObject) {
                        RmiProxyHandler handler = (RmiProxyHandler) Proxy.getInvocationHandler(o);

                        int id = handler.objectID;
                        field.set(message, connection.getImplementationObject(id));
                    }
                    else {
                        // is a field supposed to be a proxy?
                        proxy = implToProxy.get(o);
                        if (proxy != null) {
                            field.set(message, proxy);
                        }
                    }

                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                    // logger.error("Error checking RMI fields for: {}.{}", remoteClassObject.getKey(), field.getName(), e);
                } finally {
                    field.setAccessible(prev);
                }
            }
        }

        return message;
    }
}
