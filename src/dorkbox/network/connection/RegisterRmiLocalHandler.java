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
package dorkbox.network.connection;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.util.IdentityMap;

import dorkbox.network.rmi.CachedMethod;
import dorkbox.network.rmi.InvokeMethod;
import dorkbox.network.rmi.RemoteObject;
import dorkbox.network.rmi.RmiBridge;
import dorkbox.network.rmi.RmiMessages;
import dorkbox.network.rmi.RmiProxyHandler;
import dorkbox.network.rmi.RmiRegistration;
import dorkbox.network.serialization.CryptoSerializationManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

/**
 * This is for a local-connection (same-JVM) RMI method invocation
 *
 * Uses the "single writer principle" for fast access, but disregards 'single writer' for field cache, because duplicates are OK
 * <p>
 * This is for a LOCAL connection (same-JVM)
 */
public
class RegisterRmiLocalHandler extends MessageToMessageDecoder<Object> {
    private static final boolean ENABLE_PROXY_OBJECTS = ConnectionImpl.ENABLE_PROXY_OBJECTS;
    private static final Field[] NO_REMOTE_FIELDS = new Field[0];

    // private static final AtomicReferenceFieldUpdater<RegisterRmiLocalHandler, IdentityMap> rmiFieldsREF = AtomicReferenceFieldUpdater.newUpdater(
    //         RegisterRmiLocalHandler.class,
    //         IdentityMap.class,
    //         "fieldCache");

    private static final AtomicReferenceFieldUpdater<RegisterRmiLocalHandler, IdentityMap> implToProxyREF = AtomicReferenceFieldUpdater.newUpdater(
            RegisterRmiLocalHandler.class,
            IdentityMap.class,
            "implToProxy");

    private static final AtomicReferenceFieldUpdater<RegisterRmiLocalHandler, IdentityMap> remoteObjectREF = AtomicReferenceFieldUpdater.newUpdater(
            RegisterRmiLocalHandler.class,
            IdentityMap.class,
            "objectHasRemoteObjects");

    // private volatile IdentityMap<Class<?>, Field[]> fieldCache = new IdentityMap<Class<?>, Field[]>();
    private volatile IdentityMap<Object, Object> implToProxy = new IdentityMap<Object, Object>();
    private volatile IdentityMap<Object, Field[]> objectHasRemoteObjects = new IdentityMap<Object, Field[]>();


    public
    RegisterRmiLocalHandler() {
    }

    @Override
    protected
    void decode(final ChannelHandlerContext context, final Object msg, final List<Object> out) throws Exception {
        ConnectionImpl connection = (ConnectionImpl) context.pipeline()
                                                            .last();

        if (msg instanceof RmiRegistration) {
            receivedRegistration(connection, (RmiRegistration) msg);
        }
        else {
            if (msg instanceof InvokeMethod) {
                InvokeMethod invokeMethod = (InvokeMethod) msg;
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
            }

            receivedNormal(connection, msg, out);
        }
    }

