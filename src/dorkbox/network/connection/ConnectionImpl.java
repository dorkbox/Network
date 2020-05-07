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

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.SecretKey;

import dorkbox.network.Client;
import dorkbox.network.connection.bridge.ConnectionBridge;
import dorkbox.network.connection.idle.IdleBridge;
import dorkbox.network.connection.idle.IdleSender;
import dorkbox.network.connection.idle.IdleSenderFactory;
import dorkbox.network.connection.ping.PingFuture;
import dorkbox.network.connection.ping.PingMessage;
import dorkbox.network.connection.ping.PingTuple;
import dorkbox.network.connection.wrapper.ChannelNetworkWrapper;
import dorkbox.network.connection.wrapper.ChannelNull;
import dorkbox.network.connection.wrapper.ChannelWrapper;
import dorkbox.network.rmi.ConnectionNoOpSupport;
import dorkbox.network.rmi.ConnectionRmiLocalSupport;
import dorkbox.network.rmi.ConnectionRmiNetworkSupport;
import dorkbox.network.rmi.ConnectionRmiSupport;
import dorkbox.network.rmi.RemoteObjectCallback;
import io.netty.bootstrap.DatagramSessionChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.kqueue.KQueueDatagramChannel;
import io.netty.channel.kqueue.KQueueSocketChannel;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.socket.oio.OioDatagramChannel;
import io.netty.channel.socket.oio.OioSocketChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Promise;


/**
 * The "network connection" is established once the registration is validated for TCP/UDP
 */
@SuppressWarnings("unused")
@Sharable
public
class ConnectionImpl extends ChannelInboundHandlerAdapter implements Connection_, Listeners, ConnectionBridge {
    public static
    boolean isTcpChannel(Class<? extends Channel> channelClass) {
        return channelClass == OioSocketChannel.class ||
               channelClass == NioSocketChannel.class ||
               channelClass == KQueueSocketChannel.class ||
               channelClass == EpollSocketChannel.class;
    }

    public static
    boolean isUdpChannel(Class<? extends Channel> channelClass) {
        return channelClass == OioDatagramChannel.class ||
               channelClass == NioDatagramChannel.class ||
               channelClass == KQueueDatagramChannel.class ||
               channelClass == EpollDatagramChannel.class ||
               channelClass == DatagramSessionChannel.class;
    }

    public static
    boolean isLocalChannel(Class<? extends Channel> channelClass) {
        return channelClass == LocalChannel.class;
    }


    private final org.slf4j.Logger logger;

    private final AtomicBoolean needsLock = new AtomicBoolean(false);
    private final AtomicBoolean writeSignalNeeded = new AtomicBoolean(false);
    private final Object writeLock = new Object();

    private final AtomicBoolean closeInProgress = new AtomicBoolean(false);
    private final AtomicBoolean channelIsClosed = new AtomicBoolean(false);

    private final Object messageInProgressLock = new Object();
    private final AtomicBoolean messageInProgress = new AtomicBoolean(false);

    private final ISessionManager sessionManager;
    private final ChannelWrapper channelWrapper;

    private volatile PingFuture pingFuture = null;

    // used to store connection local listeners (instead of global listeners). Only possible on the server.
    private volatile ConnectionManager localListenerManager;

    // while on the CLIENT, if the SERVER's ecc key has changed, the client will abort and show an error.
    private boolean remoteKeyChanged;

    private final EndPoint endPoint;

    // when true, the connection will be closed (either as RMI or as 'normal' listener execution) when the thread execution returns control
    // back to the network stack
    private boolean closeAsap = false;

    // The IV for AES-GCM must be 12 bytes, since it's 4 (salt) + 8 (external counter) + 4 (GCM counter)
    // The 12 bytes IV is created during connection registration, and during the AES-GCM crypto, we override the last 8 with this
    // counter, which is also transmitted as an optimized int. (which is why it starts at 0, so the transmitted bytes are small)
    private final AtomicLong aes_gcm_iv = new AtomicLong(0);


    // when closing this connection, HOW MANY endpoints need to be closed?
    private CountDownLatch closeLatch;


