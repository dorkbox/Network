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

import javax.crypto.SecretKey;

import dorkbox.network.connection.ConnectionPoint;
import dorkbox.network.connection.Connection_;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.Listeners;
import dorkbox.network.connection.bridge.ConnectionBridge;
import dorkbox.network.connection.idle.IdleBridge;
import dorkbox.network.connection.idle.IdleSender;

public
class RmiNopConnection implements Connection_ {
    @Override
    public
    boolean hasRemoteKeyChanged() {
        return false;
    }

    @Override
    public
    String getRemoteHost() {
        return null;
    }

    @Override
    public
    boolean isLoopback() {
        return false;
    }

    @Override
    public
    EndPoint getEndPoint() {
        return null;
    }

    @Override
    public
    int id() {
        return 0;
    }

    @Override
    public
    String idAsHex() {
        return null;
    }

    @Override
    public
    boolean hasUDP() {
        return false;
    }

    @Override
    public
    ConnectionBridge send() {
        return null;
    }

    @Override
    public
    ConnectionPoint send(final Object message) {
        return null;
    }

    @Override
    public
    IdleBridge sendOnIdle(final IdleSender<?, ?> sender) {
        return null;
    }

    @Override
    public
    IdleBridge sendOnIdle(final Object message) {
        return null;
    }

    @Override
    public
    Listeners listeners() {
        return null;
    }

    @Override
    public
    void close() {

    }

    @Override
    public
    void closeAsap() {

    }

    @Override
    public
    <Iface> void createRemoteObject(final Class<Iface> interfaceClass, final RemoteObjectCallback<Iface> callback) {

    }

    @Override
    public
    <Iface> void getRemoteObject(final int objectId, final RemoteObjectCallback<Iface> callback) {

    }

    @Override
    public
    ConnectionNoOpSupport rmiSupport() {
        return null;
    }

    @Override
    public
    long nextGcmSequence() {
        return 0;
    }

    @Override
    public
    SecretKey cryptoKey() {
        return null;
    }
}
