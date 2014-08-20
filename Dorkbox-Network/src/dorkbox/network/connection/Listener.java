package dorkbox.network.connection;

import dorkbox.util.ClassHelper;

// note that we specifically DO NOT implement equals/hashCode, because we cannot create two separate
// listeners that are somehow equal to each other.
public abstract class Listener<C extends Connection, M extends Object> {

    private final Class<?> objectType;

    // for compile time code. The generic type parameter #2 (index 1) is pulled from type arguments.
    // generic parameters cannot be primitive types
    public Listener() {
        this(1);
    }

    // for sub-classed listeners, we might have to specify which parameter to use.
    protected Listener(int lastParameterIndex) {
        if (lastParameterIndex > -1) {
            Class<?> objectType = ClassHelper.getGenericParameterAsClassForSuperClass(getClass(), lastParameterIndex);

            if (objectType != null) {
                this.objectType = objectType;
            } else {
                this.objectType = Object.class;
            }
        } else {
            // for when we want to override it
            this.objectType = Object.class;
        }
    }

    /**
     * Gets the referenced object type.
     *
     * non-final so this can be overridden by listeners that aren't able to define their type as a generic parameter
     */
    public Class<?> getObjectType() {
        return this.objectType;
    }

    /**
     * Called when the remote end has been connected. This will be invoked before any objects are received by the network.
     * This method should not block for long periods as other network activity will not be processed
     * until it returns.
     */
    public void connected(C connection) {
    }

    /**
     * Called when the remote end is no longer connected. There is no guarantee as to what thread will invoke this method.
     * <p>
     * Do not write data in this method! The channel can be closed, resulting in an error if you attempt to do so.
     */
    public void disconnected(C connection) {
    }

    /**
     * Called when an object has been received from the remote end of the connection.
     * This method should not block for long periods as other network activity will not be processed until it returns.
     */
    public void received(C connection, M message) {
    }

    /**
     * Called when the connection is idle for longer than the {@link EndPoint#setIdleTimeout(idle) idle threshold}.
     */
    public void idle(C connection) {
    }

    /**
     * Called when there is an error of some kind during the up/down stream process (to/from the socket or otherwise)
     */
    public void error(C connection, Throwable throwable) {
        throwable.printStackTrace();
    }

    @Override
    public String toString() {
        return "Listener [type=" + getObjectType() + "]";
    }
}
