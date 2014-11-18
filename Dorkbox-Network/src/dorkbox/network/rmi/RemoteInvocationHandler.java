package dorkbox.network.rmi;


import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.ListenerRaw;
import dorkbox.network.util.exceptions.NetException;

/** Handles network communication when methods are invoked on a proxy. */
class RemoteInvocationHandler implements InvocationHandler {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(RemoteInvocationHandler.class);
    private final Connection connection;

    final int objectID;
    private int timeoutMillis = 3000;

    private boolean nonBlocking = false;
    private boolean transmitReturnValue = true;
    private boolean transmitExceptions = true;

    private boolean remoteToString;
    private Byte lastResponseID;
    private byte nextResponseId = 1;

    private ListenerRaw<Connection, InvokeMethodResult> responseListener;

    final ReentrantLock lock = new ReentrantLock();
    final Condition responseCondition = this.lock.newCondition();

    final InvokeMethodResult[] responseTable = new InvokeMethodResult[64];
    final boolean[] pendingResponses = new boolean[64];

    public RemoteInvocationHandler(Connection connection, final int objectID) {
        super();
        this.connection = connection;
        this.objectID = objectID;

        this.responseListener = new ListenerRaw<Connection, InvokeMethodResult>() {
            @Override
            public void received (Connection connection, InvokeMethodResult invokeMethodResult) {
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

            @Override
            public void disconnected(Connection connection) {
                close();
            }
        };

        connection.listeners().add(this.responseListener);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Exception {
        Class<?> declaringClass = method.getDeclaringClass();
        if (declaringClass == RemoteObject.class) {
            String name = method.getName();
            if (name.equals("close")) {
                close();
                return null;
            } else if (name.equals("setResponseTimeout")) {
                this.timeoutMillis = (Integer) args[0];
                return null;
            } else if (name.equals("setNonBlocking")) {
                this.nonBlocking = (Boolean) args[0];
                return null;
            } else if (name.equals("setTransmitReturnValue")) {
                this.transmitReturnValue = (Boolean) args[0];
                return null;
            } else if (name.equals("setTransmitExceptions")) {
                this.transmitExceptions = (Boolean) args[0];
                return null;
            } else if (name.equals("setRemoteToString")) {
                this.remoteToString = (Boolean) args[0];
                return null;
            } else if (name.equals("waitForLastResponse")) {
                if (this.lastResponseID == null) {
                    throw new IllegalStateException("There is no last response to wait for.");
                }
                return waitForResponse(this.lastResponseID);
            } else if (name.equals("getLastResponseID")) {
                if (this.lastResponseID == null) {
                    throw new IllegalStateException("There is no last response ID.");
                }
                return this.lastResponseID;
            } else if (name.equals("waitForResponse")) {
                if (!this.transmitReturnValue && !this.transmitExceptions && this.nonBlocking) {
                    throw new IllegalStateException("This RemoteObject is currently set to ignore all responses.");
                }
                return waitForResponse((Byte) args[0]);
            } else if (name.equals("getConnection")) {
                return this.connection;
            }
            // Should never happen, for debugging purposes only
            throw new Exception("Invocation handler could not find RemoteObject method. Check ObjectSpace.java");
        } else if (!this.remoteToString && declaringClass == Object.class && method.getName().equals("toString")) {
            return "<proxy>";
        }

        InvokeMethod invokeMethod = new InvokeMethod();
        invokeMethod.objectID = this.objectID;
        invokeMethod.args = args;

        EndPoint endPoint = this.connection.getEndPoint();
        CachedMethod[] cachedMethods = RmiBridge.getMethods(endPoint.getSerialization().getSingleInstanceUnsafe(), method.getDeclaringClass());
        for (int i = 0, n = cachedMethods.length; i < n; i++) {
            CachedMethod cachedMethod = cachedMethods[i];
            if (cachedMethod.method.equals(method)) {
                invokeMethod.cachedMethod = cachedMethod;
                break;
            }
        }
        if (invokeMethod.cachedMethod == null) {
            String msg = "Method not found: " + method;
            logger.error(msg);
            return msg;
        }


        // An invocation doesn't need a response is if it's async and no return values or exceptions are wanted back.
        boolean needsResponse = this.transmitReturnValue || this.transmitExceptions || !this.nonBlocking;
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
                responseData |= RmiBridge.returnValMask;
            }
            if (this.transmitExceptions) {
                responseData |= RmiBridge.returnExMask;
            }
            invokeMethod.responseData = responseData;
        } else {
            invokeMethod.responseData = 0; // A response data of 0 means to not respond.
        }

        this.connection.send().TCP(invokeMethod).flush();

        if (logger.isDebugEnabled()) {
            String argString = "";
            if (args != null) {
                argString = Arrays.deepToString(args);
                argString = argString.substring(1, argString.length() - 1);
            }
            logger.debug(this.connection + " sent: " + method.getDeclaringClass().getSimpleName() +
                         "#" + method.getName() + "(" + argString + ")");
        }

        this.lastResponseID = (byte)(invokeMethod.responseData & RmiBridge.responseIdMask);

        if (this.nonBlocking) {
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
                    return (char)0;
                }
                if (returnType == long.class) {
                    return 0l;
                }
                if (returnType == short.class) {
                    return (short)0;
                }
                if (returnType == byte.class) {
                    return (byte)0;
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
                throw (Exception)result;
            } else {
                return result;
            }
        } catch (TimeoutException ex) {
            throw new TimeoutException("Response timed out: " + method.getDeclaringClass().getName() + "." + method.getName());
        } finally {
            synchronized (this) {
                this.pendingResponses[responseID] = false;
                this.responseTable[responseID] = null;
            }
        }
    }

    private Object waitForResponse(byte responseID) {

        long endTime = System.currentTimeMillis() + this.timeoutMillis;
        long remaining = this.timeoutMillis;

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
                    Thread.currentThread().interrupt();
                    throw new NetException(e);
                } finally {
                    this.lock.unlock();
                }
            }

            remaining = endTime - System.currentTimeMillis();
        }

        // only get here if we timeout
        throw new TimeoutException("Response timed out.");
    }


    void close() {
        this.connection.listeners().remove(this.responseListener);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (this.connection == null ? 0 : this.connection.hashCode());
        result = prime * result + (this.lastResponseID == null ? 0 : this.lastResponseID.hashCode());
        result = prime * result + this.objectID;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
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
        } else if (!this.connection.equals(other.connection)) {
            return false;
        }
        if (this.lastResponseID == null) {
            if (other.lastResponseID != null) {
                return false;
            }
        } else if (!this.lastResponseID.equals(other.lastResponseID)) {
            return false;
        }
        if (this.objectID != other.objectID) {
            return false;
        }
        return true;
    }
}