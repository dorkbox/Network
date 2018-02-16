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
package io.netty.channel.socket.nio;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.AbstractNioChannel.NioUnsafe;
import io.netty.channel.nio.NioEventLoop;
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
    protected final DatagramSessionChannelConfig config;


    protected final NioServerDatagramChannel serverChannel;
    protected final InetSocketAddress remote;

    private volatile boolean isOpen = true;
    private ByteBuf buffer;

    protected
    DatagramSessionChannel(NioServerDatagramChannel serverChannel, InetSocketAddress remote) {
        super(serverChannel);
        this.serverChannel = serverChannel;
        this.remote = remote;

        config = new DatagramSessionChannelConfig(this, serverChannel);
    }

    @Override
    public
    ChannelConfig config() {
        return config;
    }

    @Override
    protected
    void doBeginRead() throws Exception {
        // a single packet is 100% of our data, so we cannot have multiple reads (there is no "session" for UDP)
        ChannelPipeline pipeline = pipeline();

        pipeline.fireChannelRead(buffer);
        pipeline.fireChannelReadComplete();
        buffer = null;
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
        serverChannel.doCloseChannel(this);
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
        NioEventLoop eventLoop = serverChannel.eventLoop();
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
        return eventloop instanceof NioEventLoop;
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
        return serverChannel.localAddress0();
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
        return remote;
    }

    @Override
    protected
    InetSocketAddress remoteAddress0() {
        return remote;
    }

    public
    void setBuffer(final ByteBuf buffer) {
        this.buffer = buffer;
    }

    private
    void write0(final RecyclableArrayList list) {
        try {
            NioUnsafe unsafe = serverChannel.unsafe();

            for (Object buf : list) {
                unsafe.write(buf, voidPromise());
            }

            unsafe.flush();
        } finally {
            list.recycle();
        }
    }
}
