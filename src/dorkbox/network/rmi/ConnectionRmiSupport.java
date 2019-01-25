/*
 * Copyright 2019 dorkbox, llc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.network.rmi;

import dorkbox.network.connection.ConnectionImpl;

public
interface ConnectionRmiSupport {
    void close();

    void removeAllListeners();

    <Iface> void createRemoteObject(final ConnectionImpl connection, final Class<Iface> interfaceClass, final RemoteObjectCallback<Iface> callback);

    <Iface> void getRemoteObject(final ConnectionImpl connection, final int objectId, final RemoteObjectCallback<Iface> callback);

    boolean manage(final ConnectionImpl connection, final Object message);

    Object fixupRmi(final ConnectionImpl connection, final Object message);

    <T> int getRegisteredId(final T object);

    RemoteObject getProxyObject(final int rmiId, final Class<?> iFace);

    Object getImplementationObject(final int objectId);
}
