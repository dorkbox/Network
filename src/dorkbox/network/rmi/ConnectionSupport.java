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

import org.slf4j.Logger;

import dorkbox.network.connection.ConnectionImpl;

/**
 *
 */
public
class ConnectionSupport {
    public
    void close() {
    }

    public
    void removeAllListeners() {
    }

    public
    <Iface> void createRemoteObject(final ConnectionImpl connection, final Class<Iface> interfaceClass, final RemoteObjectCallback<Iface> callback) {
    }

    public
    <Iface> void getRemoteObject(final ConnectionImpl connection, final int objectId, final RemoteObjectCallback<Iface> callback) {
    }

    public
    boolean manage(final ConnectionImpl connection, final Object message) {
        return false;
    }

    public
    Object fixupRmi(final ConnectionImpl connection, final Object message) {
        return message;
    }

    public
    <T> int getRegisteredId(final T object) {
        return RmiBridge.INVALID_RMI;
    }

    public
    void runCallback(final Class<?> interfaceClass, final int callbackId, final Object remoteObject, final Logger logger) {
    }

    public
    RemoteObject getProxyObject(final int rmiId, final Class<?> iFace) {
        return null;
    }

    public
    Object getImplementationObject(final int objectId) {
        return null;
    }
}
