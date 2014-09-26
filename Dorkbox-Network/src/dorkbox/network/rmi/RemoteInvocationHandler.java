package dorkbox.network.rmi;


import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.ListenerRaw;

/** Handles network communication when methods are invoked on a proxy. */
class RemoteInvocationHandler implements InvocationHandler {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(RemoteInvocationHandler.class);
    private final Connection connection;

    final int objectID;
    private int timeoutMillis = 3000;

    private boolean nonBlocking = false;
    private boolean transmitReturnValue = true;
    private boolean transmitExceptions = true;

    private Byte lastResponseID;
    private byte nextResponseNum = 1;

    private ListenerRaw<Connection, InvokeMethodResult> responseListener;

    final ReentrantLock lock = new ReentrantLock();
    final Condition responseCondition = lock.newCondition();

    final ConcurrentHashMap<Byte, InvokeMethodResult> responseTable = new ConcurrentHashMap<Byte, InvokeMethodResult>();


    public RemoteInvocationHandler(Connection connection, final int objectID) {
        super();
        this.connection = connection;
        this.objectID = objectID;

        responseListener = new ListenerRaw<Connection, InvokeMethodResult>() {
            @Override
            public void received (Connection connection, InvokeMethodResult invokeMethodResult) {
                byte responseID = invokeMethodResult.responseID;

                if (invokeMethodResult.objectID != objectID) {
//				    System.err.println("FAILED: " + responseID);
//				    logger.trace("{} FAILED to received data: {}  with id ({})", connection, invokeMethodResult.result, invokeMethodResult.responseID);
                    return;
                }

//				System.err.println("Recieved: " + responseID);

//				logger.trace("{} received data: {}  with id ({})", connection, invokeMethodResult.result, invokeMethodResult.responseID);

                responseTable.put(responseID, invokeMethodResult);

//			    System.err.println("L");
                lock.lock();
                try {
                    responseCondition.signalAll();
                } finally {
                    lock.unlock();
//                    System.err.println("U");
                }
            }

            @Override
            public void disconnected(Connection connection) {
                close();
            }
        };

        connection.listeners().add(responseListener);
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



    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Exception {
        if (method.getDeclaringClass() == RemoteObject.class) {
            String name = method.getName();
            if (name.equals("close")) {
                close();
                return null;
            } else if (name.equals("setResponseTimeout")) {
                timeoutMillis = (Integer)args[0];
                return null;
            } else if (name.equals("setNonBlocking")) {
                nonBlocking = (Boolean)args[0];
                return null;
            } else if (name.equals("setTransmitReturnValue")) {
                transmitReturnValue = (Boolean)args[0];
                return null;
            } else if (name.equals("setTransmitExceptions")) {
                transmitExceptions = (Boolean)args[0];
                return null;
            } else if (name.equals("waitForLastResponse")) {
                if (lastResponseID == null) {
                    throw new IllegalStateException("There is no last response to wait for.");
                }
                return waitForResponse(lastResponseID);
            } else if (name.equals("getLastResponseID")) {
                if (lastResponseID == null) {
                    throw new IllegalStateException("There is no last response ID.");
                }
                return lastResponseID;
            } else if (name.equals("waitForResponse")) {
                if (!transmitReturnValue && !transmitExceptions && nonBlocking) {
                    throw new IllegalStateException("This RemoteObject is currently set to ignore all responses.");
                }
                return waitForResponse((Byte)args[0]);
            } else if (name.equals("getConnection")) {
                return connection;
            } else {
                // Should never happen, for debugging purposes only
                throw new RuntimeException("Invocation handler could not find RemoteObject method. Check ObjectSpace.java");
            }
        } else if (method.getDeclaringClass() == Object.class) {
            if (method.getName().equals("toString")) {
                return "<proxy>";
            }
            try {
                return method.invoke(proxy, args);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }

        InvokeMethod invokeMethod = new InvokeMethod();
        invokeMethod.objectID = objectID;
        invokeMethod.method = method;
        invokeMethod.args = args;

        // The only time a invocation doesn't need a response is if it's async
        // and no return values or exceptions are wanted back.
        boolean needsResponse = transmitReturnValue || transmitExceptions || !nonBlocking;
        if (needsResponse) {
            byte responseID;
            synchronized (this) {
                // Increment the response counter and put it into the first six bits of the responseID byte
                responseID = nextResponseNum++;
                if (nextResponseNum == 64) {
                    nextResponseNum = 1; // Keep number under 2^6, avoid 0 (see else statement below)
                }
            }
            // Pack return value and exception info into the top two bits
            if (transmitReturnValue) {
                responseID |= RmiBridge.kReturnValMask;
            }
            if (transmitExceptions) {
                responseID |= RmiBridge.kReturnExMask;
            }
            invokeMethod.responseID = responseID;
        } else {
            invokeMethod.responseID = 0; // A response info of 0 means to not respond
        }

        connection.send().TCP(invokeMethod).flush();

        if (logger.isDebugEnabled()) {
            String argString = "";
            if (args != null) {
                argString = Arrays.deepToString(args);
                argString = argString.substring(1, argString.length() - 1);
            }
            logger.debug(connection + " sent: " + method.getDeclaringClass().getSimpleName() +
                         "#" + method.getName() + "(" + argString + ")");
        }

        if (invokeMethod.responseID != 0) {
            lastResponseID = invokeMethod.responseID;
        }

        if (nonBlocking) {
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
            Object result = waitForResponse(invokeMethod.responseID);
            if (result != null && result instanceof Exception) {
                throw (Exception)result;
            } else {
                return result;
            }
        } catch (TimeoutException ex) {
            throw new TimeoutException("Response timed out: " + method.getDeclaringClass().getName() + "." + method.getName());
        }
    }

    private Object waitForResponse(byte responseID) {

        long endTime = System.currentTimeMillis() + timeoutMillis;
        long remaining = timeoutMillis;

        while (remaining > 0) {
//            System.err.println("Waiting for: " + responseID);
            if (responseTable.containsKey(responseID)) {
                InvokeMethodResult invokeMethodResult = responseTable.get(responseID);
                responseTable.remove(responseID);
                lastResponseID = null;
                return invokeMethodResult.result;
            }
            else {
//                System.err.println("LL");
                lock.lock();
                try {
                    responseCondition.await(remaining, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                } finally {
                    lock.unlock();
//                    System.err.println("UU");
                }
            }

            remaining = endTime - System.currentTimeMillis();
        }

        // only get here if we timeout
        throw new TimeoutException("Response timed out.");
    }


    void close() {
        connection.listeners().remove(responseListener);
    }
}