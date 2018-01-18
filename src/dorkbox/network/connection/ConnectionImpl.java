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
import java.lang.reflect.Field;
import java.util.LinkedList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.bouncycastle.crypto.params.ParametersWithIV;
import org.slf4j.Logger;

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
import dorkbox.network.rmi.RemoteObject;
import dorkbox.network.rmi.RemoteObjectCallback;
import dorkbox.network.rmi.Rmi;
import dorkbox.network.rmi.RmiBridge;
import dorkbox.network.rmi.RmiRegistration;
import dorkbox.network.serialization.RmiSerializationManager;
import dorkbox.util.collections.IntMap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
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
class ConnectionImpl extends ChannelInboundHandlerAdapter implements ICryptoConnection, Connection, Listeners, ConnectionBridge {

    private final org.slf4j.Logger logger;

    private final AtomicBoolean needsLock = new AtomicBoolean(false);
    private final AtomicBoolean writeSignalNeeded = new AtomicBoolean(false);
    private final Object writeLock = new Object();

    private final AtomicBoolean closeInProgress = new AtomicBoolean(false);
    private final AtomicBoolean alreadyClosed = new AtomicBoolean(false);
    private final Object closeInProgressLock = new Object();

    private final Object messageInProgressLock = new Object();
    private final AtomicBoolean messageInProgress = new AtomicBoolean(false);

    private ISessionManager<Connection> sessionManager;
    private ChannelWrapper<Connection> channelWrapper;
    private boolean isLoopback;

    private volatile PingFuture pingFuture = null;

    // used to store connection local listeners (instead of global listeners). Only possible on the server.
    private volatile ConnectionManager<Connection> localListenerManager;

    // while on the CLIENT, if the SERVER's ecc key has changed, the client will abort and show an error.
    private boolean remoteKeyChanged;

    private final EndPointBase endPoint;

    // when true, the connection will be closed (either as RMI or as 'normal' listener execution) when the thread execution returns control
    // back to the network stack
    private boolean closeAsap = false;

    // The IV for AES-GCM must be 12 bytes, since it's 4 (salt) + 8 (external counter) + 4 (GCM counter)
    // The 12 bytes IV is created during connection registration, and during the AES-GCM crypto, we override the last 8 with this
    // counter, which is also transmitted as an optimized int. (which is why it starts at 0, so the transmitted bytes are small)
    private final AtomicLong aes_gcm_iv = new AtomicLong(0);

    //
    // RMI fields
    //
    protected CountDownLatch rmi;
    private final RmiBridge rmiBridge;
    private final Map<Integer, RemoteObject> proxyIdCache = new WeakHashMap<Integer, RemoteObject>(8);
    private final IntMap<RemoteObjectCallback> rmiRegistrationCallbacks = new IntMap<RemoteObjectCallback>();
    private int rmiRegistrationID = 0; // protected by synchronized (rmiRegistrationCallbacks)

    /**
     * All of the parameters can be null, when metaChannel wants to get the base class type
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public
    ConnectionImpl(final Logger logger, final EndPointBase endPoint, final RmiBridge rmiBridge) {
        this.logger = logger;
        this.endPoint = endPoint;
        this.rmiBridge = rmiBridge;
    }

    /**
     * Initialize the connection with any extra info that is needed but was unavailable at the channel construction.
     * <p/>
     * This happens BEFORE prep.
     */
    @SuppressWarnings("unchecked")
    void init(final ChannelWrapper channelWrapper, final ConnectionManager<Connection> sessionManager) {
        this.sessionManager = sessionManager;
        this.channelWrapper = channelWrapper;

        //noinspection SimplifiableIfStatement
        if (this.channelWrapper instanceof ChannelNetworkWrapper) {
            this.remoteKeyChanged = ((ChannelNetworkWrapper) this.channelWrapper).remoteKeyChanged();
        }
        else {
            this.remoteKeyChanged = false;
        }

        isLoopback = channelWrapper.isLoopback();
    }

    /**
     * Prepare the channel wrapper, since it doesn't have access to certain fields during it's initialization.
     * <p/>
     * This happens AFTER init.
     */
    void prep() {
        if (this.channelWrapper != null) {
            this.channelWrapper.init();
        }
    }


