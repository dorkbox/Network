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
package io.netty.bootstrap;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.RecyclableArrayList;

public
class DatagramSessionChannel extends AbstractChannel implements Channel {

    private
    class ChannelUnsafe extends AbstractUnsafe {
        @Override
        public
        void connect(SocketAddress socketAddress, SocketAddress socketAddress2, ChannelPromise channelPromise) {
            // Connect not supported by ServerChannel implementations
            channelPromise.setFailure(new UnsupportedOperationException());
        }
    }


    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);
    private final DatagramSessionChannelConfig config;


    private SessionManager sessionManager;
    private InetSocketAddress localAddress;
    private InetSocketAddress remoteAddress;

    private volatile boolean isOpen = true;

    DatagramSessionChannel(final Channel parentChannel,
                           final SessionManager sessionManager,
                           final DatagramSessionChannelConfig sessionConfig,
                           final InetSocketAddress localAddress,
                           final InetSocketAddress remoteAddress) {
        super(parentChannel);

        this.sessionManager = sessionManager;
        this.config = sessionConfig;

        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
    }

    @Override
    public
    ChannelConfig config() {
        return config;
    }

    @Override
    protected
    void doBeginRead() throws Exception {
    }

    @Override
    protected
    void doBind(SocketAddress addr) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    protected
    void doClose() throws Exception {
        isOpen = false;
        sessionManager.doCloseChannel(this);
    }

    @Override
    protected
    void doDisconnect() throws Exception {
        doClose();
    }

    @Override
    protected
    void doWrite(ChannelOutboundBuffer buffer) throws Exception {
        //transfer all messages that are ready to be written to list
        final RecyclableArrayList list = RecyclableArrayList.newInstance();
        boolean free = true;

        try {
            DatagramPacket buf = null;
            while ((buf = (DatagramPacket) buffer.current()) != null) {
                list.add(buf.retain());
                buffer.remove();
            }
            free = false;
        } finally {
            if (free) {
                for (Object obj : list) {
                    ReferenceCountUtil.safeRelease(obj);
                }
                list.recycle();
            }
        }

        //schedule a task that will write those entries
        EventLoop eventLoop = parent().eventLoop();
        if (eventLoop.inEventLoop()) {
            write0(list);
        }
        else {
            eventLoop.submit(new Runnable() {
                @Override
                public
                void run() {
                    write0(list);
                }
            });
        }
    }

    @Override
    public
    boolean isActive() {
        return isOpen;
    }

    @Override
    protected
    boolean isCompatible(EventLoop eventloop) {
        // compatible with all Datagram event loops where we are explicitly used
        return true;
    }

    @Override
    public
    boolean isOpen() {
        return isOpen;
    }

    @Override
    public
    InetSocketAddress localAddress() {
        return (InetSocketAddress) localAddress0();
    }

    @Override
    protected
    SocketAddress localAddress0() {
        return localAddress;
    }

    @Override
    public
    ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    protected
    AbstractUnsafe newUnsafe() {
        // cannot connect, so we make this be an error if we try.
        return new ChannelUnsafe();
    }

    @Override
    public
    InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    @Override
    protected
    InetSocketAddress remoteAddress0() {
        return remoteAddress;
    }

    private
    void write0(final RecyclableArrayList list) {
        try {
            Unsafe unsafe = super.parent().unsafe();

            for (Object buf : list) {
                unsafe.write(buf, voidPromise());
            }

            unsafe.flush();
        } finally {
            list.recycle();
        }
    }
}
