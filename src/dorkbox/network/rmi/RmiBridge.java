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
 *
 * Copyright (c) 2008, Nathan Sweet
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * - Neither the name of Esoteric Software nor the names of its contributors may be used to endorse or promote products derived
 * from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package dorkbox.network.rmi;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.Listener;
import dorkbox.network.serialization.RmiSerializationManager;
import dorkbox.util.Property;
import dorkbox.util.collections.LockFreeIntBiMap;

/**
 * Allows methods on objects to be invoked remotely over TCP, UDP, or LOCAL. Local connections ignore TCP/UDP requests, and perform
 * object transformation (because there is no serialization occurring) using a series of weak hashmaps.
 * <p/>
 * <p/>
 * Objects are {@link RmiSerializationManager#registerRmiInterface(Class)}, and endpoint connections can then {@link
 * Connection#createRemoteObject(Class, RemoteObjectCallback)} for the registered objects.
 * <p/>
 * It costs at least 2 bytes more to use remote method invocation than just sending the parameters. If the method has a return value which
 * is not {@link RemoteObject#setAsync(boolean) ignored}, an extra byte is written. If the type of a parameter is not final (note that
 * primitives are final) then an extra byte is written for that parameter.
 * <p/>
 * <p/>
 * In situations where we want to pass in the Connection (to an RMI method), we have to be able to override method A, with method B.
 * This is to support calling RMI methods from an interface (that does pass the connection reference) to
 * an implType, that DOES pass the connection reference. The remote side (that initiates the RMI calls), MUST use
 * the interface, and the implType may override the method, so that we add the connection as the first in
 * the list of parameters.
 * <p/>
 * for example:
 * Interface: foo(String x)
 *      Impl: foo(Connection c, String x)
 * <p/>
 * The implType (if it exists, with the same name, and with the same signature + connection parameter) will be called from the interface
 * instead of the method that would NORMALLY be called.
 * <p/>
 *
 * @author Nathan Sweet <misc@n4te.com>, Nathan Robinson
 */
public final
class RmiBridge {
    /**
     * Permits local (in-jvm) connections to bypass creating RMI proxy objects (which is CPU + Memory intensive) in favor of just using the
     * object directly. While doing so is considerably faster, it comes at the expense that RMI objects lose the {@link RemoteObject}
     * functions.
     * <p>
     * This will also break logic in a way that "network connection" RMI logic *CAN BE* incompatible.
     * <p>
     * Default is true
     */
    @Property
    public static boolean ENABLE_PROXY_OBJECTS = true;

    public static final int INVALID_RMI = Integer.MAX_VALUE;

    static final int returnValueMask = 1 << 7;
    static final int returnExceptionMask = 1 << 6;
    static final int responseIdMask = 0xFF & ~returnValueMask & ~returnExceptionMask;

    private static final int INVALID_MAP_ID = -1;

    /**
     * @return true if the objectId is global for all connections (even). false if it's connection-only (odd)
     */
    public static
    boolean isGlobal(final int objectId) {
        // global RMI objects -> EVEN in range 0 - (MAX_VALUE-1)
        // connection local RMI -> ODD in range 1 - (MAX_VALUE-1)
        return (objectId & 1) == 0;
    }

    // the name of who created this RmiBridge
    private final org.slf4j.Logger logger;

    private final Executor executor;


    // we start at 1, because 0 (INVALID_RMI) means we access connection only objects
    private final AtomicInteger rmiObjectIdCounter;

    // this is the ID -> Object RMI map. The RMI ID is used (not the kryo ID)
    private final LockFreeIntBiMap<Object> objectMap = new LockFreeIntBiMap<Object>(INVALID_MAP_ID);

