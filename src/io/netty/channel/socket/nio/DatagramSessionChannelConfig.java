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

import java.util.Map;

import dorkbox.network.connection.EndPoint;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramChannelConfig;

/**
 * The default {@link DatagramChannelConfig} implementation.
 */
public class DatagramSessionChannelConfig implements ChannelConfig {
    private static final MessageSizeEstimator DEFAULT_MSG_SIZE_ESTIMATOR = DefaultMessageSizeEstimator.DEFAULT;


    private volatile MessageSizeEstimator msgSizeEstimator = DEFAULT_MSG_SIZE_ESTIMATOR;

    private final NioServerDatagramChannel serverDatagramSessionChannel;

    /**
     * Creates a new instance.
     */
    public
    DatagramSessionChannelConfig(DatagramSessionChannel channel, final NioServerDatagramChannel serverDatagramSessionChannel) {
        this.serverDatagramSessionChannel = serverDatagramSessionChannel;
    }

    @Override
    public
    Map<ChannelOption<?>, Object> getOptions() {
        return null;
    }

    @Override
    public
    boolean setOptions(final Map<ChannelOption<?>, ?> options) {
        return false;
    }

    @Override
    public
    <T> T getOption(final ChannelOption<T> option) {
        return serverDatagramSessionChannel.config().getOption(option);
    }

    @Override
    public
    <T> boolean setOption(final ChannelOption<T> option, final T value) {
        return false;
    }

    @Override
    public
    int getConnectTimeoutMillis() {
        return 0;
    }

    @Override
    public
    ChannelConfig setConnectTimeoutMillis(final int connectTimeoutMillis) {
        return this;
    }

    @Override
    public
    int getMaxMessagesPerRead() {
        return 0;
    }

    @Override
    public
    ChannelConfig setMaxMessagesPerRead(final int maxMessagesPerRead) {
        return this;
    }

    @Override
    public
    int getWriteSpinCount() {
        return 0;
    }

    @Override
    public
    ChannelConfig setWriteSpinCount(final int writeSpinCount) {
        return this;
    }

    @Override
    public
    ByteBufAllocator getAllocator() {
        return serverDatagramSessionChannel.config()
                                           .getAllocator();
    }

    @Override
    public
    ChannelConfig setAllocator(final ByteBufAllocator allocator) {
        return this;
    }

    @Override
    public
    <T extends RecvByteBufAllocator> T getRecvByteBufAllocator() {
        return serverDatagramSessionChannel.config()
                                           .getRecvByteBufAllocator();
    }

    @Override
    public
    ChannelConfig setRecvByteBufAllocator(final RecvByteBufAllocator allocator) {
        return this;
    }

    @Override
    public
    boolean isAutoRead() {
        // we implement our own reading from within the DatagramServer context.
        return false;
    }

    @Override
    public
    ChannelConfig setAutoRead(final boolean autoRead) {
        return this;
    }

    @Override
    public
    boolean isAutoClose() {
        return false;
    }

    @Override
    public
    ChannelConfig setAutoClose(final boolean autoClose) {
        return this;
    }

    @Override
    public
    int getWriteBufferHighWaterMark() {
        return EndPoint.udpMaxSize;
    }

    @Override
    public
    ChannelConfig setWriteBufferHighWaterMark(final int writeBufferHighWaterMark) {
        return this;
    }

    @Override
    public
    int getWriteBufferLowWaterMark() {
        return 0;
    }

    @Override
    public
    ChannelConfig setWriteBufferLowWaterMark(final int writeBufferLowWaterMark) {
        return this;
    }

    @Override
    public
    MessageSizeEstimator getMessageSizeEstimator() {
        return msgSizeEstimator;
    }

    @Override
    public
    ChannelConfig setMessageSizeEstimator(final MessageSizeEstimator estimator) {
        this.msgSizeEstimator = estimator;
        return this;
    }

    @Override
    public
    WriteBufferWaterMark getWriteBufferWaterMark() {
        return null;
    }

    @Override
    public
    ChannelConfig setWriteBufferWaterMark(final WriteBufferWaterMark writeBufferWaterMark) {
        return this;
    }
}
