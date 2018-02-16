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

import java.net.SocketException;
import java.nio.channels.DatagramChannel;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.socket.ServerSocketChannelConfig;

/**
 * This is a basic implementation of a ChannelConfig, with the exception that we take in a DatagramSessionChannel, and modify only the
 * options of that channel that make sense
 */
public final
class NioServerDatagramChannelConfig extends DefaultChannelConfig implements ServerSocketChannelConfig {
    private final DatagramChannel datagramChannel;

    public
    NioServerDatagramChannelConfig(NioServerDatagramChannel channel, DatagramChannel datagramChannel) {
        super(channel);
        this.datagramChannel = datagramChannel;
    }

    @Override
    public
    int getBacklog() {
        return 1;
    }

    @Override
    public
    ServerSocketChannelConfig setBacklog(final int backlog) {
        return this;
    }

    @Override
    public
    boolean isReuseAddress() {
        try {
            return datagramChannel.socket()
                                  .getReuseAddress();
        } catch (SocketException e) {
            throw new ChannelException(e);
        }
    }

    @Override
    public
    ServerSocketChannelConfig setReuseAddress(final boolean reuseAddress) {
        try {
            datagramChannel.socket()
                           .setReuseAddress(true);
        } catch (SocketException e) {
            throw new ChannelException(e);
        }

        return this;
    }

    @Override
    public
    int getReceiveBufferSize() {
        try {
            return datagramChannel.socket()
                                  .getReceiveBufferSize();
        } catch (SocketException e) {
            throw new ChannelException(e);
        }
    }

    @Override
    public
    ServerSocketChannelConfig setReceiveBufferSize(final int receiveBufferSize) {
        try {
            datagramChannel.socket()
                           .setReceiveBufferSize(receiveBufferSize);
        } catch (SocketException e) {
            throw new ChannelException(e);
        }

        return this;
    }

    @Override
    public
    ServerSocketChannelConfig setPerformancePreferences(final int connectionTime, final int latency, final int bandwidth) {
        return this;
    }

    @Override
    public
    ServerSocketChannelConfig setConnectTimeoutMillis(int timeout) {
        return this;
    }

    @Override
    @Deprecated
    public
    ServerSocketChannelConfig setMaxMessagesPerRead(int n) {
        super.setMaxMessagesPerRead(n);
        return this;
    }

    @Override
    public
    ServerSocketChannelConfig setWriteSpinCount(int spincount) {
        super.setWriteSpinCount(spincount);
        return this;
    }

    @Override
    public
    ServerSocketChannelConfig setAllocator(ByteBufAllocator alloc) {
        super.setAllocator(alloc);
        return this;
    }

    @Override
    public
    ServerSocketChannelConfig setRecvByteBufAllocator(RecvByteBufAllocator alloc) {
        super.setRecvByteBufAllocator(alloc);
        return this;
    }

    @Override
    public
    ServerSocketChannelConfig setAutoRead(boolean autoread) {
        super.setAutoRead(autoread);
        return this;
    }

    @Override
    public
    ServerSocketChannelConfig setWriteBufferHighWaterMark(int writeBufferHighWaterMark) {
        return (ServerSocketChannelConfig) super.setWriteBufferHighWaterMark(writeBufferHighWaterMark);
    }

    @Override
    public
    ServerSocketChannelConfig setWriteBufferLowWaterMark(int writeBufferLowWaterMark) {
        return (ServerSocketChannelConfig) super.setWriteBufferLowWaterMark(writeBufferLowWaterMark);
    }

    @Override
    public
    ServerSocketChannelConfig setWriteBufferWaterMark(WriteBufferWaterMark writeBufferWaterMark) {
        return (ServerSocketChannelConfig) super.setWriteBufferWaterMark(writeBufferWaterMark);
    }

    @Override
    public
    ServerSocketChannelConfig setMessageSizeEstimator(MessageSizeEstimator est) {
        super.setMessageSizeEstimator(est);
        return this;
    }
}