    /**
     * @return a threadlocal AES key + IV. key=32 byte, iv=12 bytes (AES-GCM implementation). This is a threadlocal
     *          because multiple protocols can be performing crypto AT THE SAME TIME, and so we have to make sure that operations don't
     *          clobber each other
     */
    @Override
    public final
    ParametersWithIV getCryptoParameters() {
        return this.channelWrapper.cryptoParameters();
    }

    /**
     * This is the per-message sequence number.
     *
     *  The IV for AES-GCM must be 12 bytes, since it's 4 (salt) + 8 (external counter) + 4 (GCM counter)
     *  The 12 bytes IV is created during connection registration, and during the AES-GCM crypto, we override the last 8 with this
     *  counter, which is also transmitted as an optimized int. (which is why it starts at 0, so the transmitted bytes are small)
     */
    @Override
    public final
    long getNextGcmSequence() {
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
        return isLoopback;
    }

    /**
     * @return the endpoint associated with this connection
     */
    @Override
    public
    EndPointBase<Connection> getEndPoint() {
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
        return Integer.toHexString(this.channelWrapper.id());
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

        Promise<PingTuple<? extends Connection>> newPromise = this.channelWrapper.getEventLoop()
                                                                                 .newPromise();
        this.pingFuture = new PingFuture(newPromise);

        PingMessage ping = new PingMessage();
        ping.id = this.pingFuture.getId();
        ping0(ping);

        return this.pingFuture;
    }

    /**
     * INTERNAL USE ONLY. Used to initiate a ping, and to return a ping.
     * Sends a ping message attempted in the following order: UDP, TCP
     */
    public final
    void ping0(PingMessage ping) {
        if (this.channelWrapper.udp() != null) {
            UDP(ping).flush();
        }
        else {
            TCP(ping).flush();
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
    void channelWritabilityChanged(final ChannelHandlerContext ctx) throws Exception {
        super.channelWritabilityChanged(ctx);

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
    private
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
     * Sends the object to other listeners INSIDE this endpoint. It does not send it to a remote address.
     */
    @Override
    public final
    void self(Object message) {
        Logger logger2 = this.logger;
        if (logger2.isTraceEnabled()) {
            logger2.trace("Sending LOCAL {}", message);
        }
        this.sessionManager.onMessage(this, message);
    }

    /**
     * Sends the object over the network using TCP. (LOCAL channels do not care if its TCP or UDP)
     */
    final
    ConnectionPoint TCP_backpressure(Object message) {
        Logger logger2 = this.logger;
        if (!this.closeInProgress.get()) {
            if (logger2.isTraceEnabled()) {
                logger2.trace("Sending TCP {}", message);
            }
            ConnectionPointWriter tcp = this.channelWrapper.tcp();
            // needed to place back-pressure when writing too much data to the connection. Will create deadlocks if called from
            // INSIDE the event loop
            controlBackPressure(tcp);

            tcp.write(message);
            return tcp;
        }
        else {
            if (logger2.isDebugEnabled()) {
                logger2.debug("writing TCP while closed: {}", message);
            }
            // we have to return something, otherwise dependent code will throw a null pointer exception
            return ChannelNull.get();
        }
    }

    /**
     * Sends the object over the network using TCP. (LOCAL channels do not care if its TCP or UDP)
     */
    @Override
    public final
    ConnectionPoint TCP(Object message) {
        Logger logger2 = this.logger;
        if (!this.closeInProgress.get()) {
            if (logger2.isTraceEnabled()) {
                logger2.trace("Sending TCP {}", message);
            }
            ConnectionPointWriter tcp = this.channelWrapper.tcp();
            tcp.write(message);
            return tcp;
        }
        else {
            if (logger2.isDebugEnabled()) {
                logger2.debug("writing TCP while closed: {}", message);
            }
            // we have to return something, otherwise dependent code will throw a null pointer exception
            return ChannelNull.get();
        }
    }

    /**
     * Sends the object over the network using UDP (LOCAL channels do not care if its TCP or UDP)
     */
    final
    ConnectionPoint UDP_backpressure(Object message) {
        Logger logger2 = this.logger;
        if (!this.closeInProgress.get()) {
            if (logger2.isTraceEnabled()) {
                logger2.trace("Sending UDP {}", message);
            }
            ConnectionPointWriter udp = this.channelWrapper.udp();
            // needed to place back-pressure when writing too much data to the connection. Will create deadlocks if called from
            // INSIDE the event loop
            controlBackPressure(udp);

            udp.write(message);
            return udp;
        }
        else {
            if (logger2.isDebugEnabled()) {
                logger2.debug("writing UDP while closed: {}", message);
            }
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
        Logger logger2 = this.logger;
        if (!this.closeInProgress.get()) {
            if (logger2.isTraceEnabled()) {
                logger2.trace("Sending UDP {}", message);
            }
            ConnectionPointWriter udp = this.channelWrapper.udp();
            udp.write(message);
            return udp;
        }
        else {
            if (logger2.isDebugEnabled()) {
                logger2.debug("writing UDP while closed: {}", message);
            }
            // we have to return something, otherwise dependent code will throw a null pointer exception
            return ChannelNull.get();
        }
    }

    /**
     * Flushes the contents of the TCP/UDP/etc pipes to the actual transport.
     */
    @Override
    public final
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
                this.sessionManager.onIdle(this);
            }
        }

        super.userEventTriggered(context, event);
    }

    @Override
    public
    void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        channelRead(message);
        ReferenceCountUtil.release(message);
    }

