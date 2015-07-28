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


import com.esotericsoftware.kryo.Kryo;
import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.ListenerRaw;
import dorkbox.network.util.CryptoSerializationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Handles network communication when methods are invoked on a proxy.
 */
public
class RemoteInvocationHandler implements InvocationHandler {
    private static final Logger logger = LoggerFactory.getLogger(RemoteInvocationHandler.class);
    private final Connection connection;

    public final int objectID;
    private int timeoutMillis = 3000;

    private boolean nonBlocking = false;
    private boolean transmitReturnValue = true;
    private boolean transmitExceptions = true;

    private boolean remoteToString;
    private boolean udp;
    private boolean udt;

    private Byte lastResponseID;
    private byte nextResponseId = 1;

    private final ListenerRaw<Connection, InvokeMethodResult> responseListener;

    final ReentrantLock lock = new ReentrantLock();
    final Condition responseCondition = this.lock.newCondition();

    final InvokeMethodResult[] responseTable = new InvokeMethodResult[64];
    final boolean[] pendingResponses = new boolean[64];

    public
    RemoteInvocationHandler(Connection connection, final int objectID) {
        super();
        this.connection = connection;
        this.objectID = objectID;

        this.responseListener = new ListenerRaw<Connection, InvokeMethodResult>() {
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
//				    System.err.println("FAILED: " + responseID);
//				    logger.trace("{} FAILED to received data: {}  with id ({})", connection, invokeMethodResult.result, invokeMethodResult.responseID);
                    return;
                }

//				logger.trace("{} received data: {}  with id ({})", connection, invokeMethodResult.result, invokeMethodResult.responseID);
                synchronized (this) {
                    if (RemoteInvocationHandler.this.pendingResponses[responseID]) {
                        RemoteInvocationHandler.this.responseTable[responseID] = invokeMethodResult;
                    }
                }

                RemoteInvocationHandler.this.lock.lock();
                try {
                    RemoteInvocationHandler.this.responseCondition.signalAll();
                } finally {
                    RemoteInvocationHandler.this.lock.unlock();
                }
            }
        };

        connection.listeners()
                  .add(this.responseListener);
    }

    @SuppressWarnings({"AutoUnboxing", "AutoBoxing"})
    @Override
    public
    Object invoke(Object proxy, Method method, Object[] args) throws Exception {
        Class<?> declaringClass = method.getDeclaringClass();
        if (declaringClass == RemoteObject.class) {
            String name = method.getName();
            if (name.equals("close")) {
                close();
                return null;
            }
            else if (name.equals("setResponseTimeout")) {
                this.timeoutMillis = (Integer) args[0];
                return null;
            }
            else if (name.equals("setNonBlocking")) {
                this.nonBlocking = (Boolean) args[0];
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
            else if (name.equals("setUDP")) {
                this.udp = (Boolean) args[0];
                return null;
            }
            else if (name.equals("setUDT")) {
                this.udt = (Boolean) args[0];
                return null;
            }
            else if (name.equals("setRemoteToString")) {
                this.remoteToString = (Boolean) args[0];
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
                if (!this.transmitReturnValue && !this.transmitExceptions && this.nonBlocking) {
                    throw new IllegalStateException("This RemoteObject is currently set to ignore all responses.");
                }
                return waitForResponse((Byte) args[0]);
            }
            else if (name.equals("getConnection")) {
                return this.connection;
            }
            // Should never happen, for debugging purposes only
            throw new Exception("Invocation handler could not find RemoteObject method.");
        }
        else if (!this.remoteToString && declaringClass == Object.class && method.getName()
                                                                                 .equals("toString")) {
            return "<proxy>";
        }

        final Logger logger1 = RemoteInvocationHandler.logger;

        EndPoint<Connection> endPoint = this.connection.getEndPoint();
        final CryptoSerializationManager serializationManager = endPoint.getSerialization();

        InvokeMethod invokeMethod = new InvokeMethod();
        invokeMethod.objectID = this.objectID;
        invokeMethod.args = args;


        // thread safe access.
        final Kryo kryo = serializationManager.take();
        if (kryo == null) {
            String msg = "Interrupted during kryo pool.take()";
            logger1.error(msg);
            return msg;
        }

        // which method do we access?
        CachedMethod[] cachedMethods = CachedMethod.getMethods(kryo, method.getDeclaringClass());
        serializationManager.release(kryo);
        for (int i = 0, n = cachedMethods.length; i < n; i++) {
            CachedMethod cachedMethod = cachedMethods[i];
            Method checkMethod = cachedMethod.origMethod;
            if (checkMethod == null) {
                checkMethod = cachedMethod.method;
            }

            // In situations where we want to pass in the Connection (to an RMI method), we have to be able to override method A, with method B.
            // This is to support calling RMI methods from an interface (that does pass the connection reference) to
            // an implementation, that DOES pass the connection reference. The remote side (that initiates the RMI calls), MUST use
            // the interface, and the implementation may override the method, so that we add the connection as the first in
            // the list of parameters.
            //
            // for example:
            // Interface: foo(String x)
            //      Impl: foo(Connection c, String x)
            //
            // The implementation (if it exists, with the same name, and with the same signature+connection) will be called from the interface.
            // This MUST hold valid for both remote and local connection types.

            if (checkMethod.equals(method)) {
                invokeMethod.cachedMethod = cachedMethod;
                break;
            }
        }
        if (invokeMethod.cachedMethod == null) {
            String msg = "Method not found: " + method;
            logger1.error(msg);
            return msg;
        }


        // An invocation doesn't need a response is if it's async and no return values or exceptions are wanted back.
        boolean needsResponse = !this.udp && (this.transmitReturnValue || this.transmitExceptions || !this.nonBlocking);
        byte responseID = 0;
        if (needsResponse) {
            synchronized (this) {
                // Increment the response counter and put it into the low bits of the responseID.
                responseID = this.nextResponseId++;
                if (this.nextResponseId > RmiBridge.responseIdMask) {
                    this.nextResponseId = 1;
                }
                this.pendingResponses[responseID] = true;
            }
            // Pack other data into the high bits.
            byte responseData = responseID;
            if (this.transmitReturnValue) {
                responseData |= RmiBridge.returnValueMask;
            }
            if (this.transmitExceptions) {
                responseData |= RmiBridge.returnExceptionMask;
            }
            invokeMethod.responseData = responseData;
        }
        else {
            invokeMethod.responseData = 0; // A response data of 0 means to not respond.
        }

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

        if (logger1.isTraceEnabled()) {
            String argString = "";
            if (args != null) {
                argString = Arrays.deepToString(args);
                argString = argString.substring(1, argString.length() - 1);
            }
            logger1.trace(this.connection + " sent: " + method.getDeclaringClass()
                                                             .getSimpleName() +
                         "#" + method.getName() + "(" + argString + ")");
        }

        this.lastResponseID = (byte) (invokeMethod.responseData & RmiBridge.responseIdMask);



        if (this.nonBlocking || this.udp || this.udt) {
            Class<?> returnType = method.getReturnType();
            if (returnType.isPrimitive()) {
                if (returnType == int.class) {
                    return 0;
                }
                if (returnType == boolean.class) {
                    return Boolean.FALSE;
                }
                if (returnType == float.class) {
                    return 0f;
                }
                if (returnType == char.class) {
                    return (char) 0;
                }
                if (returnType == long.class) {
                    return 0l;
                }
                if (returnType == short.class) {
                    return (short) 0;
                }
                if (returnType == byte.class) {
                    return (byte) 0;
                }
                if (returnType == double.class) {
                    return 0d;
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
    Object waitForResponse(byte responseID) throws IOException {
        long endTime = System.currentTimeMillis() + this.timeoutMillis;
        long remaining = this.timeoutMillis;

        if (remaining == 0) {
            // just wait however log it takes.
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
                    this.responseCondition.await();
                } catch (InterruptedException e) {
                    Thread.currentThread()
                          .interrupt();
                    throw new IOException("Response timed out.", e);
                } finally {
                    this.lock.unlock();
                }
            }

            synchronized (this) {
                invokeMethodResult = this.responseTable[responseID];
            }
            if (invokeMethodResult != null) {
                this.lastResponseID = null;
                return invokeMethodResult.result;
            }
        }
        else {
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
        }



        // only get here if we timeout
        throw new TimeoutException("Response timed out.");
    }


    void close() {
        this.connection.listeners()
                       .remove(this.responseListener);
    }

    @Override
    public
    int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (this.connection == null ? 0 : this.connection.hashCode());
        result = prime * result + (this.lastResponseID == null ? 0 : this.lastResponseID.hashCode());
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
        RemoteInvocationHandler other = (RemoteInvocationHandler) obj;
        if (this.connection == null) {
            if (other.connection != null) {
                return false;
            }
        }
        else if (!this.connection.equals(other.connection)) {
            return false;
        }
        if (this.lastResponseID == null) {
            if (other.lastResponseID != null) {
                return false;
            }
        }
        else if (!this.lastResponseID.equals(other.lastResponseID)) {
            return false;
        }
        return this.objectID == other.objectID;
    }
}
