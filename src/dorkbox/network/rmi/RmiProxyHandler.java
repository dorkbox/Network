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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.util.RmiSerializationManager;

/**
 * Handles network communication when methods are invoked on a proxy.
 */
class RmiProxyHandler implements InvocationHandler {
    private final Logger logger;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition responseCondition = this.lock.newCondition();

    private final InvokeMethodResult[] responseTable = new InvokeMethodResult[64];
    private final boolean[] pendingResponses = new boolean[64];

    private final Connection connection;
    public final int objectID;
    private final String proxyString;
    private final RemoteInvocationResponse<Connection> responseListener;

    private int timeoutMillis = 3000;
    private boolean isAsync = false;

    private boolean transmitReturnValue = true;
    private boolean transmitExceptions = true;

    private boolean enableToString;

    private boolean udp;
    private boolean udt;

    private Byte lastResponseID;
    private byte nextResponseId = (byte) 1;

    RmiProxyHandler(final Connection connection, final int objectID) {
        super();

        this.connection = connection;
        this.objectID = objectID;
        this.proxyString = "<proxy #" + objectID + ">";

        logger = LoggerFactory.getLogger(connection.getEndPoint().getName() + ":" + this.getClass().getSimpleName());


        this.responseListener = new RemoteInvocationResponse<Connection>() {
            @Override
            public
            void disconnected(Connection connection) {
                close();
            }

            @Override
            public
            void received(Connection connection, InvokeMethodResult invokeMethodResult) {
                byte responseID = invokeMethodResult.responseID;

                if (invokeMethodResult.objectID != objectID) {
                    return;
                }

                synchronized (this) {
                    if (RmiProxyHandler.this.pendingResponses[responseID]) {
                        RmiProxyHandler.this.responseTable[responseID] = invokeMethodResult;
                    }
                }

                RmiProxyHandler.this.lock.lock();
                try {
                    RmiProxyHandler.this.responseCondition.signalAll();
                } finally {
                    RmiProxyHandler.this.lock.unlock();
                }
            }
        };

        connection.listeners()
                  .add(this.responseListener);
    }