    private
    void receivedNormal(final ConnectionImpl connection, final Object msg, final List<Object> out) {
        // else, this was "just a local message"

        if (msg instanceof RmiMessages) {
            // don't even process these message types
            out.add(msg);
            return;
        }

        // because we NORMALLY pass around just the object (there is no serialization going on...) we have to explicitly check to see
        // if this object, or any of it's fields MIGHT HAVE BEEN an RMI Proxy (or should be on), and switcheroo it here.
        // NORMALLY this is automatic since the kryo IDs on each side point to the "correct object" for serialization, but here we don't do that.

        // maybe this object is supposed to switch to a proxy object?? (note: we cannot send proxy objects over local/network connections)

        @SuppressWarnings("unchecked")
        IdentityMap<Object, Object> implToProxy = implToProxyREF.get(this);
        IdentityMap<Object, Field[]> objectHasRemoteObjects = remoteObjectREF.get(this);


        Object proxy = implToProxy.get(msg);
        if (proxy != null) {
            // we have a proxy object. nothing left to do.
            out.add(proxy);
            return;
        }


        Class<?> messageClass = msg.getClass();

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
                            o = field.get(msg);

                            if (o instanceof RemoteObject) {
                                RmiProxyHandler handler = (RmiProxyHandler) Proxy.getInvocationHandler(o);

                                int id = handler.objectID;
                                field.set(msg, connection.getImplementationObject(id));
                                fields.add(field);
                            }
                            else {
                                // is a field supposed to be a proxy?
                                proxy = implToProxy.get(o);
                                if (proxy != null) {
                                    field.set(msg, proxy);
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
            synchronized (objectHasRemoteObjects) {
                // i know what I'm doing. This must be synchronized.
                objectHasRemoteObjects.put(messageClass, array);
            }
        }
        else if (remoteObjectFields != NO_REMOTE_FIELDS) {
            // quickly replace objects as necessary

            for (Field field : remoteObjectFields) {
                boolean prev = field.isAccessible();
                final Object o;
                try {
                    field.setAccessible(true);
                    o = field.get(msg);

                    if (o instanceof RemoteObject) {
                        RmiProxyHandler handler = (RmiProxyHandler) Proxy.getInvocationHandler(o);

                        int id = handler.objectID;
                        field.set(msg, connection.getImplementationObject(id));
                    }
                    else {
                        // is a field supposed to be a proxy?
                        proxy = implToProxy.get(o);
                        if (proxy != null) {
                            field.set(msg, proxy);
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

        out.add(msg);
    }


    private
    void receivedRegistration(final ConnectionImpl connection, final RmiRegistration registration) {
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
                EndPointBase<Connection> endPoint = connection.getEndPoint();
                CryptoSerializationManager serialization = endPoint.getSerialization();

                Class<?> rmiImpl = serialization.getRmiImpl(registration.interfaceClass);

                RmiRegistration registrationResult = connection.createNewRmiObject(interfaceClass, rmiImpl, callbackId);
                connection.TCP(registrationResult)
                          .flush();
            }

            // Check if we are getting an already existing REMOTE object. This check is always AFTER the check to create a new object
            else {
                // THIS IS ON THE REMOTE CONNECTION (where the object implementation will really exist)
                //
                // GET a LOCAL rmi object, if none get a specific, GLOBAL rmi object (objects that are not bound to a single connection).
                RmiRegistration registrationResult = connection.getExistingRmiObject(interfaceClass, registration.rmiId, callbackId);

                connection.TCP(registrationResult)
                          .flush();
            }
        }
        else {
            // this is the response.
            // THIS IS ON THE LOCAL CONNECTION SIDE, which is the side that called 'getRemoteObject()'   This can be Server or Client.


            // on "local" connections (as opposed to "network" connections), the objects ARE NOT serialized, so we never
            // generate a proxy via the rmiID - so the LocalRmiProxy (in this use case) overwrites the remoteObject with a proxy object
            // if we are the response, we want to create a proxy object instead of just passing the ACTUAL object.
            // On the "network" (RemoteObjectSerializer.java) stack, this process is automatic -- and here we have to mimic this behavior.

            // has to be 'registration.remoteObject' because we use it later on
            if (ENABLE_PROXY_OBJECTS && registration.remoteObject != null) {
                // override the implementation object with the proxy. This is required because RMI must be the same between "network" and "local"
                // connections -- even if this "slows down" the speed/performance of what "local" connections offer.
                RemoteObject proxyObject = connection.getProxyObject(registration.rmiId, interfaceClass);

                // have to save A and B so we can correctly switch as necessary
                //noinspection SynchronizeOnNonFinalField
                synchronized (implToProxy) {
                    // i know what I'm doing. This must be synchronized.
                    implToProxy.put(registration.remoteObject, proxyObject);
                }

                connection.runRmiCallback(interfaceClass, callbackId, proxyObject);
            }
            else {
                connection.runRmiCallback(interfaceClass, callbackId, registration.remoteObject);
            }
        }
    }

    // private
    // LocalRmiClassEncoder replaceFieldObjects(final ConnectionImpl connection, final Object object, final Class<?> implClass) {
    //     Field[] rmiFields = fieldCache.get(implClass);
    //     int length = rmiFields.length;
    //
    //     Object rmiObject = null;
    //     int[] rmiFieldIds = new int[length];
    //     for (int i = 0; i < length; i++) {
    //         Field field = rmiFields[i];
    //
    //         // if it's an RMI object we want to write out a proxy object in the field instead of the actual object
    //         try {
    //             rmiObject = field.get(object);
    //         } catch (IllegalAccessException e) {
    //             e.printStackTrace();
    //         }
    //
    //         if (rmiObject == null) {
    //             rmiFieldIds[i] = 0; // 0 means it was null
    //         }
    //
    //         final Map<Object, Integer> localWeakCache = objectThreadLocals.get();
    //
    //         Integer id = localWeakCache.get(rmiObject);
    //         if (id == null) {
    //             // duplicates are fine, as they represent the same object (as specified by the ID) on the remote side.
    //             int registeredId = connection.getRegisteredId(rmiObject);
    //             rmiFieldIds[i] = registeredId;
    //             localWeakCache.put(rmiObject, registeredId);
    //         }
    //         else {
    //             rmiFieldIds[i] = id;
    //         }
    //     }
    //
    //     LocalRmiClassEncoder localRmiClassEncoder = new LocalRmiClassEncoder();
    //     localRmiClassEncoder.rmiObject = object;
    //     localRmiClassEncoder.rmiFieldIds = rmiFieldIds;
    //
    //     return localRmiClassEncoder;
    // }

    // Field[] getFields(final Class<?> clazz) {
    //     // duplicates are OK, because they will contain the same information, so we DO NOT care about single writers
    //
    //     //noinspection unchecked
    //     final IdentityMap<Class<?>, Field[]> identityMap = rmiFieldsREF.get(this);
    //
    //
    //     Field[] rmiFields = identityMap.get(clazz);
    //     if (rmiFields != null) {
    //         return rmiFields;
    //     }
    //
    //     final ArrayList<Field> fields = new ArrayList<Field>();
    //
    //     for (Field field : clazz.getDeclaredFields()) {
    //         if (field.getAnnotation(Rmi.class) != null) {
    //             fields.add(field);
    //         }
    //     }
    //
    //
    //     rmiFields = new Field[fields.size()];
    //     fields.toArray(rmiFields);
    //
    //     // save in cache
    //     fieldCache.put(clazz, rmiFields);
    //     rmiFieldsREF.lazySet(this, fieldCache);
    //
    //     return rmiFields;
    // }
}