    public
    void channelRead(Object object) throws Exception {

        // prevent close from occurring SMACK in the middle of a message in progress.
        // delay close until it's finished.
        this.messageInProgress.set(true);

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

        boolean isTCP = channelClass == NioSocketChannel.class || channelClass == EpollSocketChannel.class;

        if (this.logger.isInfoEnabled()) {
            String type;

            if (isTCP) {
                type = "TCP";
            }
            else if (channelClass == NioDatagramChannel.class || channelClass == EpollDatagramChannel.class) {
                type = "UDP";
            }
            else if (channelClass == LocalChannel.class) {
                type = "LOCAL";
            }
            else {
                type = "UNKNOWN";
            }

            this.logger.info("Closed remote {} connection: {}",
                             type,
                             channel.remoteAddress()
                                    .toString());
        }

        if (this.endPoint instanceof EndPointClient) {
            ((EndPointClient) this.endPoint).abortRegistration();
        }

        // our master channels are TCP/LOCAL (which are mutually exclusive). Only key disconnect events based on the status of them.
        if (isTCP || channelClass == LocalChannel.class) {
            // this is because channelInactive can ONLY happen when netty shuts down the channel.
            //   and connection.close() can be called by the user.
            this.sessionManager.onDisconnected(this);

            // close TCP/UDP together!
            close();
        }

        synchronized (this.closeInProgressLock) {
            this.alreadyClosed.set(true);
            this.closeInProgressLock.notify();
        }
    }

