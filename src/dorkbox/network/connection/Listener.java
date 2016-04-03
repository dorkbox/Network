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

import java.io.IOException;

public
interface Listener {
    /**
     * Called when the remote end has been connected. This will be invoked before any objects are received by the network.
     * This method should not block for long periods as other network activity will not be processed
     * until it returns.
     */
    interface OnConnected<C extends Connection> extends Listener {
        /**
         * Called when the remote end has been connected. This will be invoked before any objects are received by the network.
         * This method should not block for long periods as other network activity will not be processed
         * until it returns.
         */
        void connected(C connection);
    }

    /**
     * Called when the remote end is no longer connected. There is no guarantee as to what thread will invoke this method.
     * <p/>
     * Do not write data in this method! The channel can already be closed, resulting in an error if you attempt to do so.
     */
    interface OnDisconnected<C extends Connection> extends Listener {
        /**
         * Called when the remote end is no longer connected. There is no guarantee as to what thread will invoke this method.
         * <p/>
         * Do not write data in this method! The channel can already be closed, resulting in an error if you attempt to do so.
         */
        void disconnected(C connection);
    }


    /**
     * Called when there is an error of some kind during the up/down stream process (to/from the socket or otherwise)
     */
    interface OnError<C extends Connection> extends Listener {
        /**
         * Called when there is an error of some kind during the up/down stream process (to/from the socket or otherwise).
         *
         * The error is sent to an error log before this method is called.
         */
        void error(C connection, Throwable throwable);
    }


    /**
     * Called when the connection is idle for longer than the {@link EndPoint#setIdleTimeout(int)} idle threshold.
     */
    interface OnIdle<C extends Connection> extends Listener {

        /**
         * Called when the connection is idle for longer than the {@link EndPoint#setIdleTimeout(int)} idle threshold.
         */
        void idle(C connection) throws IOException;
    }


    /**
     * Called when an object has been received from the remote end of the connection.
     * This method should not block for long periods as other network activity will not be processed until it returns.
     */
    interface OnMessageReceived<C extends Connection, M extends Object> extends Listener {
        void received(C connection, M message);
    }


    /**
     * Permits a listener to specify it's own referenced object type, if passing in a generic parameter doesn't work. This is necessary since
     * the system looks up incoming message types to determine what listeners to dispatch them to.
     */
    interface SelfDefinedType {
        /**
         * Permits a listener to specify it's own referenced object type, if passing in a generic parameter doesn't work. This is necessary since
         * the system looks up incoming message types to determine what listeners to dispatch them to.
         */
        Class<?> getType();
    }
}