    // RMI support for this connection
    final ConnectionRmiSupport rmiSupport;

    /**
     * All of the parameters can be null, when metaChannel wants to get the base class type
     */
    public
    ConnectionImpl(final EndPoint endPoint, final ChannelWrapper channelWrapper) {
        this.endPoint = endPoint;

        if (endPoint != null) {
            this.channelWrapper = channelWrapper;
            this.logger = endPoint.logger;
            this.sessionManager = endPoint.connectionManager;

            boolean isNetworkChannel = this.channelWrapper instanceof ChannelNetworkWrapper;

            if (endPoint.rmiEnabled) {
                if (isNetworkChannel) {
                    // because this is PER CONNECTION, there is no need for synchronize(), since there will not be any issues with concurrent access, but
                    // there WILL be issues with thread visibility because a different worker thread can be called for different connections
                    this.rmiSupport = new ConnectionRmiNetworkSupport(this, endPoint.rmiGlobalBridge);
                }
                else {
                    // because this is PER CONNECTION, there is no need for synchronize(), since there will not be any issues with concurrent access, but
                    // there WILL be issues with thread visibility because a different worker thread can be called for different connections
                    this.rmiSupport = new ConnectionRmiLocalSupport(this, endPoint.rmiGlobalBridge);
                }
            } else {
                this.rmiSupport = new ConnectionNoOpSupport();
            }


            if (isNetworkChannel) {
                this.remoteKeyChanged = ((ChannelNetworkWrapper) channelWrapper).remoteKeyChanged();

                int count = 0;
                if (channelWrapper.tcp() != null) {
                    count++;
                }

                if (channelWrapper.udp() != null) {
                    count++;
                }

                // when closing this connection, HOW MANY endpoints need to be closed?
                this.closeLatch = new CountDownLatch(count);
            }
            else {
                this.remoteKeyChanged = false;

                // when closing this connection, HOW MANY endpoints need to be closed?
                this.closeLatch = new CountDownLatch(1);
            }

        } else {
            this.logger = null;
            this.sessionManager = null;
            this.channelWrapper = null;
            this.rmiSupport = new ConnectionNoOpSupport();
        }
    }

    /**
     * @return the AES key. key=32 byte, iv=12 bytes (AES-GCM implementation).
     */
    @Override
    public final
    SecretKey cryptoKey() {
        return this.channelWrapper.cryptoKey();
    }

    /**
     * This is the per-message sequence number.
     *
     *  The IV for AES-GCM must be 12 bytes, since it's 4 (salt) + 4 (external counter) + 4 (GCM counter)
     *  The 12 bytes IV is created during connection registration, and during the AES-GCM crypto, we override the last 8 with this
     *  counter, which is also transmitted as an optimized int. (which is why it starts at 0, so the transmitted bytes are small)
     */
    @Override
    public final
    long nextGcmSequence() {
        return aes_gcm_iv.getAndIncrement();
    }


    /**
     * Has the remote ECC public key changed. This can be useful if specific actions are necessary when the key has changed.
     */
    @Override
    public
    boolean hasRemoteKeyChanged() {
        return this.remoteKeyChanged;
    }

    /**
     * @return the remote address, as a string.
     */
    @Override
    public
    String getRemoteHost() {
        return this.channelWrapper.getRemoteHost();
    }

    /**
     * @return true if this connection is established on the loopback interface
     */
    @Override
    public
    boolean isLoopback() {
        return channelWrapper.isLoopback();
    }

    /**
     * @return the endpoint associated with this connection
     */
    @Override
    public
    EndPoint getEndPoint() {
        return this.endPoint;
    }

    /**
     * @return the connection (TCP or LOCAL) id of this connection.
     */
    @Override
    public
    int id() {
        return this.channelWrapper.id();
    }

    /**
     * @return the connection (TCP or LOCAL) id of this connection as a HEX string.
     */
    @Override
    public
    String idAsHex() {
        return Integer.toHexString(id());
    }

    /**
     * Updates the ping times for this connection (called when this connection gets a REPLY ping message).
     */
    public final
    void updatePingResponse(PingMessage ping) {
        if (this.pingFuture != null) {
            this.pingFuture.setSuccess(this, ping);
        }
    }

