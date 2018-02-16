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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.List;

import dorkbox.network.pipeline.discovery.BroadcastServer;
import dorkbox.util.bytes.BigEndian.Long_;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.AbstractNioMessageChannel;
import io.netty.channel.socket.*;
import io.netty.util.collection.LongObjectHashMap;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SocketUtils;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * An NIO datagram {@link Channel} that sends and receives an
 * {@link AddressedEnvelope AddressedEnvelope<ByteBuf, SocketAddress>}.
 *
 * @see AddressedEnvelope
 * @see DatagramPacket
 */
public final
class NioServerDatagramChannel extends AbstractNioMessageChannel implements ServerSocketChannel {

    private static final SelectorProvider DEFAULT_SELECTOR_PROVIDER = SelectorProvider.provider();
    private static final ChannelMetadata METADATA = new ChannelMetadata(true, 16);

    private static final String EXPECTED_TYPES = " (expected: " +
        StringUtil.simpleClassName(DatagramPacket.class) + ", " +
        StringUtil.simpleClassName(AddressedEnvelope.class) + '<' +
            StringUtil.simpleClassName(ByteBuf.class) + ", " +
            StringUtil.simpleClassName(SocketAddress.class) + ">, " +
        StringUtil.simpleClassName(ByteBuf.class) + ')';

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioServerDatagramChannel.class);


    private static
    java.nio.channels.DatagramChannel newSocket(SelectorProvider provider) {
        try {
            /**
             *  Use the {@link SelectorProvider} to open {@link SocketChannel} and so remove condition in
             *  {@link SelectorProvider#provider()} which is called by each DatagramSessionChannel.open() otherwise.
             *
             *  See <a href="https://github.com/netty/netty/issues/2308">#2308</a>.
             */
            return provider.openDatagramChannel();
        } catch (IOException e) {
            throw new ChannelException("Failed to open a socket.", e);
        }
    }

    private static
    java.nio.channels.DatagramChannel newSocket(SelectorProvider provider, InternetProtocolFamily ipFamily) {
        if (ipFamily == null) {
            return newSocket(provider);
        }

        checkJavaVersion();

        try {
            return NioServerDatagramChannel7.newSocket(provider, ipFamily);
        } catch (IOException e) {
            throw new ChannelException("Failed to open a socket.", e);
        }
    }

    private static
    void checkJavaVersion() {
        if (PlatformDependent.javaVersion() < 7) {
            throw new UnsupportedOperationException("Only supported on java 7+.");
        }
    }

    /**
     * Checks if the specified buffer is a direct buffer and is composed of a single NIO buffer.
     * (We check this because otherwise we need to make it a non-composite buffer.)
     */
    private static
    boolean isSingleDirectBuffer(ByteBuf buf) {
        return buf.isDirect() && buf.nioBufferCount() == 1;
    }

    private static
    long getChannelId(final InetSocketAddress remoteAddress) {
        int address = remoteAddress.getAddress()
                                   .hashCode(); // we want it as an int
        int port = remoteAddress.getPort(); // this is really just 2 bytes

        byte[] combined = new byte[8];
        combined[0] = (byte) ((port >>> 24) & 0xFF);
        combined[1] = (byte) ((port >>> 16) & 0xFF);
        combined[2] = (byte) ((port >>> 8) & 0xFF);
        combined[3] = (byte) ((port) & 0xFF);
        combined[4] = (byte) ((address >>> 24) & 0xFF);
        combined[5] = (byte) ((address >>> 16) & 0xFF);
        combined[6] = (byte) ((address >>> 8) & 0xFF);
        combined[7] = (byte) ((address) & 0xFF);

        return Long_.from(combined);
    }
    private final ServerSocketChannelConfig config;


    // Does not need to be thread safe, because access only happens in the event loop
    private final LongObjectHashMap<DatagramSessionChannel> datagramChannels = new LongObjectHashMap<DatagramSessionChannel>();
    private BroadcastServer broadcastServer;

    /**
     * Create a new instance which will use the Operation Systems default {@link InternetProtocolFamily}.
     */
    public
    NioServerDatagramChannel() {
        this(newSocket(DEFAULT_SELECTOR_PROVIDER));
    }

    /**
     * Create a new instance using the given {@link SelectorProvider}
     * which will use the Operation Systems default {@link InternetProtocolFamily}.
     */
    public
    NioServerDatagramChannel(SelectorProvider provider) {
        this(newSocket(provider));
    }

    /**
     * Create a new instance using the given {@link InternetProtocolFamily}. If {@code null} is used it will depend
     * on the Operation Systems default which will be chosen.
     */
    public
    NioServerDatagramChannel(InternetProtocolFamily ipFamily) {
        this(newSocket(DEFAULT_SELECTOR_PROVIDER, ipFamily));
    }

    /**
     * Create a new instance using the given {@link SelectorProvider} and {@link InternetProtocolFamily}.
     * If {@link InternetProtocolFamily} is {@code null} it will depend on the Operation Systems default
     * which will be chosen.
     */
    public
    NioServerDatagramChannel(SelectorProvider provider, InternetProtocolFamily ipFamily) {
        this(newSocket(provider, ipFamily));
    }

    /**
     * Create a new instance from the given {@link java.nio.channels.DatagramChannel}.
     */
    public
    NioServerDatagramChannel(java.nio.channels.DatagramChannel socket) {
        super(null, socket, SelectionKey.OP_READ);
        config = new NioServerDatagramChannelConfig(this, socket);
        broadcastServer = new BroadcastServer();
    }

    void clearReadPending0() {
        clearReadPending();
    }

    @Override
    protected
    boolean closeOnReadError(Throwable cause) {
        // We do not want to close on SocketException when using DatagramSessionChannel as we usually can continue receiving.
        // See https://github.com/netty/netty/issues/5893
        if (cause instanceof SocketException) {
            return false;
        }
        return super.closeOnReadError(cause);
    }

    @Override
    public
    ServerSocketChannelConfig config() {
        return config;
    }

    @Override
    protected
    boolean continueOnWriteError() {
        // Continue on write error as a DatagramSessionChannel can write to multiple remote peers
        //
        // See https://github.com/netty/netty/issues/2665
        return true;
    }

    @Override
    protected
    void doBind(SocketAddress localAddress) throws Exception {
        doBind0(localAddress);
    }

    private
    void doBind0(SocketAddress localAddress) throws Exception {
        if (PlatformDependent.javaVersion() >= 7) {
            SocketUtils.bind(javaChannel(), localAddress);
        }
        else {
            javaChannel().socket()
                         .bind(localAddress);
        }
    }

    // Always called from the EventLoop
    @Override
    protected
    void doClose() throws Exception {
        // have to close all of the fake DatagramChannels as well. Each one will remove itself from the channel map.

        // We make a copy of this b/c of concurrent modification, in the event this is closed BEFORE the child-channels are closed
        ArrayList<DatagramSessionChannel> channels = new ArrayList<DatagramSessionChannel>(datagramChannels.values());
        for (DatagramSessionChannel datagramSessionChannel : channels) {
            datagramSessionChannel.close();
        }

        javaChannel().close();
    }

    /**
     * ADDED to support closing a DatagramSessionChannel. Always called from the EventLoop
     */
    public
    void doCloseChannel(final DatagramSessionChannel datagramSessionChannel) {
        InetSocketAddress remoteAddress = datagramSessionChannel.remoteAddress();
        long channelId = getChannelId(remoteAddress);

        datagramChannels.remove(channelId);
    }

    @Override
    protected
    boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        // Unnecessary stuff
        throw new UnsupportedOperationException();
    }

    @Override
    protected
    void doDisconnect() throws Exception {
        // Unnecessary stuff
        throw new UnsupportedOperationException();
    }

    @Override
    protected
    void doFinishConnect() throws Exception {
        // Unnecessary stuff
        throw new UnsupportedOperationException();
    }


    @Override
    protected
    int doReadMessages(List<Object> buf) throws Exception {
        java.nio.channels.DatagramChannel ch = javaChannel();
        ServerSocketChannelConfig config = config();
        RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();
        ChannelPipeline pipeline = pipeline();

        ByteBuf data = allocHandle.allocate(config.getAllocator());
        allocHandle.attemptedBytesRead(data.writableBytes());
        boolean free = true;
        try {
            ByteBuffer nioData = data.internalNioBuffer(data.writerIndex(), data.writableBytes());
            int pos = nioData.position();
            InetSocketAddress remoteAddress = (InetSocketAddress) ch.receive(nioData);
            if (remoteAddress == null) {
                return 0;
            }

            allocHandle.lastBytesRead(nioData.position() - pos);
            ByteBuf byteBuf = data.writerIndex(data.writerIndex() + allocHandle.lastBytesRead());
            // original behavior from NioDatagramChannel.
            // buf.add(new DatagramPacket(byteBuf, localAddress(), remoteAddress));
            // free = false;
            // return 1;



            // new behavior

            // check to see if it's a broadcast packet or not
            ByteBuf broadcast = broadcastServer.getBroadcastResponse(byteBuf, remoteAddress);
            if (broadcast != null) {
                // don't bother creating channels if this is a broadcast event. Just respond and be finished
                doWriteBytes(broadcast, remoteAddress);
                // no messages created (since we directly write to the channel).
                return 0;
            }


            long channelId = getChannelId(remoteAddress);

            // create a new channel or reuse existing one
            DatagramSessionChannel channel = datagramChannels.get(channelId);
            if (channel == null) {
                try {
                    channel = new DatagramSessionChannel(this, remoteAddress);
                    datagramChannels.put(channelId, channel);

                    // This channel is registered automatically AFTER this read method completes
                } catch (Throwable t) {
                    logger.warn("Failed to create a new datagram channel from a read operation.", t);

                    try {
                        channel.close();
                    } catch (Throwable t2) {
                        logger.warn("Failed to close the datagram channel.", t2);
                    }

                    return 0;
                }
            }

            // set the bytes of the datagram channel
            channel.setBuffer(byteBuf);

            pipeline.fireChannelRead(channel);

            // immediately trigger a read
            channel.read();

            free = false;
            // we manually fireChannelRead + read (caller class calls readComplete for us)
            return 0;
        } catch (Throwable cause) {
            PlatformDependent.throwException(cause);
            return -1; // -1 means to close this channel
        } finally {
            if (free) {
                data.release();
            }
        }
    }

    @Override
    protected
    boolean doWriteMessage(Object msg, ChannelOutboundBuffer in) throws Exception {
        final SocketAddress remoteAddress;
        final ByteBuf data;
        if (msg instanceof AddressedEnvelope) {
            @SuppressWarnings("unchecked")
            AddressedEnvelope<ByteBuf, SocketAddress> envelope = (AddressedEnvelope<ByteBuf, SocketAddress>) msg;
            remoteAddress = envelope.recipient();
            data = envelope.content();
        }
        else {
            data = (ByteBuf) msg;
            remoteAddress = null;
        }

        return doWriteBytes(data, remoteAddress);
    }

    private
    boolean doWriteBytes(final ByteBuf data, final SocketAddress remoteAddress) throws IOException {
        final int dataLen = data.readableBytes();
        if (dataLen == 0) {
            return true;
        }

        final ByteBuffer nioData = data.internalNioBuffer(data.readerIndex(), dataLen);
        final int writtenBytes;
        if (remoteAddress != null) {
            writtenBytes = javaChannel().send(nioData, remoteAddress);
        }
        else {
            writtenBytes = javaChannel().write(nioData);
        }
        return writtenBytes > 0;
    }

    @Override
    protected
    Object filterOutboundMessage(Object msg) {
        if (msg instanceof DatagramPacket) {
            DatagramPacket p = (DatagramPacket) msg;
            ByteBuf content = p.content();
            if (isSingleDirectBuffer(content)) {
                return p;
            }
            return new DatagramPacket(newDirectBuffer(p, content), p.recipient());
        }

        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            if (isSingleDirectBuffer(buf)) {
                return buf;
            }
            return newDirectBuffer(buf);
        }

        if (msg instanceof AddressedEnvelope) {
            @SuppressWarnings("unchecked")
            AddressedEnvelope<Object, SocketAddress> e = (AddressedEnvelope<Object, SocketAddress>) msg;
            if (e.content() instanceof ByteBuf) {
                ByteBuf content = (ByteBuf) e.content();
                if (isSingleDirectBuffer(content)) {
                    return e;
                }
                return new DefaultAddressedEnvelope<ByteBuf, SocketAddress>(newDirectBuffer(e, content), e.recipient());
            }
        }

        throw new UnsupportedOperationException("unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
    }

    @Override
    @SuppressWarnings("deprecation")
    public
    boolean isActive() {
        java.nio.channels.DatagramChannel ch = javaChannel();
        // we do not support registration options
        // return ch.isOpen() && (config.getOption(ChannelOption.DATAGRAM_CHANNEL_ACTIVE_ON_REGISTRATION) && isRegistered() || ch.socket().isBound());
        return ch.isOpen() || ch.socket()
                                .isBound();
    }

    @Override
    protected
    java.nio.channels.DatagramChannel javaChannel() {
        return (java.nio.channels.DatagramChannel) super.javaChannel();
    }

    @Override
    public
    InetSocketAddress localAddress() {
        return (InetSocketAddress) super.localAddress();
    }

    @Override
    protected
    SocketAddress localAddress0() {
        return javaChannel().socket()
                            .getLocalSocketAddress();
    }

    @Override
    public
    ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    public
    InetSocketAddress remoteAddress() {
        return null;
    }

    @Override
    protected
    SocketAddress remoteAddress0() {
        return null;
    }

    @Override
    @Deprecated
    protected
    void setReadPending(boolean readPending) {
        super.setReadPending(readPending);
    }

    @Override
    public
    String toString() {
        return "NioServerDatagramChannel";
    }
}
