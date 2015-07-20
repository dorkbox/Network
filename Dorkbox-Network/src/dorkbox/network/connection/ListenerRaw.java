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
 */
package dorkbox.network.connection;

import dorkbox.util.ClassHelper;

public abstract
class ListenerRaw<C extends Connection, M extends Object> {

    private final Class<?> objectType;

    // for compile time code. The generic type parameter #2 (index 1) is pulled from type arguments.
    // generic parameters cannot be primitive types
    public
    ListenerRaw() {
        this(1);
    }

    // for sub-classed listeners, we might have to specify which parameter to use.
    protected
    ListenerRaw(int lastParameterIndex) {
        if (lastParameterIndex > -1) {
            @SuppressWarnings("rawtypes")
            Class<? extends ListenerRaw> class1 = getClass();

            Class<?> objectType = ClassHelper.getGenericParameterAsClassForSuperClass(class1, lastParameterIndex);

            if (objectType != null) {
                // SOMETIMES generics get confused on which parameter we actually mean (when sub-classing)
                if (objectType != Object.class && ClassHelper.hasInterface(Connection.class, objectType)) {
                    Class<?> objectType2 = ClassHelper.getGenericParameterAsClassForSuperClass(class1, lastParameterIndex + 1);
                    if (objectType2 != null) {
                        objectType = objectType2;
                    }
                }

                this.objectType = objectType;
            }
            else {
                this.objectType = Object.class;
            }
        }
        else {
            // for when we want to override it
            this.objectType = Object.class;
        }
    }

    /**
     * Gets the referenced object type.
     * <p/>
     * non-final so this can be overridden by listeners that aren't able to define their type as a generic parameter
     */
    public
    Class<?> getObjectType() {
        return this.objectType;
    }

    /**
     * Called when the remote end has been connected. This will be invoked before any objects are received by the network.
     * This method should not block for long periods as other network activity will not be processed
     * until it returns.
     */
    @SuppressWarnings("unused")
    public
    void connected(C connection) {
    }

    /**
     * Called when the remote end is no longer connected. There is no guarantee as to what thread will invoke this method.
     * <p/>
     * Do not write data in this method! The channel can be closed, resulting in an error if you attempt to do so.
     */
    @SuppressWarnings("unused")
    public
    void disconnected(C connection) {
    }

    /**
     * Called when an object has been received from the remote end of the connection.
     * This method should not block for long periods as other network activity will not be processed until it returns.
     */
    @SuppressWarnings("unused")
    public
    void received(C connection, M message) {
    }

    /**
     * Called when the connection is idle for longer than the {@link EndPoint#setIdleTimeout(int)} idle threshold.
     */
    @SuppressWarnings("unused")
    public
    void idle(C connection) {
    }

    /**
     * Called when there is an error of some kind during the up/down stream process (to/from the socket or otherwise)
     */
    @SuppressWarnings("unused")
    public
    void error(C connection, Throwable throwable) {
        throwable.printStackTrace();
    }

    @Override
    public
    int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (this.objectType == null ? 0 : this.objectType.hashCode());
        return result;
    }

    // only possible way for it to be equal, is if it is the same object
    @Override
    public
    boolean equals(Object obj) {
        return this == obj;

    }

    @Override
    public
    String toString() {
        return "Listener [type=" + getObjectType() + "]";
    }
}