    @SuppressWarnings({"AutoUnboxing", "AutoBoxing", "NumericCastThatLosesPrecision", "IfCanBeSwitch"})
    @Override
    public
    Object invoke(final Object proxy, final Method method, final Object[] args) throws Exception {
        final Class<?> declaringClass = method.getDeclaringClass();
        if (declaringClass == RemoteObject.class) {
            // manage all of the RemoteObject proxy methods

            String name = method.getName();
            if (name.equals("close")) {
                close();
                return null;
            }
            else if (name.equals("setResponseTimeout")) {
                this.timeoutMillis = (Integer) args[0];
                return null;
            }
            else if (name.equals("setAsync")) {
                this.isAsync = (Boolean) args[0];
                return null;
            }
            else if (name.equals("setTransmitReturnValue")) {
                this.transmitReturnValue = (Boolean) args[0];
                return null;
            }
            else if (name.equals("setTransmitExceptions")) {
                this.transmitExceptions = (Boolean) args[0];
                return null;
            }
            else if (name.equals("setTCP")) {
                this.udp = false;
                this.udt = false;
                return null;
            }
            else if (name.equals("setUDP")) {
                this.udp = true;
                this.udt = false;
                return null;
            }
            else if (name.equals("setUDT")) {
                this.udp = false;
                this.udt = true;
                return null;
            }
            else if (name.equals("enableToString")) {
                this.enableToString = (Boolean) args[0];
                return null;
            }
            else if (name.equals("waitForLastResponse")) {
                if (this.lastResponseID == null) {
                    throw new IllegalStateException("There is no last response to wait for.");
                }
                return waitForResponse(this.lastResponseID);
            }
            else if (name.equals("getLastResponseID")) {
                if (this.lastResponseID == null) {
                    throw new IllegalStateException("There is no last response ID.");
                }
                return this.lastResponseID;
            }
            else if (name.equals("waitForResponse")) {
                if (!this.transmitReturnValue && !this.transmitExceptions && this.isAsync) {
                    throw new IllegalStateException("This RemoteObject is currently set to ignore all responses.");
                }
                return waitForResponse((Byte) args[0]);
            }

            // Should never happen, for debugging purposes only!
            throw new Exception("Invocation handler could not find RemoteObject method for " + name);
        }
        else if (!this.enableToString && declaringClass == Object.class && method.getName()
                                                                                 .equals("toString")) {
            return proxyString;
        }

        EndPoint endPoint = this.connection.getEndPoint();
        final RmiSerializationManager serializationManager = endPoint.getSerialization();

        InvokeMethod invokeMethod = new InvokeMethod();
        invokeMethod.objectID = this.objectID;
        invokeMethod.args = args;


        // which method do we access?
        CachedMethod[] cachedMethods = CachedMethod.getMethods(serializationManager, method.getDeclaringClass(), invokeMethod.objectID);

        for (int i = 0, n = cachedMethods.length; i < n; i++) {
            CachedMethod cachedMethod = cachedMethods[i];
            Method checkMethod = cachedMethod.origMethod;
            if (checkMethod == null) {
                checkMethod = cachedMethod.method;
            }

            // In situations where we want to pass in the Connection (to an RMI method), we have to be able to override method A, with method B.
            // This is to support calling RMI methods from an interface (that does pass the connection reference) to
            // an implType, that DOES pass the connection reference. The remote side (that initiates the RMI calls), MUST use
            // the interface, and the implType may override the method, so that we add the connection as the first in
            // the list of parameters.
            //
            // for example:
            // Interface: foo(String x)
            //      Impl: foo(Connection c, String x)
            //
            // The implType (if it exists, with the same name, and with the same signature+connection) will be called from the interface.
            // This MUST hold valid for both remote and local connection types.

            // To facilitate this functionality, for methods with the same name, the "overriding" method is the one that inherits the Connection
            // interface as the first parameter, and  .registerRemote(ifaceClass, implClass)  must be called.

            if (checkMethod.equals(method)) {
                invokeMethod.cachedMethod = cachedMethod;
                break;
            }
        }

        if (invokeMethod.cachedMethod == null) {
            String msg = "Method not found: " + method;
            logger.error(msg);
            return msg;
        }


        byte responseID = (byte) 0;
        // An invocation doesn't need a response is if it's
        // ASYNC and no return values or exceptions are wanted back
        Class<?> returnType = method.getReturnType();
        boolean ignoreResponse = this.isAsync && !(this.transmitReturnValue || this.transmitExceptions);
        if (ignoreResponse) {
            invokeMethod.responseData = (byte) 0; // 0 means do not respond.
        }
        else {
            synchronized (this) {
                // Increment the response counter and put it into the low bits of the responseID.
                responseID = this.nextResponseId++;
                if (this.nextResponseId > RmiBridge.responseIdMask) {
                    this.nextResponseId = (byte) 1;
                }
                this.pendingResponses[responseID] = true;
            }
            // Pack other data into the high bits.
            byte responseData = responseID;
            if (this.transmitReturnValue) {
                responseData |= (byte) RmiBridge.returnValueMask;
            }
            if (this.transmitExceptions) {
                responseData |= (byte) RmiBridge.returnExceptionMask;
            }
            invokeMethod.responseData = responseData;
        }

        this.lastResponseID = (byte) (invokeMethod.responseData & RmiBridge.responseIdMask);

        // Sends our invokeMethod to the remote connection, which the RmiBridge listens for
        if (this.udp) {
            this.connection.send()
                           .UDP(invokeMethod)
                           .flush();
        }
        else if (this.udt) {
            this.connection.send()
                           .UDT(invokeMethod)
                           .flush();
        }
        else {
            this.connection.send()
                           .TCP(invokeMethod)
                           .flush();
        }

        if (logger.isTraceEnabled()) {
            String argString = "";
            if (args != null) {
                argString = Arrays.deepToString(args);
                argString = argString.substring(1, argString.length() - 1);
            }
            logger.trace(this.connection + " sent: " + method.getDeclaringClass()
                                                              .getSimpleName() +
                          "#" + method.getName() + "(" + argString + ")");
        }

        // 0 means respond immediately because it's
        // ASYNC and no return values or exceptions are wanted back
        if (this.isAsync) {
            if (returnType.isPrimitive()) {
                if (returnType == int.class) {
                    return 0;
                }
                if (returnType == boolean.class) {
                    return Boolean.FALSE;
                }
                if (returnType == float.class) {
                    return 0.0f;
                }
                if (returnType == char.class) {
                    return (char) 0;
                }
                if (returnType == long.class) {
                    return 0L;
                }
                if (returnType == short.class) {
                    return (short) 0;
                }
                if (returnType == byte.class) {
                    return (byte) 0;
                }
                if (returnType == double.class) {
                    return 0.0d;
                }
            }
            return null;
        }

        try {
            Object result = waitForResponse(this.lastResponseID);
            if (result != null && result instanceof Exception) {
                throw (Exception) result;
            }
            else {
                return result;
            }
        } catch (TimeoutException ex) {
            throw new TimeoutException("Response timed out: " + method.getDeclaringClass()
                                                                      .getName() + "." + method.getName());
        } finally {
            synchronized (this) {
                this.pendingResponses[responseID] = false;
                this.responseTable[responseID] = null;
            }
        }
    }

    /**
     * A timeout of 0 means that we want to disable waiting, otherwise - it waits in milliseconds
     */
    private
    Object waitForResponse(final byte responseID) throws IOException {
        // if timeout == 0, we wait "forever"
        long remaining;
        long endTime;

        if (this.timeoutMillis != 0) {
            remaining = this.timeoutMillis;
            endTime = System.currentTimeMillis() + remaining;
        } else {
            // not forever, but close enough
            remaining = Long.MAX_VALUE;
            endTime = Long.MAX_VALUE;
        }

        // wait for the specified time
        while (remaining > 0) {
            InvokeMethodResult invokeMethodResult;
            synchronized (this) {
                invokeMethodResult = this.responseTable[responseID];
            }

            if (invokeMethodResult != null) {
                this.lastResponseID = null;
                return invokeMethodResult.result;
            }
            else {
                this.lock.lock();
                try {
                    this.responseCondition.await(remaining, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread()
                          .interrupt();
                    throw new IOException("Response timed out.", e);
                } finally {
                    this.lock.unlock();
                }
            }

            remaining = endTime - System.currentTimeMillis();
        }

        // only get here if we timeout
        throw new TimeoutException("Response timed out.");
    }

    private
    void close() {
        this.connection.listeners()
                       .remove(this.responseListener);
    }

    @Override
    public
    int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + this.objectID;
        return result;
    }

    @Override
    public
    boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        RmiProxyHandler other = (RmiProxyHandler) obj;
        return this.objectID == other.objectID;
    }
}