    /**
     * Sends a "ping" packet, trying UDP then TCP (in that order) to measure <b>ROUND TRIP</b> time to the remote connection.
     *
     * @return Ping can have a listener attached, which will get called when the ping returns.
     */
    @Override
    public final
    Ping ping() {
        PingFuture pingFuture2 = this.pingFuture;
        if (pingFuture2 != null && !pingFuture2.isSuccess()) {
            pingFuture2.cancel();
        }

        Promise<PingTuple<? extends Connection>> newPromise;
        if (this.channelWrapper.udp() != null) {
            newPromise = this.channelWrapper.udp()
                                            .newPromise();
        }
        else {
            newPromise = this.channelWrapper.tcp()
                                            .newPromise();
        }

        this.pingFuture = new PingFuture(newPromise);

        PingMessage ping = new PingMessage();
        ping.id = this.pingFuture.getId();
        ping0(ping);

        return this.pingFuture;
    }

    /**
     * INTERNAL USE ONLY. Used to initiate a ping, and to return a ping.
     *
     * Sends a ping message attempted in the following order: UDP, TCP,LOCAL
     */
    public final
    void ping0(PingMessage ping) {
        if (this.channelWrapper.udp() != null) {
            UDP(ping).flush();
        }
        else if (this.channelWrapper.tcp() != null) {
            TCP(ping).flush();
        }
        else {
            self(ping);
        }
    }

    /**
     * Returns the last calculated TCP return trip time, or -1 if or the {@link PingMessage} response has not yet been received.
     */
    public final
    int getLastRoundTripTime() {
        PingFuture pingFuture2 = this.pingFuture;
        if (pingFuture2 != null) {
            return pingFuture2.getResponse();
        }
        else {
            return -1;
        }
    }

    /**
     * @return true if this connection is also configured to use UDP
     */
    @Override
    public final
    boolean hasUDP() {
        return this.channelWrapper.udp() != null;
    }

    @Override
    public
    void channelWritabilityChanged(final ChannelHandlerContext context) throws Exception {
        super.channelWritabilityChanged(context);

        // needed to place back-pressure when writing too much data to the connection
        if (writeSignalNeeded.getAndSet(false)) {
            synchronized (writeLock) {
                needsLock.set(false);
                writeLock.notifyAll();
            }
        }
    }

