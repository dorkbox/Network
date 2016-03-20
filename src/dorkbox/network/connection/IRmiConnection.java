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

import dorkbox.network.rmi.RemoteObject;

/**
 * Supporting methods for RMI connections
 */
public
interface IRmiConnection {

    /**
     * Used by RMI for the LOCAL side, to get the proxy object as an interface
     *
     * @param type must be the interface the proxy will bind to
     */
    RemoteObject getProxyObject(final int objectID, final Class<?> type);

    /**
     * This is used by RMI for the REMOTE side, to get the implementation
     */
    Object getImplementationObject(final int objectID);

    /**
     * Used by RMI
     *
     * @return the registered ID for a specific object. This is used by the "local" side when setting up the to fetch an object for the
     * "remote" side for RMI
     */
    <T> int getRegisteredId(final T object);
}