    /**
     * Closes the connection
     */
    @Override
    public final
    void close() {
        // only close if we aren't already in the middle of closing.
        if (this.closeInProgress.compareAndSet(false, true)) {
            int idleTimeoutMs = this.endPoint.getIdleTimeout();
            if (idleTimeoutMs == 0) {
                // default is 2 second timeout, in milliseconds.
                idleTimeoutMs = 2000;
            }

            // if we are in the middle of a message, hold off.
            synchronized (this.messageInProgressLock) {
                if (this.messageInProgress.get()) {
                    try {
                        this.messageInProgressLock.wait(idleTimeoutMs);
                    } catch (InterruptedException ignored) {
                    }
                }
            }

            // flush any pending messages
            this.channelWrapper.flush();

            this.channelWrapper.close(this, this.sessionManager);

            // close out the ping future
            PingFuture pingFuture2 = this.pingFuture;
            if (pingFuture2 != null) {
                pingFuture2.cancel();
            }
            this.pingFuture = null;

            // want to wait for the "channelInactive" method to FINISH before allowing our current thread to continue!
            synchronized (this.closeInProgressLock) {
                if (!this.alreadyClosed.get()) {
                    try {
                        this.closeInProgressLock.wait(idleTimeoutMs);
                    } catch (Exception ignored) {
                    }
                }
            }
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
    @SuppressWarnings("rawtypes")
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
    @SuppressWarnings("rawtypes")
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
                        ((EndPointServer<Connection>) this.endPoint).removeListenerManager(this);
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
     */
    @Override
    public final
    Listeners removeAll() {
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

                    ((EndPointServer<Connection>) this.endPoint).removeListenerManager(this);
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
     * Removes all registered listeners (of the object type) from this
     * connection/endpoint to NO LONGER be notified of
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
                        ((EndPointServer<Connection>) this.endPoint).removeListenerManager(this);
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

    @SuppressWarnings({"UnnecessaryLocalVariable", "unchecked", "Duplicates"})
    @Override
    public final
    <Iface> void getRemoteObject(final Class<Iface> interfaceClass, final RemoteObjectCallback<Iface> callback) {
        if (!interfaceClass.isInterface()) {
            throw new IllegalArgumentException("Cannot create a proxy for RMI access. It must be an interface.");
        }

        RmiRegistration message;

        synchronized (rmiRegistrationCallbacks) {
            int nextRmiID = rmiRegistrationID++;
            rmiRegistrationCallbacks.put(nextRmiID, callback);
            message = new RmiRegistration(interfaceClass, nextRmiID);
        }

        // We use a callback to notify us when the object is ready. We can't "create this on the fly" because we
        // have to wait for the object to be created + ID to be assigned on the remote system BEFORE we can create the proxy instance here.

        // this means we are creating a NEW object on the server, bound access to only this connection
        TCP(message).flush();
    }

    @SuppressWarnings({"UnnecessaryLocalVariable", "unchecked", "Duplicates"})
    @Override
    public final
    <Iface> void getRemoteObject(final int objectId, final RemoteObjectCallback<Iface> callback) {
        RmiRegistration message;

        synchronized (rmiRegistrationCallbacks) {
            int nextRmiID = rmiRegistrationID++;
            rmiRegistrationCallbacks.put(nextRmiID, callback);
            message = new RmiRegistration(objectId, nextRmiID);
        }

        // We use a callback to notify us when the object is ready. We can't "create this on the fly" because we
        // have to wait for the object to be created + ID to be assigned on the remote system BEFORE we can create the proxy instance here.

        // this means we are creating a NEW object on the server, bound access to only this connection
        TCP(message).flush();
    }

    private
    void collectRmiFields(final RmiBridge rmiBridge,
                          final LinkedList<ClassObject> classesToCheck, final ClassObject remoteClassObject, final Field[] fields) {


    }

    final
    void registerInternal(final ConnectionImpl connection, final RmiRegistration remoteRegistration) {
        final Class<?> interfaceClass = remoteRegistration.interfaceClass;
        final int rmiID = remoteRegistration.rmiID;

        if (interfaceClass != null) {
            // THIS IS ON THE REMOTE CONNECTION (where the object will really exist as an implementation)
            //
            // CREATE a new ID, and register the ID and new object (must create a new one) in the object maps

            // the interface class kryo ID == implementation class kryo ID, so they switcheroo automatically.
            final Class<?> implementationClass = interfaceClass;

            final RmiSerializationManager manager = getEndPoint().serializationManager;

            KryoExtra kryo = null;
            final Object remotePrimaryObject;
            try {
                kryo = manager.takeKryo();
                // this is what creates a new instance of the impl class, and stores it as an ID.
                remotePrimaryObject = kryo.newInstance(implementationClass);
            } catch (Exception e) {
                logger.error("Error creating RMI class " + implementationClass, e);
                connection.TCP(new RmiRegistration(rmiID))
                          .flush();
                return;
            } finally {
                if (kryo != null) {
                    // we use kryo to create a new instance - so only return it on error or when it's done creating a new instance
                    manager.returnKryo(kryo);
                }
            }


            try {
                rmiBridge.register(rmiBridge.nextObjectId(), remotePrimaryObject);


                // the @Rmi annotation allows an RMI object to have fields with objects that are ALSO RMI
                LinkedList<ClassObject> classesToCheck = new LinkedList<ClassObject>();
                classesToCheck.add(new ClassObject(implementationClass, remotePrimaryObject));

                ClassObject remoteClassObject;
                while (!classesToCheck.isEmpty()) {
                    remoteClassObject = classesToCheck.removeFirst();

                    // we have to check the IMPLEMENTATION for any additional fields that will have proxy information.
                    // we use getDeclaredFields() + walking the object hierarchy, so we get ALL the fields possible.
                    for (Field field : remoteClassObject.clazz.getDeclaredFields()) {
                        if (field.getAnnotation(Rmi.class) != null) {
                            final Class<?> type = field.getType();

                            if (!type.isInterface()) {
                                // the type must be an interface, otherwise RMI cannot create a proxy object
                                logger.error("Error checking RMI fields for: {}.{} -- It is not an interface!", remoteClassObject.clazz, field.getName());
                                continue;
                            }


                            boolean prev = field.isAccessible();
                            field.setAccessible(true);
                            final Object o;
                            try {
                                o = field.get(remoteClassObject.object);

                                rmiBridge.register(rmiBridge.nextObjectId(), o);
                                classesToCheck.add(new ClassObject(type, o));
                            } catch (IllegalAccessException e) {
                                logger.error("Error checking RMI fields for: {}.{}", remoteClassObject.clazz, field.getName(), e);
                            } finally {
                                field.setAccessible(prev);
                            }
                        }
                    }


                    // have to check the object hierarchy as well
                    Class<?> superclass = remoteClassObject.clazz.getSuperclass();
                    if (superclass != null && superclass != Object.class) {
                        classesToCheck.add(new ClassObject(superclass, remoteClassObject.object));
                    }
                }

                connection.TCP(new RmiRegistration(remotePrimaryObject, rmiID)).flush();
            } catch (Exception e) {
                logger.error("Error registering RMI class " + implementationClass, e);
                connection.TCP(new RmiRegistration(rmiID)).flush();
            }
        }
        else if (remoteRegistration.remoteObjectId > RmiBridge.INVALID_RMI) {
            // THIS IS ON THE REMOTE CONNECTION (where the object implementation will really exist)
            //
            // GET a LOCAL rmi object, if none get a specific, GLOBAL rmi object (objects that are not bound to a single connection).
            Object object = getImplementationObject(remoteRegistration.remoteObjectId);

            if (object != null) {
                connection.TCP(new RmiRegistration(object, rmiID)).flush();
            } else {
                connection.TCP(new RmiRegistration(rmiID)).flush();
            }
        }
        else {
            // THIS IS ON THE LOCAL CONNECTION SIDE, which is the side that called 'getRemoteObject()'   This can be Server or Client.

            // this will be null if there was an error
            Object remoteObject = remoteRegistration.remoteObject;

            boolean noMoreRmiRemaining ;
            RemoteObjectCallback callback;

            synchronized (rmiRegistrationCallbacks) {
                callback = rmiRegistrationCallbacks.remove(remoteRegistration.rmiID);
            }

            try {
                //noinspection unchecked
                callback.created(remoteObject);
            } catch (Exception e) {
                logger.error("Error getting remote object " + remoteObject.getClass() + ", ID: " + rmiID, e);
            }
        }
    }

    /**
     * Used by RMI
     *
     * @return the registered ID for a specific object. This is used by the "client" side when setting up the to fetch an object for the
     * "service" side for RMI
     */
    @Override
    public
    <T> int getRegisteredId(final T object) {
        // always check local before checking global, because less contention on the synchronization
        RmiBridge globalRmiBridge = endPoint.globalRmiBridge;

        if (globalRmiBridge == null) {
            throw new NullPointerException("Unable to call 'getRegisteredId' when the globalRmiBridge is null!");
        }

        int objectId = globalRmiBridge.getRegisteredId(object);
        if (objectId == Integer.MAX_VALUE) {
            return rmiBridge.getRegisteredId(object);
        } else {
            return objectId;
        }
    }

    /**
     * Used by RMI for the CLIENT side, to get the proxy object as an interface
     *
     * @param objectID is the RMI object ID
     * @param iFace must be the interface the proxy will bind to
     */
    @Override
    public
    RemoteObject getProxyObject(final int objectID, final Class<?> iFace) {
        synchronized (proxyIdCache) {
            // we want to have a connection specific cache of IDs, using weak references.
            // because this is PER CONNECTION, this is safe.
            RemoteObject remoteObject = proxyIdCache.get(objectID);

            if (remoteObject == null) {
                // duplicates are fine, as they represent the same object (as specified by the ID) on the remote side.
                remoteObject = rmiBridge.createProxyObject(this, objectID, iFace);
                proxyIdCache.put(objectID, remoteObject);
            }

            return remoteObject;
        }
    }

    /**
     * This is used by RMI for the REMOTE side, to get the implementation
     */
    @Override
    public
    Object getImplementationObject(final int objectID) {
        if (RmiBridge.isGlobal(objectID)) {
            RmiBridge globalRmiBridge = endPoint.globalRmiBridge;

            if (globalRmiBridge == null) {
                throw new NullPointerException("Unable to call 'getRegisteredId' when the gloablRmiBridge is null!");
            }

            return globalRmiBridge.getRegisteredObject(objectID);
        } else {
            return rmiBridge.getRegisteredObject(objectID);
        }
    }
}