    private final Listener.OnMessageReceived<ConnectionImpl, InvokeMethod> invokeListener = new Listener.OnMessageReceived<ConnectionImpl, InvokeMethod>() {
        @SuppressWarnings("AutoBoxing")
        @Override
        public
        void received(final ConnectionImpl connection, final InvokeMethod invokeMethod) {
            int objectID = invokeMethod.objectID;

            // have to make sure to get the correct object (global vs local)
            // This is what is overridden when registering interfaces/classes for RMI.
            // objectID is the interface ID, and this returns the implementation ID.
            final Object target = connection.getImplementationObject(objectID);

            if (target == null) {
                Logger logger2 = RmiBridge.this.logger;
                if (logger2.isWarnEnabled()) {
                    logger2.warn("Ignoring remote invocation request for unknown object ID: {}", objectID);
                }

                return;
            }

            Executor executor2 = RmiBridge.this.executor;
            if (executor2 == null) {
                try {
                    invoke(connection, target, invokeMethod);
                } catch (IOException e) {
                    logger.error("Unable to invoke method.", e);
                }
            }
            else {
                executor2.execute(new Runnable() {
                    @Override
                    public
                    void run() {
                        try {
                            invoke(connection, target, invokeMethod);
                        } catch (IOException e) {
                            logger.error("Unable to invoke method.", e);
                        }
                    }
                });
            }
        }
    };

    /**
     * Creates an RmiBridge with no connections. Connections must be {@link RmiBridge#register(int, Object)} added to allow the remote end
     * of the connections to access objects in this ObjectSpace.
     *
     * @param executor
     *                 Sets the executor used to invoke methods when an invocation is received from a remote endpoint. By default, no
     *                 executor is set and invocations occur on the network thread, which should not be blocked for long, May be null.
     * @param isGlobal
     *                 specify if this RmiBridge is a "global" bridge, meaning connections will prefer objects from this bridge instead of
     *                 the connection-local bridge.
     */
    public
    RmiBridge(final org.slf4j.Logger logger, final Executor executor, final boolean isGlobal) {
        this.logger = logger;
        this.executor = executor;

        if (isGlobal) {
            rmiObjectIdCounter = new AtomicInteger(0);
        }
        else {
            rmiObjectIdCounter = new AtomicInteger(1);
        }
    }

    /**
     * @return the invocation listener
     */
    @SuppressWarnings("rawtypes")
    public
    Listener.OnMessageReceived<ConnectionImpl, InvokeMethod> getListener() {
        return this.invokeListener;
    }

