package dorkbox.network.connection;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.udt.nio.NioUdtByteConnectorChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Promise;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bouncycastle.crypto.params.ParametersWithIV;
import org.slf4j.Logger;

import dorkbox.network.connection.bridge.ConnectionBridge;
import dorkbox.network.connection.idle.IdleBridge;
import dorkbox.network.connection.idle.IdleObjectSender;
import dorkbox.network.connection.idle.IdleSender;
import dorkbox.network.connection.wrapper.ChannelNetworkWrapper;
import dorkbox.network.connection.wrapper.ChannelNull;
import dorkbox.network.connection.wrapper.ChannelWrapper;


/**
 * The "network connection" is established once the registration is validated for TCP/UDP/UDT
 */
@Sharable
public class ConnectionImpl extends ChannelInboundHandlerAdapter
        implements Connection, ListenerBridge, ConnectionBridge {

    private final org.slf4j.Logger logger;
    private final String name;

    private AtomicBoolean closeInProgress = new AtomicBoolean(false);
    private AtomicBoolean alreadyClosed = new AtomicBoolean(false);
    private final Object closeInProgressLock = new Object();

    private final Object messageInProgressLock = new Object();
    private AtomicBoolean messageInProgress = new AtomicBoolean(false);

    private ISessionManager sessionManager;
    private ChannelWrapper channelWrapper;
    private EndPointWithSerialization endPoint;

    private volatile PingFuture pingFuture = null;

    // used to store connection local listeners (instead of global listeners). Only possible on the server.
    private volatile ConnectionManager localListenerManager;

    // while on the CLIENT, if the SERVER's ecc key has changed, the client will abort and show an error.
    private boolean remoteKeyChanged;


    public ConnectionImpl(String name) {
        this.name = name;
        this.logger = org.slf4j.LoggerFactory.getLogger(name);
    }

    /**
     * Initialize the connection with any extra info that is needed but was unavailable at the channel construction.
     */
    @Override
    public void init(EndPointWithSerialization endPoint, Bridge bridge) {
        this.endPoint = endPoint;
        if (bridge != null) {
            this.sessionManager = bridge.sessionManager;
            this.channelWrapper = bridge.channelWrapper;
        } else {
            this.sessionManager = null;
            this.channelWrapper = null;
        }

        if (this.channelWrapper instanceof ChannelNetworkWrapper) {
            this.remoteKeyChanged = ((ChannelNetworkWrapper)this.channelWrapper).remoteKeyChanged();
        } else {
            this.remoteKeyChanged = false;
        }
    }

    /**
     * Prepare the channel wrapper, since it doesn't have access to certain fields during it's construction.
     */
    @Override
    public void prep() {
        if (this.channelWrapper != null) {
            this.channelWrapper.init();
        }
    }


    /**
     * @return the AES key/IV, etc associated with this connection
     */
    @Override
    public final ParametersWithIV getCryptoParameters() {
        return this.channelWrapper.cryptoParameters();
    }

    /**
     * Has the remote ECC public key changed. This can be useful if specific actions are necessary when the key has changed.
     */
    @Override
    public boolean hasRemoteKeyChanged() {
        return this.remoteKeyChanged;
    }

    /**
     * @return the remote address, as a string.
     */
    @Override
    public String getRemoteHost() {
        return this.channelWrapper.getRemoteHost();
    }

    /**
     * @return the name used by the connection
     */
    @Override
    public String getName() {
        return this.name;
    }

    /**
     * @return the connection (TCP or LOCAL) id of this connection.
     */
    @Override
    public int id() {
        return this.channelWrapper.id();
    }

    /**
     * @return the connection (TCP or LOCAL) id of this connection as a HEX string.
     */
    @Override
    public String idAsHex() {
        return Integer.toHexString(this.channelWrapper.id());
    }

    /**
     * Updates the ping times for this connection (called when this connection gets a REPLY ping message).
     */
    public final void updatePingResponse(PingMessage ping) {
        if (this.pingFuture != null) {
            this.pingFuture.setSuccess(this, ping);
        }
    }

    /**
     * Sends a "ping" packet, trying UDP, then UDT, then TCP (in that order) to measure <b>ROUND TRIP</b> time to the remote connection.
     *
     * @return Ping can have a listener attached, which will get called when the ping returns.
     */
    @Override
    public final Ping ping() {
        PingFuture pingFuture2 = this.pingFuture;
        if (pingFuture2 != null && !pingFuture2.isSuccess()) {
            pingFuture2.cancel();
        }

        Promise<PingTuple<? extends Connection>> newPromise = this.channelWrapper.getEventLoop().newPromise();
        this.pingFuture = new PingFuture(newPromise);

        PingMessage ping = new PingMessage();
        ping.id = this.pingFuture.getId();
        ping0(ping);

        return this.pingFuture;
    }

    /**
     * INTERNAL USE ONLY. Used to initiate a ping, and to return a ping.
     * Sends a ping message attempted in the following order: UDP, UDT, TCP
     */
    final void ping0(PingMessage ping) {
        if (this.channelWrapper.udp() != null) {
            UDP(ping).flush();
        } else if (this.channelWrapper.udt() != null) {
            UDT(ping).flush();
        } else {
            TCP(ping).flush();
        }
    }

    /**
     * Returns the last calculated TCP return trip time, or -1 if or the {@link PingMessage} response has not yet been received.
     */
    public final int getLastRoundTripTime() {
        PingFuture pingFuture2 = this.pingFuture;
        if (pingFuture2 != null) {
            return pingFuture2.getResponse();
        } else {
            return -1;
        }
    }

    /**
     * @return true if this connection is also configured to use UDP
     */
    @Override
    public final boolean hasUDP() {
        return this.channelWrapper.udp() != null;
    }

    /**
     * @return true if this connection is also configured to use UDT
     */
    @Override
    public final boolean hasUDT() {
        return this.channelWrapper.udt() != null;
    }

    /**
     * Expose methods to send objects to a destination.
     */
    @Override
    public final ConnectionBridge send() {
        return this;
    }

    /**
     * Sends the object to other listeners INSIDE this endpoint. It does not send it to a remote address.
     */
    @Override
    public final void self(Object message) {
        Logger logger2 = this.logger;
        if (logger2.isTraceEnabled()) {
            logger2.trace("Sending LOCAL {}", message);
        }
        this.sessionManager.notifyOnMessage(this, message);
    }

    /**
     * Sends the object over the network using TCP. (LOCAL channels do not care if its TCP or UDP)
     */
    @Override
    public final ConnectionPoint TCP(Object message) {
        Logger logger2 = this.logger;
        if (!this.closeInProgress.get()) {
            if (logger2.isTraceEnabled()) {
                logger2.trace("Sending TCP {}", message);
            }
            ConnectionPointWriter tcp = this.channelWrapper.tcp();
            tcp.write(message);
            return tcp;
        } else {
            if (logger2.isDebugEnabled()) {
                logger2.debug("writing TCP while closed: {}", message);
            }
            // we have to return something, otherwise dependent code will throw a null pointer exception
            return ChannelNull.get();
        }

    }

    /**
     * Sends the object over the network using UDP (or via LOCAL when it's a local channel).
     */
    @Override
    public ConnectionPoint UDP(Object message) {
        Logger logger2 = this.logger;
        if (!this.closeInProgress.get()) {
            if (logger2.isTraceEnabled()) {
                logger2.trace("Sending UDP {}", message);
            }
            ConnectionPointWriter udp = this.channelWrapper.udp();
            udp.write(message);
            return udp;
        } else {
            if (logger2.isDebugEnabled()) {
                logger2.debug("writing UDP while closed: {}", message);
            }
            // we have to return something, otherwise dependent code will throw a null pointer exception
            return ChannelNull.get();
        }
    }

    /**
     * Sends the object over the network using TCP. (LOCAL channels do not care if its TCP or UDP)
     */
    @Override
    public final ConnectionPoint UDT(Object message) {
        Logger logger2 = this.logger;
        if (!this.closeInProgress.get()) {
            if (logger2.isTraceEnabled()) {
                logger2.trace("Sending UDT {}", message);
            }
            ConnectionPointWriter udt = this.channelWrapper.udt();
            udt.write(message);
            return udt;
        } else {
            if (logger2.isDebugEnabled()) {
                logger2.debug("writing UDT while closed: {}", message);
            }
            // we have to return something, otherwise dependent code will throw a null pointer exception
            return ChannelNull.get();
        }
    }


    /**
     * Flushes the contents of the TCP/UDP/UDT/etc pipes to the actual transport.
     */
    @Override
    public final void flush() {
        this.channelWrapper.flush();
    }

    /**
     * Expose methods to modify the connection listeners.
     */
    @Override
    public final IdleBridge sendOnIdle(@SuppressWarnings("rawtypes") IdleSender sender) {
        listeners().add(sender);
        return sender;
    }


    /**
     * Expose methods to modify the connection listeners.
     */
    @Override
    public final IdleBridge sendOnIdle(Object message) {
        @SuppressWarnings({"rawtypes","unchecked"})
        IdleObjectSender sender = new IdleObjectSender(message);
        listeners().add(sender);
        return sender;
    }

    /**
     * Invoked when a {@link Channel} has been idle for a while.
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
        //      if (e.getState() == IdleState.READER_IDLE) {
        //      e.getChannel().close();
        //  } else if (e.getState() == IdleState.WRITER_IDLE) {
        //      e.getChannel().write(new Object());
        //  } else
        if (event instanceof IdleStateEvent) {
            if (((IdleStateEvent) event).state() == IdleState.ALL_IDLE) {
                this.sessionManager.notifyOnIdle(this);
            }
        }

        super.userEventTriggered(context, event);
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        channelRead(message);
        ReferenceCountUtil.release(message);
    }

    public void channelRead(Object object) throws Exception {

        // prevent close from occurring SMACK in the middle of a message in progress.
        // delay close until it's finished.
        this.messageInProgress.set(true);

        this.sessionManager.notifyOnMessage(this, object);

        this.messageInProgress.set(false);

        // if we are in the middle of closing, and waiting for the message, it's safe to notify it to continue.
        if (this.closeInProgress.get()) {
            synchronized (this.messageInProgressLock) {
                this.messageInProgressLock.notifyAll();
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        // if we are in the middle of a message, hold off.
        if (this.messageInProgress.get()) {
            synchronized (this.messageInProgressLock) {
                try {
                    this.messageInProgressLock.wait();
                } catch (InterruptedException e) {
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
            } else if (channelClass == NioDatagramChannel.class) {
                type = "UDP";
            } else if (channelClass == EpollDatagramChannel.class) {
                type = "UDP";
            } else if (channelClass == NioUdtByteConnectorChannel.class) {
                type = "UDT";
            } else if (channelClass == LocalChannel.class) {
                type = "LOCAL";
            } else {
                type = "UNKNOWN";
            }

            this.logger.info("Closed remote {} connection: {}", type, channel.remoteAddress().toString());
        }

        // our master channels are TCP/LOCAL (which are mutually exclusive). Only key disconnect events based on the status of them.
        if (isTCP || channelClass == LocalChannel.class) {
            // this is because channelInactive can ONLY happen when netty shuts down the channel.
            //   and connection.close() can be called by the user.
            this.sessionManager.connectionDisconnected(this);

            // close TCP/UDP/UDT together!
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
    public final void close() {
        // only close if we aren't already in the middle of closing.
        if (this.closeInProgress.compareAndSet(false, true)) {
            int idleTimeout = this.endPoint.getIdleTimeout();
            if (idleTimeout == 0) {
                // default is 2 second timeout, in milliseconds.
                idleTimeout = 2000;
            }

            // if we are in the middle of a message, hold off.
            synchronized (this.messageInProgressLock) {
                if (this.messageInProgress.get()) {
                    try {
                        this.messageInProgressLock.wait(idleTimeout);
                    } catch (InterruptedException e) {
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
                        this.closeInProgressLock.wait(idleTimeout);
                    } catch (Exception e) {
                    }
                }
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception {
        if (!(cause instanceof IOException)) {
            Channel channel = context.channel();

            // safe to ignore, since it's thrown when we try to interact with a closed socket. Race conditions cause this, and
            // it is still safe to ignore.
            this.logger.error("Unexpected exception while receiving data from {}", channel.remoteAddress(), cause);
            this.sessionManager.connectionError(this, cause);

            // the ONLY sockets that can call this are:
            // CLIENT TCP or UDP
            // SERVER TCP

            if (channel.isOpen()) {
                channel.close();
            }
        }
    }

    /**
     * Expose methods to modify the connection listeners.
     */
    @Override
    public final ListenerBridge listeners() {
        return this;
    }

    /**
     * Adds a listener to this connection/endpoint to be notified of
     * connect/disconnect/idle/receive(object) events.
     * <p>
     * If the listener already exists, it is not added again.
     * <p>
     * When called by a server, NORMALLY listeners are added at the GLOBAL level
     * (meaning, I add one listener, and ALL connections are notified of that
     * listener.
     * <p>
     * It is POSSIBLE to add a server connection ONLY (ie, not global) listener
     * (via connection.addListener), meaning that ONLY that listener attached to
     * the connection is notified on that event (ie, admin type listeners)
     */
    @SuppressWarnings("rawtypes")
    @Override
    public final void add(ListenerRaw listener) {
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
                    this.localListenerManager = ((EndPointServer)this.endPoint).addListenerManager(this);
                }
                this.localListenerManager.add(listener);
            }

        } else {
            this.endPoint.listeners().add(listener);
        }
    }

    /**
     * Removes a listener from this connection/endpoint to NO LONGER be notified
     * of connect/disconnect/idle/receive(object) events.
     * <p>
     * When called by a server, NORMALLY listeners are added at the GLOBAL level
     * (meaning, I add one listener, and ALL connections are notified of that
     * listener.
     * <p>
     * It is POSSIBLE to remove a server-connection 'non-global' listener (via
     * connection.removeListener), meaning that ONLY that listener attached to
     * the connection is removed
     */
    @SuppressWarnings("rawtypes")
    @Override
    public final void remove(ListenerRaw listener) {
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
                        ((EndPointServer)this.endPoint).removeListenerManager(this);
                    }
                }
            }
        } else {
            this.endPoint.listeners().remove(listener);
        }
    }

    /**
     * Removes all registered listeners from this connection/endpoint to NO
     * LONGER be notified of connect/disconnect/idle/receive(object) events.
     */
    @Override
    public final void removeAll() {
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

                    ((EndPointServer)this.endPoint).removeListenerManager(this);
                }
            }
        } else {
            this.endPoint.listeners().removeAll();
        }
    }

    /**
     * Removes all registered listeners (of the object type) from this
     * connection/endpoint to NO LONGER be notified of
     * connect/disconnect/idle/receive(object) events.
     */
    @Override
    public final void removeAll(Class<?> classType) {
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
                        ((EndPointServer)this.endPoint).removeListenerManager(this);
                    }
                }
            }
        } else {
            this.endPoint.listeners().removeAll(classType);
        }
    }

    @Override
    public String toString() {
        return this.channelWrapper.toString();
    }

    @Override
    public int hashCode() {
        return id();
    }

    @Override
    public boolean equals(Object obj) {
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
        } else if (!this.channelWrapper.equals(other.channelWrapper)) {
            return false;
        }
        if (this.name == null) {
            if (other.name != null) {
                return false;
            }
        } else if (!this.name.equals(other.name)) {
            return false;
        }
        return true;
    }
}
