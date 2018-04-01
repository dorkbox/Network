/*
 * Copyright 2018 dorkbox, llc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package dorkbox.network.connection.registration;

import org.bouncycastle.crypto.params.ParametersWithIV;

import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.ConnectionPoint;
import dorkbox.network.connection.CryptoConnection;
import dorkbox.network.connection.EndPoint;
import dorkbox.network.connection.Listeners;
import dorkbox.network.connection.bridge.ConnectionBridge;
import dorkbox.network.connection.idle.IdleBridge;
import dorkbox.network.connection.idle.IdleSender;
import dorkbox.network.rmi.RemoteObject;
import dorkbox.network.rmi.RemoteObjectCallback;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;

/**
 * A wrapper for the period of time between registration and connect for a "connection" session.
 *
 * This is to prevent race conditions where onMessage() can happen BEFORE a "connection" is "connected"
 */
public
class ConnectionWrapper implements CryptoConnection, ChannelHandler {
    public final ConnectionImpl connection;

    public
    ConnectionWrapper(final ConnectionImpl connection) {
        this.connection = connection;
    }

    @Override
    public
    void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
    }

    @Override
    public
    void handlerRemoved(final ChannelHandlerContext ctx) throws Exception {
    }

    @Override
    public
    void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
    }


    @Override
    public
    long getNextGcmSequence() {
        return connection.getNextGcmSequence();
    }

    @Override
    public
    ParametersWithIV getCryptoParameters() {
        return connection.getCryptoParameters();
    }

    @Override
    public
    RemoteObject getProxyObject(final int objectID, final Class<?> iFace) {
        throw new IllegalArgumentException("not implemented");
    }

    @Override
    public
    Object getImplementationObject(final int objectID) {
        throw new IllegalArgumentException("not implemented");
    }

    @Override
    public
    <T> int getRegisteredId(final T object) {
        return 0;
    }

    @Override
    public
    boolean hasRemoteKeyChanged() {
        throw new IllegalArgumentException("not implemented");
    }

    @Override
    public
    String getRemoteHost() {
        throw new IllegalArgumentException("not implemented");
    }

    @Override
    public
    boolean isLoopback() {
        return connection.isLoopback();
    }

    @Override
    public
    EndPoint getEndPoint() {
        throw new IllegalArgumentException("not implemented");
    }

    @Override
    public
    int id() {
        throw new IllegalArgumentException("not implemented");
    }

    @Override
    public
    String idAsHex() {
        throw new IllegalArgumentException("not implemented");
    }

    @Override
    public
    boolean hasUDP() {
        throw new IllegalArgumentException("not implemented");
    }

    @Override
    public
    ConnectionBridge send() {
        throw new IllegalArgumentException("not implemented");
    }

    @Override
    public
    ConnectionPoint send(final Object message) {
        throw new IllegalArgumentException("not implemented");
    }

    @Override
    public
    IdleBridge sendOnIdle(final IdleSender<?, ?> sender) {
        throw new IllegalArgumentException("not implemented");
    }

    @Override
    public
    IdleBridge sendOnIdle(final Object message) {
        throw new IllegalArgumentException("not implemented");
    }

    @Override
    public
    Listeners listeners() {
        throw new IllegalArgumentException("not implemented");
    }

    @Override
    public
    void close() {
        throw new IllegalArgumentException("not implemented");
    }

    @Override
    public
    void closeAsap() {
        throw new IllegalArgumentException("not implemented");
    }

    @Override
    public
    <Iface> void createRemoteObject(final Class<Iface> interfaceClass, final RemoteObjectCallback<Iface> callback) {
        throw new IllegalArgumentException("not implemented");
    }

    @Override
    public
    <Iface> void getRemoteObject(final int objectId, final RemoteObjectCallback<Iface> callback) {
        throw new IllegalArgumentException("not implemented");
    }
}