    /**
     * Invokes the method on the object and, if necessary, sends the result back to the connection that made the invocation request. This
     * method is invoked on the update thread of the {@link EndPoint} for this RmiBridge and unless an executor has been set.
     *
     * This is the response to the invoke method in the RmiProxyHandler
     *
     * @param connection
     *                 The remote side of this connection requested the invocation.
     */
    @SuppressWarnings("NumericCastThatLosesPrecision")
    protected
    void invoke(final Connection connection, final Object target, final InvokeMethod invokeMethod) throws IOException {
        CachedMethod cachedMethod = invokeMethod.cachedMethod;

        Logger logger2 = this.logger;
        if (logger2.isTraceEnabled()) {
            String argString = "";
            if (invokeMethod.args != null) {
                argString = Arrays.deepToString(invokeMethod.args);
                argString = argString.substring(1, argString.length() - 1);
            }

            //noinspection StringBufferReplaceableByString
            StringBuilder stringBuilder = new StringBuilder(128);
            stringBuilder.append(connection.toString())
                         .append(" received: ")
                         .append(target.getClass()
                                       .getSimpleName());
            stringBuilder.append(":")
                         .append(invokeMethod.objectID);
            stringBuilder.append("#")
                         .append(cachedMethod.method.getName());
            stringBuilder.append("(")
                         .append(argString)
                         .append(")");

            if (cachedMethod.overriddenMethod) {
                // did we override our cached method? This is not common.
                stringBuilder.append(" [Connection method override]");
            }
            logger2.trace(stringBuilder.toString());
        }

        byte responseData = invokeMethod.responseData;
        boolean transmitReturnVal = (responseData & returnValueMask) == returnValueMask;
        boolean transmitExceptions = (responseData & returnExceptionMask) == returnExceptionMask;
        int responseID = responseData & responseIdMask;

        Object result;
        try {
            result = cachedMethod.invoke(connection, target, invokeMethod.args);
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
                String message = "Error invoking method: " + cachedMethod.method.getDeclaringClass()
                                                                                .getName() + "." + cachedMethod.method.getName();
                logger.error(message, ex);
                throw new IOException(message, ex);
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
                  .TCP(invokeMethodResult);

        // logger.error("{} sent data: {}  with id ({})", connection, result, invokeMethod.responseID);
    }

    private
    int nextObjectId() {
        // always increment by 2
        // global RMI objects -> ODD in range 1-16380 (max 2 bytes) throws error on outside of range
        // connection local RMI -> EVEN in range 1-16380 (max 2 bytes)  throws error on outside of range
        int value = rmiObjectIdCounter.getAndAdd(2);
        if (value >= INVALID_RMI) {
            rmiObjectIdCounter.set(INVALID_RMI); // prevent wrapping by spammy callers
            logger.error("next RMI value '{}' has exceeded maximum limit '{}' in RmiBridge! Not creating RMI object.", value,  INVALID_RMI);
            return INVALID_RMI;
        }
        return value;
    }

    /**
     * Automatically registers an object with the next available ID to allow the remote end of the RmiBridge connections to access it using the returned ID.
     *
     * @return the RMI object ID, if the registration failed (null object or TOO MANY objects), it will be {@link RmiBridge#INVALID_RMI} (Integer.MAX_VALUE).
     */
    public
    int register(Object object) {
        if (object == null) {
            return INVALID_RMI;
        }

        // this will return INVALID_RMI if there are too many in the ObjectSpace
        int nextObjectId = nextObjectId();
        if (nextObjectId != INVALID_RMI) {
            // specifically avoid calling register(int, Object) method to skip non-necessary checks + exceptions
            objectMap.put(nextObjectId, object);

            if (logger.isTraceEnabled()) {
                logger.trace("Object <proxy #{}> registered with ObjectSpace with .toString() = '{}'", nextObjectId, object);
            }
        }
        return nextObjectId;
    }

    /**
     * Registers an object to allow the remote end of the RmiBridge connections to access it using the specified ID.
     *
     * @param objectID Must not be <0 or {@link RmiBridge#INVALID_RMI} (Integer.MAX_VALUE).
     */
    public
    void register(int objectID, Object object) {
        if (objectID < 0) {
            throw new IllegalArgumentException("objectID cannot be " + INVALID_RMI);
        }
        if (objectID >= INVALID_RMI) {
            throw new IllegalArgumentException("objectID cannot be " + INVALID_RMI);
        }
        if (object == null) {
            throw new IllegalArgumentException("object cannot be null.");
        }

        objectMap.put(objectID, object);

        if (logger.isTraceEnabled()) {
            logger.trace("Object <proxy #{}> registered with ObjectSpace with .toString() = '{}'", objectID, object);
        }
    }

    /**
     * Removes an object. The remote end of the RmiBridge connection will no longer be able to access it.
     */
    @SuppressWarnings("AutoBoxing")
    public
    void remove(int objectID) {
        Object object = objectMap.remove(objectID);

        if (logger.isTraceEnabled()) {
            logger.trace("Object <proxy #{}> removed from ObjectSpace with .toString() = '{}'", objectID, object);
        }
    }

    /**
     * Removes an object. The remote end of the RmiBridge connection will no longer be able to access it.
     */
    @SuppressWarnings("AutoBoxing")
    public
    void remove(Object object) {
        int objectID = objectMap.inverse()
                                .remove(object);

        if (objectID == INVALID_MAP_ID) {
            logger.error("Object {} could not be found in the ObjectSpace.", object);
        }
        else if (logger.isTraceEnabled()) {
            logger.trace("Object {} (ID: {}) removed from ObjectSpace.", object, objectID);
        }
    }

    /**
     * Returns the object registered with the specified ID.
     */
    public
    Object getRegisteredObject(final int objectID) {
        if (objectID < 0 || objectID >= INVALID_RMI) {
            return null;
        }

        // Find an object with the objectID.
        return objectMap.get(objectID);
    }

    /**
     * Returns the ID registered for the specified object, or INVALID_RMI if not found.
     */
    public
    <T> int getRegisteredId(final T object) {
        // Find an ID with the object.
        int i = objectMap.inverse()
                         .get(object);
        if (i == INVALID_MAP_ID) {
            return INVALID_RMI;
        }

        return i;
    }
}