    /**
     * needed to place back-pressure when writing too much data to the connection.
     *
     * This blocks until we are writable again
     */
    final
    void controlBackPressure(ConnectionPoint c) {
        while (!closeInProgress.get() && !c.isWritable()) {
            needsLock.set(true);
            writeSignalNeeded.set(true);

            synchronized (writeLock) {
                if (needsLock.get()) {
                    try {
                        // waits 1 second maximum per check. This is to guarantee that eventually (in the case of deadlocks, which i've seen)
                        // it will get released. The while loop makes sure it will exit when the channel is writable
                        writeLock.wait(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    /**
     * Expose methods to send objects to a destination.
     */
    @Override
    public final
    ConnectionBridge send() {
        return this;
    }

    /**
     * Safely sends objects to a destination (such as a custom object or a standard ping). This will automatically choose which protocol
     * is available to use. If you want specify the protocol, use {@link #TCP(Object)}, {@link #UDP(Object)}, or {@link #self(Object)}.
     *
     * By default, this will try in the following order:
     *  - TCP (if available)
     *  - UDP (if available)
     *  - LOCAL (sending a message to itself)
     */
    @Override
    public final
    ConnectionPoint send(final Object message) {
        if (this.channelWrapper.tcp() != null) {
            return TCP(message);
        }
        else if (this.channelWrapper.udp() != null) {
            return UDP(message);
        }
        else {
            self(message);

            // we have to return something, otherwise dependent code will throw a null pointer exception
            return ChannelNull.get();
        }
    }

    /**
     * Sends the object to other listeners INSIDE this endpoint. It does not send it to a remote address.
     */
    @Override
    public final
    ConnectionPoint self(Object message) {
        logger.trace("Sending LOCAL {}", message);
        this.sessionManager.onMessage(this, message);

        // THIS IS REALLY A LOCAL CONNECTION!
        return this.channelWrapper.tcp();
    }

    /**
     * Sends the object over the network using TCP. (LOCAL channels do not care if its TCP or UDP)
     */
    @Override
    public final
    ConnectionPoint TCP(final Object message) {
        if (!closeInProgress.get()) {
            logger.trace("Sending TCP {}", message);

            ConnectionPoint tcp = this.channelWrapper.tcp();
            try {
                tcp.write(message);
            } catch (Exception e) {
                logger.error("Unable to write TCP object {}", message.getClass(), e);
            }
            return tcp;
        }
        else {
            logger.debug("writing TCP while closed: {}", message);

            // we have to return something, otherwise dependent code will throw a null pointer exception
            return ChannelNull.get();
        }
    }

    /**
     * Sends the object over the network using UDP (LOCAL channels do not care if its TCP or UDP)
     */
    @Override
    public
    ConnectionPoint UDP(Object message) {
        if (!closeInProgress.get()) {
            logger.trace("Sending UDP {}", message);

            ConnectionPoint udp = this.channelWrapper.udp();
            try {
                udp.write(message);
            } catch (Exception e) {
                logger.error("Unable to write UDP object {}", message.getClass(), e);
            }
            return udp;
        }
        else {
            logger.debug("writing UDP while closed: {}", message);
            // we have to return something, otherwise dependent code will throw a null pointer exception
            return ChannelNull.get();
        }
    }

    /**
     * Flushes the contents of the TCP/UDP/etc pipes to the actual transport.
     */
    final
    void flush() {
        this.channelWrapper.flush();
    }

    /**
     * Expose methods to modify the connection listeners.
     */
    @Override
    public final
    IdleBridge sendOnIdle(@SuppressWarnings("rawtypes") IdleSender sender) {
        return new IdleSenderFactory(this, sender);
    }


    /**
     * Expose methods to modify the connection listeners.
     */
    @Override
    public final
    IdleBridge sendOnIdle(Object message) {
        return new IdleSenderFactory(this, message);
    }

    /**
     * Invoked when a {@link Channel} has been idle for a while.
     */
    @Override
    public
    void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
        //      if (e.getState() == IdleState.READER_IDLE) {
        //      e.getChannel().close();
        //  } else if (e.getState() == IdleState.WRITER_IDLE) {
        //      e.getChannel().write(new Object());
        //  } else
        if (event instanceof IdleStateEvent) {
            if (((IdleStateEvent) event).state() == IdleState.ALL_IDLE) {
                // will auto-flush if necessary
                this.sessionManager.onIdle(this);
            }
        }

        super.userEventTriggered(context, event);
    }

    /**
     * @param context can be NULL when running deferred messages from registration process.
     * @param message the received message
     */
    @Override
    public
    void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        channelRead(message);
        ReferenceCountUtil.release(message);
    }

    private
    void channelRead(Object object) {
        // prevent close from occurring SMACK in the middle of a message in progress.
        // delay close until it's finished.
        this.messageInProgress.set(true);

        // will auto-flush if necessary
        this.sessionManager.onMessage(this, object);

        this.messageInProgress.set(false);

        // if we are in the middle of closing, and waiting for the message, it's safe to notify it to continue.
        if (this.closeInProgress.get()) {
            synchronized (this.messageInProgressLock) {
                this.messageInProgressLock.notifyAll();
            }
        }

        // in some cases, we want to close the current connection -- and given the way the system is designed, we cannot always close it before
        // we return. This will let us close the connection when our business logic is finished.
        if (closeAsap) {
            close();
        }
    }

    @Override
    public
    void channelInactive(ChannelHandlerContext context) throws Exception {
        // if we are in the middle of a message, hold off.
        if (this.messageInProgress.get()) {
            synchronized (this.messageInProgressLock) {
                try {
                    this.messageInProgressLock.wait();
                } catch (InterruptedException ignored) {
                }
            }
        }

        Channel channel = context.channel();
        Class<? extends Channel> channelClass = channel.getClass();

        boolean isTCP = isTcpChannel(channelClass);
        boolean isUDP = false;
        boolean isLocal = isLocalChannel(channelClass);

        if (this.logger.isInfoEnabled()) {
            String type;

            if (isTCP) {
                type = "TCP";
            }
            else {
                isUDP = isUdpChannel(channelClass);
                if (isUDP) {
                    type = "UDP";
                }
                else if (isLocal) {
                    type = "LOCAL";
                }
                else {
                    type = "UNKNOWN";
                }
            }

            this.logger.info("Closed {} connection [{}]",
                             type,
                             EndPoint.getHostDetails(channel.remoteAddress()));
        }

        // TODO: tell the remote endpoint that it needs to close (via a message, which might get there...).


        if (this.endPoint instanceof EndPointClient) {
            ((EndPointClient) this.endPoint).abortRegistration();
        }


        /*
         * Only close if we are:
         *  - local (mutually exclusive to TCP/UDP)
         *  - TCP (and TCP+UDP)
         *  - UDP (and not part of TCP+UDP)
         *
         * DO NOT call close if we are:
         *  - UDP (part of TCP+UDP)
         */
        if (isLocal ||
            isTCP ||
            (isUDP && this.channelWrapper.tcp() == null)) {

            // we can get to this point in two ways. We only want this to happen once
            // - remote endpoint disconnects (and so closes us)
            // - local endpoint calls close(), and netty will call this.


            // this must happen first, because client.close() depends on it!
            // onDisconnected() must happen last.
            boolean doClose = channelIsClosed.compareAndSet(false, true);

            if (!closeInProgress.get()) {
                if (endPoint instanceof EndPointClient) {
                    // client closes single connection
                    ((Client) endPoint).close();
                } else {
                    // server only closes this connection.
                    close();
                }
            }

            if (doClose) {
                // this is because channelInactive can ONLY happen when netty shuts down the channel.
                //   and connection.close() can be called by the user.
                // will auto-flush if necessary
                this.sessionManager.onDisconnected(this);
            }
        }

        closeLatch.countDown();
    }

    final void
    forceClose() {
        this.channelWrapper.close(this, this.sessionManager, true);
    }

    /**
     * Closes the connection, but does not remove any listeners
     */
    @Override
    public final
    void close() {
        close(true);
    }


    /**
     * we can get to this point in two ways. We only want this to happen once
     *  - remote endpoint disconnects (and so netty calls us)
     *  - local endpoint calls close() directly
     *
     * NOTE: If we remove all listeners and we are the client, then we remove ALL logic from the client!
     */
    final
    void close(final boolean keepListeners) {
        // if we are in the same thread as netty, run in a new thread to prevent deadlocks with messageInProgress
        if (!this.closeInProgress.get() && this.messageInProgress.get() && Shutdownable.isNettyThread()) {
            Shutdownable.runNewThread("Close connection Thread", new Runnable() {
                @Override
                public
                void run() {
                    close(keepListeners);
                }
            });

            return;
        }


        // only close if we aren't already in the middle of closing.
        if (this.closeInProgress.compareAndSet(false, true)) {
            int idleTimeoutMs = this.endPoint.getIdleTimeout();
            if (idleTimeoutMs == 0) {
                // default is 2 second timeout, in milliseconds.
                idleTimeoutMs = 2000;
            }

            // if we are in the middle of a message, hold off.
            synchronized (this.messageInProgressLock) {
                // while loop is to prevent spurious wakeups!
                while (this.messageInProgress.get()) {
                    try {
                        this.messageInProgressLock.wait(idleTimeoutMs);
                    } catch (InterruptedException ignored) {
                    }
                }
            }

            // flush any pending messages
            this.channelWrapper.flush();

            // close out the ping future
            PingFuture pingFuture2 = this.pingFuture;
            if (pingFuture2 != null) {
                pingFuture2.cancel();
            }
            this.pingFuture = null;



            synchronized (this.channelIsClosed) {
                if (!this.channelIsClosed.get()) {
                    // this will have netty call "channelInactive()"
                    this.channelWrapper.close(this, this.sessionManager, false);

                    // want to wait for the "channelInactive()" method to FINISH ALL TYPES before allowing our current thread to continue!
                    try {
                        closeLatch.await(idleTimeoutMs, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                    }
                }
            }

            // remove all listeners AFTER we close the channel.
            if (!keepListeners) {
                removeAll();
            }


            // remove all RMI listeners
            rmiSupport.close();
        }
    }

    /**
     * Marks the connection to be closed as soon as possible. This is evaluated when the current
     * thread execution returns to the network stack.
     */
    @Override
    public final
    void closeAsap() {
        closeAsap = true;
    }

    @Override
    public
    void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception {
        final Channel channel = context.channel();

        if (!(cause instanceof IOException)) {
            // safe to ignore, since it's thrown when we try to interact with a closed socket. Race conditions cause this, and
            // it is still safe to ignore.
            this.logger.error("Unexpected exception while receiving data from {}", channel.remoteAddress(), cause);

            // the ONLY sockets that can call this are:
            // CLIENT TCP or UDP
            // SERVER TCP

            if (channel.isOpen()) {
                channel.close();
            }
        } else {
            // it's an IOException, just log it!
            this.logger.error("Unexpected exception while communicating with {}!", channel.remoteAddress(), cause);
        }
    }

    /**
     * Expose methods to modify the connection listeners.
     */
    @Override
    public final
    Listeners listeners() {
        return this;
    }

    /**
     * Adds a listener to this connection/endpoint to be notified of
     * connect/disconnect/idle/receive(object) events.
     * <p/>
     * If the listener already exists, it is not added again.
     * <p/>
     * When called by a server, NORMALLY listeners are added at the GLOBAL level
     * (meaning, I add one listener, and ALL connections are notified of that
     * listener.
     * <p/>
     * It is POSSIBLE to add a server connection ONLY (ie, not global) listener
     * (via connection.addListener), meaning that ONLY that listener attached to
     * the connection is notified on that event (ie, admin type listeners)
     */
    @Override
    public final
    Listeners add(Listener listener) {
        if (this.endPoint instanceof EndPointServer) {
            // when we are a server, NORMALLY listeners are added at the GLOBAL level
            // meaning --
            //   I add one listener, and ALL connections are notified of that listener.
            //
            // HOWEVER, it is also POSSIBLE to add a local listener (via connection.addListener), meaning that ONLY
            // that listener is notified on that event (ie, admin type listeners)

            // synchronized because this should be VERY uncommon, and we want to make sure that when the manager
            // is empty, we can remove it from this connection.
            synchronized (this) {
                if (this.localListenerManager == null) {
                    this.localListenerManager = ((EndPointServer) this.endPoint).addListenerManager(this);
                }
                this.localListenerManager.add(listener);
            }

        }
        else {
            this.endPoint.listeners()
                         .add(listener);
        }

        return this;
    }

    /**
     * Removes a listener from this connection/endpoint to NO LONGER be notified
     * of connect/disconnect/idle/receive(object) events.
     * <p/>
     * When called by a server, NORMALLY listeners are added at the GLOBAL level
     * (meaning, I add one listener, and ALL connections are notified of that
     * listener.
     * <p/>
     * It is POSSIBLE to remove a server-connection 'non-global' listener (via
     * connection.removeListener), meaning that ONLY that listener attached to
     * the connection is removed
     */
    @Override
    public final
    Listeners remove(Listener listener) {
        if (this.endPoint instanceof EndPointServer) {
            // when we are a server, NORMALLY listeners are added at the GLOBAL level
            // meaning --
            //   I add one listener, and ALL connections are notified of that listener.
            //
            // HOWEVER, it is also POSSIBLE to add a local listener (via connection.addListener), meaning that ONLY
            // that listener is notified on that event (ie, admin type listeners)

            // synchronized because this should be uncommon, and we want to make sure that when the manager
            // is empty, we can remove it from this connection.
            synchronized (this) {
                if (this.localListenerManager != null) {
                    this.localListenerManager.remove(listener);

                    if (!this.localListenerManager.hasListeners()) {
                        ((EndPointServer) this.endPoint).removeListenerManager(this);
                    }
                }
            }
        }
        else {
            this.endPoint.listeners()
                         .remove(listener);
        }

        return this;
    }

    /**
     * Removes all registered listeners from this connection/endpoint to NO
     * LONGER be notified of connect/disconnect/idle/receive(object) events.
     *
     * This includes all proxy listeners
     */
    @Override
    public final
    Listeners removeAll() {
        rmiSupport.removeAllListeners();

        if (this.endPoint instanceof EndPointServer) {
            // when we are a server, NORMALLY listeners are added at the GLOBAL level
            // meaning --
            //   I add one listener, and ALL connections are notified of that listener.
            //
            // HOWEVER, it is also POSSIBLE to add a local listener (via connection.addListener), meaning that ONLY
            // that listener is notified on that event (ie, admin type listeners)

            // synchronized because this should be uncommon, and we want to make sure that when the manager
            // is empty, we can remove it from this connection.
            synchronized (this) {
                if (this.localListenerManager != null) {
                    this.localListenerManager.removeAll();
                    this.localListenerManager = null;

                    ((EndPointServer) this.endPoint).removeListenerManager(this);
                }
            }
        }
        else {
            this.endPoint.listeners()
                         .removeAll();
        }

        return this;
    }

    /**
     * Removes all registered listeners (of the object type) from this connection/endpoint to NO LONGER be notified of
     * connect/disconnect/idle/receive(object) events.
     */
    @Override
    public final
    Listeners removeAll(Class<?> classType) {
        if (this.endPoint instanceof EndPointServer) {
            // when we are a server, NORMALLY listeners are added at the GLOBAL level
            // meaning --
            //   I add one listener, and ALL connections are notified of that listener.
            //
            // HOWEVER, it is also POSSIBLE to add a local listener (via connection.addListener), meaning that ONLY
            // that listener is notified on that event (ie, admin type listeners)

            // synchronized because this should be uncommon, and we want to make sure that when the manager
            // is empty, we can remove it from this connection.
            synchronized (this) {
                if (this.localListenerManager != null) {
                    this.localListenerManager.removeAll(classType);

                    if (!this.localListenerManager.hasListeners()) {
                        this.localListenerManager = null;
                        ((EndPointServer) this.endPoint).removeListenerManager(this);
                    }
                }
            }
        }
        else {
            this.endPoint.listeners()
                         .removeAll(classType);
        }

        return this;
    }

    @Override
    public
    String toString() {
        return this.channelWrapper.toString();
    }

    @Override
    public
    int hashCode() {
        return id();
    }

    @Override
    public
    boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }

        ConnectionImpl other = (ConnectionImpl) obj;
        if (this.channelWrapper == null) {
            if (other.channelWrapper != null) {
                return false;
            }
        }
        else if (!this.channelWrapper.equals(other.channelWrapper)) {
            return false;
        }

        return true;
    }


    //
    //
    // RMI methods
    //
    //

    @Override
    public
    ConnectionRmiSupport rmiSupport() {
        return rmiSupport;
    }

    @Override
    public final
    <Iface> void createRemoteObject(final Class<Iface> interfaceClass, final RemoteObjectCallback<Iface> callback) {
        rmiSupport.createRemoteObject(this, interfaceClass, callback);
    }

    @Override
    public final
    <Iface> void getRemoteObject(final int objectId, final RemoteObjectCallback<Iface> callback) {
        rmiSupport.getRemoteObject(this, objectId, callback);
    }

    /**
     * Manages the RMI stuff for a connection.
     *
     * @return true if there was RMI stuff done, false if the message was "normal" and nothing was done
     */
    boolean manageRmi(final Object message) {
        return rmiSupport.manage(this, message);
    }

    /**
     * Objects that are on the "local" in-jvm connection have fixup their objects. For "network" connections, this is automatically done.
     */
    Object fixupRmi(final Object message) {
        // "local RMI" objects have to be modified, this part does that
        return rmiSupport.fixupRmi(message);
    }
}
