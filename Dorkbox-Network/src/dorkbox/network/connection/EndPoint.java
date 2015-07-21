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

import dorkbox.network.Configuration;
import dorkbox.network.connection.bridge.ConnectionBridgeBase;
import dorkbox.network.connection.ping.PingSystemListener;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.wrapper.ChannelLocalWrapper;
import dorkbox.network.connection.wrapper.ChannelNetworkWrapper;
import dorkbox.network.connection.wrapper.ChannelWrapper;
import dorkbox.network.pipeline.KryoEncoder;
import dorkbox.network.pipeline.KryoEncoderCrypto;
import dorkbox.network.rmi.RmiBridge;
import dorkbox.network.util.CryptoSerializationManager;
import dorkbox.network.util.EndPointTool;
import dorkbox.network.util.store.NullSettingsStore;
import dorkbox.network.util.store.SettingsStore;
import dorkbox.util.Sys;
import dorkbox.util.collections.IntMap;
import dorkbox.util.collections.IntMap.Entries;
import dorkbox.util.crypto.Crypto;
import dorkbox.util.entropy.Entropy;
import dorkbox.util.exceptions.InitializationException;
import dorkbox.util.exceptions.SecurityException;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * represents the base of a client/server end point
 */
public abstract
class EndPoint {
    // If TCP and UDP both fill the pipe, THERE WILL BE FRAGMENTATION and dropped UDP packets!
    // it results in severe UDP packet loss and contention.
    //
    // http://www.isoc.org/INET97/proceedings/F3/F3_1.HTM
    // also, a google search on just "INET97/proceedings/F3/F3_1.HTM" turns up interesting problems.
    // Usually it's with ISPs.

    // TODO: will also want an UDP keepalive? (TCP is already there b/c of socket options, but might need a heartbeat to detect dead connections?)
    //          routers sometimes need a heartbeat to keep the connection
    // TODO: maybe some sort of STUN-like connection keep-alive??


    // TODO: do we really need this? Maybe?
    public static final String LOCAL_CHANNEL = "local_channel";
    protected static final String shutdownHookName = "::SHUTDOWN_HOOK::";
    protected static final String stopTreadName = "::STOP_THREAD::";
    /**
     * Shall we REALLY use a valid lan IP address instead of the loopback address? A common mistake is to listen on localhost, which does
     * not accept external network connections
     */
    public static boolean useLanIpInsteadOfLoopback = true;
    /**
     * this can be changed to a more specialized value, if necessary
     */
    public static int DEFAULT_THREAD_POOL_SIZE = Runtime.getRuntime()
                                                        .availableProcessors() * 2;
    /**
     * The amount of time in milli-seconds to wait for this endpoint to close all
     * {@link Channel}s and shutdown gracefully.
     */
    public static long maxShutdownWaitTimeInMilliSeconds = 2000L; // in milliseconds

    /**
     * The default size for UDP packets is 768 bytes.
     * <p/>
     * You could increase or decrease this value to avoid truncated packets
     * or to improve memory footprint respectively.
     * <p/>
     * Please also note that a large UDP packet might be truncated or
     * dropped by your router no matter how you configured this option.
     * In UDP, a packet is truncated or dropped if it is larger than a
     * certain size, depending on router configuration.  IPv4 routers
     * truncate and IPv6 routers drop a large packet.  That's why it is
     * safe to send small packets in UDP.
     * <p/>
     * 512 is recommended to prevent fragmentation.
     * This can be set higher on an internal lan! (or use UDT to make UDP transfers easy)
     */
    public static int udpMaxSize = 512;


    static {
        try {
            // doesn't work in eclipse.
            // Needed for NIO selectors on Android 2.2, and to force IPv4.
            System.setProperty("java.net.preferIPv4Stack", Boolean.TRUE.toString());
            System.setProperty("java.net.preferIPv6Addresses", Boolean.FALSE.toString());
        } catch (Throwable ignored) {
        }
    }

    protected final org.slf4j.Logger logger;
    protected final Class<? extends EndPoint> type;

    protected final ConnectionManager connectionManager;
    protected final CryptoSerializationManager serializationManager;
    protected final RegistrationWrapper registrationWrapper;

    protected final Object shutdownInProgress = new Object();
    final ECPrivateKeyParameters privateKey;
    final ECPublicKeyParameters publicKey;

    final SecureRandom secureRandom;
    final RmiBridge globalRmiBridge;

    private final CountDownLatch blockUntilDone = new CountDownLatch(1);

    private final Executor rmiExecutor;
    private final boolean rmiEnabled;
    // the eventLoop groups are used to track and manage the event loops for startup/shutdown
    private final List<EventLoopGroup> eventLoopGroups = new ArrayList<EventLoopGroup>(8);
    private final List<ChannelFuture> shutdownChannelList = new ArrayList<ChannelFuture>();
    private final ConcurrentHashMap<Class<?>, EndPointTool> toolMap = new ConcurrentHashMap<Class<?>, EndPointTool>();

    // make sure that the endpoint is closed on JVM shutdown (if it's still open at that point in time)
    protected Thread shutdownHook;

    protected AtomicBoolean stopCalled = new AtomicBoolean(false);
    protected AtomicBoolean isConnected = new AtomicBoolean(false);

    SettingsStore propertyStore;
    boolean disableRemoteKeyValidation;

    /**
     * in milliseconds. default is disabled!
     */
    private volatile int idleTimeoutMs = 0;


    /**
     * @param type    this is either "Client" or "Server", depending on who is creating this endpoint.
     * @param options these are the specific connection options
     * @throws InitializationException
     * @throws SecurityException
     */
    public
    EndPoint(Class<? extends EndPoint> type, final Configuration options) throws InitializationException, SecurityException, IOException {
        this.type = type;

        this.logger = org.slf4j.LoggerFactory.getLogger(type);

        this.registrationWrapper = new RegistrationWrapper(this,
                                                           this.logger); // TODO - get rid of the wrapper, since it just loops back on itself

        // make sure that 'localhost' is REALLY our specific IP address
        if (useLanIpInsteadOfLoopback && options.host != null && (options.host.equals("localhost") || options.host.startsWith("127."))) {
            try {
                InetAddress localHostLanAddress = Sys.getLocalHostLanAddress();
                options.host = localHostLanAddress.getHostAddress();
                this.logger.info("Network localhost request, using real IP instead: {}", options.host);
            } catch (UnknownHostException e) {
                this.logger.error("Unable to get the actual 'localhost' IP address", e);
            }
        }

        // serialization stuff
        this.serializationManager = KryoCryptoSerializationManager.DEFAULT;

        rmiEnabled = options.rmiEnabled;
        if (rmiEnabled) {
            // setup our RMI serialization managers. Can only be called once
            serializationManager.initRmiSerialization();
        }

        rmiExecutor = options.rmiExecutor;


        // setup our TCP kryo encoders
        this.registrationWrapper.setKryoTcpEncoder(new KryoEncoder(this.serializationManager));
        this.registrationWrapper.setKryoTcpCryptoEncoder(new KryoEncoderCrypto(this.serializationManager));


        // we have to be able to specify WHAT property store we want to use, since it can change!
        if (options.settingsStore == null) {
            this.propertyStore = new PropertyStore();
        }
        else {
            this.propertyStore = options.settingsStore;
        }

        this.propertyStore.init(type, this.serializationManager, null);

        // null it out, since it is sensitive!
        options.settingsStore = null;


        if (!(this.propertyStore instanceof NullSettingsStore)) {
            // initialize the private/public keys used for negotiating ECC handshakes
            // these are ONLY used for IP connections. LOCAL connections do not need a handshake!
            ECPrivateKeyParameters privateKey = this.propertyStore.getPrivateKey();
            ECPublicKeyParameters publicKey = this.propertyStore.getPublicKey();

            if (privateKey == null || publicKey == null) {
                try {
                    // seed our RNG based off of this and create our ECC keys
                    byte[] seedBytes = Entropy.get("There are no ECC keys for the " + type.getSimpleName() + " yet");
                    SecureRandom secureRandom = new SecureRandom(seedBytes);
                    secureRandom.nextBytes(seedBytes);

                    this.logger.debug("Now generating ECC (" + Crypto.ECC.p521_curve + ") keys. Please wait!");
                    AsymmetricCipherKeyPair generateKeyPair = Crypto.ECC.generateKeyPair(Crypto.ECC.p521_curve, secureRandom);

                    privateKey = (ECPrivateKeyParameters) generateKeyPair.getPrivate();
                    publicKey = (ECPublicKeyParameters) generateKeyPair.getPublic();

                    // save to properties file
                    this.propertyStore.savePrivateKey(privateKey);
                    this.propertyStore.savePublicKey(publicKey);

                    this.logger.debug("Done with ECC keys!");
                } catch (Exception e) {
                    String message = "Unable to initialize/generate ECC keys. FORCED SHUTDOWN.";
                    this.logger.error(message);
                    throw new InitializationException(message);
                }
            }

            this.privateKey = privateKey;
            this.publicKey = publicKey;
        }
        else {
            this.privateKey = null;
            this.publicKey = null;
        }


        this.secureRandom = new SecureRandom(this.propertyStore.getSalt());

        this.shutdownHook = new Thread() {
            @Override
            public
            void run() {
                // connectionManager.shutdown accurately reflects the state of the app. Safe to use here
                if (EndPoint.this.connectionManager != null && !EndPoint.this.connectionManager.shutdown) {
                    EndPoint.this.stop();
                }
            }
        };
        this.shutdownHook.setName(shutdownHookName);
        try {
            Runtime.getRuntime()
                   .addShutdownHook(this.shutdownHook);
        } catch (Throwable ignored) {
            // if we are in the middle of shutdown, we cannot do this.
        }


        // we don't care about un-instantiated/constructed members, since the class type is the only interest.
        this.connectionManager = new ConnectionManager(type.getSimpleName(), connection0(null).getClass());

        // add the ping listener (internal use only!)
        this.connectionManager.add(new PingSystemListener());

        if (this.rmiEnabled) {
            // these register the listener for registering a class implementation for RMI (internal use only)
            this.connectionManager.add(new RegisterRmiSystemListener());
            this.globalRmiBridge = new RmiBridge(logger, options.rmiExecutor, true);
        }
        else {
            this.globalRmiBridge = null;
        }
    }

    public
    void disableRemoteKeyValidation() {
        Logger logger2 = this.logger;

        if (isConnected()) {
            logger2.error("Cannot disable the remote key validation after this endpoint is connected!");
        }
        else {
            if (logger2.isInfoEnabled()) {
                logger2.info("WARNING: Disabling remote key validation is a security risk!!");
            }
            this.disableRemoteKeyValidation = true;
        }
    }

    /**
     * Returns the property store used by this endpoint. The property store can store via properties,
     * a database, etc, or can be a "null" property store, which does nothing
     */
    @SuppressWarnings("unchecked")
    public
    <T extends SettingsStore> T getPropertyStore() {
        return (T) this.propertyStore;
    }

    /**
     * TODO maybe remove this? method call is used by jetty ssl
     *
     * @return the ECC public key in use by this endpoint
     */
    public
    ECPrivateKeyParameters getPrivateKey() {
        return this.privateKey;
    }

    /**
     * TODO maybe remove this? method call is used by jetty ssl
     *
     * @return the ECC private key in use by this endpoint
     */
    public
    ECPublicKeyParameters getPublicKey() {
        return this.publicKey;
    }

    /**
     * Internal call by the pipeline to notify the client to continue registering the different session protocols.
     * The server does not use this.
     */
    protected
    boolean registerNextProtocol0() {
        return true;
    }

    /**
     * The amount of milli-seconds that must elapse with no read or write before {@link ListenerRaw#idle(Connection)} }
     * will be triggered
     */
    public
    int getIdleTimeout() {
        return this.idleTimeoutMs;
    }

    /**
     * The {@link Listener:idle()} will be triggered when neither read nor write
     * has happened for the specified period of time (in milli-seconds)
     * <br>
     * Specify {@code 0} to disable (default).
     */
    public
    void setIdleTimeout(int idleTimeoutMs) {
        this.idleTimeoutMs = idleTimeoutMs;
    }

    /**
     * Return the connection status of this endpoint.
     * <p/>
     * Once a server has connected to ANY client, it will always return true until server.close() is called
     */
    public final
    boolean isConnected() {
        return this.isConnected.get();
    }

    /**
     * Add a channel future to be tracked and managed for shutdown.
     */
    protected final
    void manageForShutdown(ChannelFuture future) {
        synchronized (this.shutdownChannelList) {
            this.shutdownChannelList.add(future);
        }
    }

    /**
     * Add an eventloop group to be tracked & managed for shutdown
     */
    protected final
    void manageForShutdown(EventLoopGroup loopGroup) {
        synchronized (this.eventLoopGroups) {
            this.eventLoopGroups.add(loopGroup);
        }
    }

    /**
     * Returns the serialization wrapper if there is an object type that needs to be added outside of the basics.
     */
    public
    CryptoSerializationManager getSerialization() {
        return this.serializationManager;
    }

    /**
     * This method allows the connections used by the client/server to be subclassed (custom implementations).
     * <p/>
     * As this is for the network stack, the new connection MUST subclass {@link Connection}
     * <p/>
     * The parameters are ALL NULL when getting the base class, as this instance is just thrown away.
     *
     * @return a new network connection
     */
    public
    Connection newConnection(final Logger logger, final EndPoint endPoint, final RmiBridge rmiBridge) {
        return new ConnectionImpl(logger, endPoint, rmiBridge);
    }

    /**
     * Internal call by the pipeline when:
     * - creating a new network connection
     * - when determining the baseClass for listeners
     *
     * @param metaChannel can be NULL (when getting the baseClass)
     */
    protected final
    Connection connection0(MetaChannel metaChannel) {
        Connection connection;

        RmiBridge rmiBridge = null;
        if (metaChannel != null && rmiEnabled) {
            rmiBridge = new RmiBridge(logger, rmiExecutor, false);
        }

        // setup the extras needed by the network connection.
        // These properties are ASSIGNED in the same thread that CREATED the object. Only the AES info needs to be
        // volatile since it is the only thing that changes.
        if (metaChannel != null) {
            ChannelWrapper wrapper;

            if (metaChannel.localChannel != null) {
                wrapper = new ChannelLocalWrapper(metaChannel);
            }
            else {
                if (this instanceof EndPointServer) {
                    wrapper = new ChannelNetworkWrapper(metaChannel, this.registrationWrapper);
                }
                else {
                    wrapper = new ChannelNetworkWrapper(metaChannel, null);
                }
            }

            connection = newConnection(logger, this, rmiBridge);
            metaChannel.connection = connection;

            // now initialize the connection channels with whatever extra info they might need.
            connection.init(new Bridge(wrapper, this.connectionManager));

            if (rmiBridge != null) {
                // notify our remote object space that it is able to receive method calls.
                connection.listeners()
                          .add(rmiBridge.getListener());
            }
        }
        else {
            // getting the connection baseClass

            // have to add the networkAssociate to a map of "connected" computers
            connection = newConnection(null, null, null);
        }

        return connection;
    }

    /**
     * Internal call by the pipeline to notify the "Connection" object that it has "connected", meaning that modifications
     * to the pipeline are finished.
     * <p/>
     * Only the CLIENT injects in front of this)
     */
    void connectionConnected0(Connection connection) {
        this.isConnected.set(true);

        // prep the channel wrapper
        connection.prep();

        this.connectionManager.connectionConnected(connection);
    }

    /**
     * Expose methods to modify the listeners (connect/disconnect/idle/receive events).
     */
    public final
    ListenerBridge listeners() {
        return this.connectionManager;
    }

    /**
     * Returns a non-modifiable list of active connections
     */
    public
    List<Connection> getConnections() {
        return this.connectionManager.getConnections();
    }

    /**
     * Returns a non-modifiable list of active connections
     */
    @SuppressWarnings("unchecked")
    public
    <C extends Connection> Collection<C> getConnectionsAs() {
        return (Collection<C>) this.connectionManager.getConnections();
    }

    /**
     * Expose methods to send objects to a destination.
     */
    public abstract
    ConnectionBridgeBase send();

    /**
     * Registers a tool with the server, to be used by other services.
     */
    public
    void registerTool(EndPointTool toolClass) {
        if (toolClass == null) {
            throw new IllegalArgumentException("Tool must not be null! Unable to add tool");
        }

        Class<?>[] interfaces = toolClass.getClass()
                                         .getInterfaces();
        int length = interfaces.length;
        int index = -1;

        if (length > 1) {
            Class<?> clazz2;
            Class<EndPointTool> cls = EndPointTool.class;

            for (int i = 0; i < length; i++) {
                clazz2 = interfaces[i];
                if (cls.isAssignableFrom(clazz2)) {
                    index = i;
                    break;
                }
            }

            if (index == -1) {
                throw new IllegalArgumentException("Unable to discover tool interface! WHOOPS!");
            }
        }
        else {
            index = 0;
        }

        Class<?> clazz = interfaces[index];
        EndPointTool put = this.toolMap.put(clazz, toolClass);
        if (put != null) {
            throw new IllegalArgumentException("Tool must be unique! Unable to add tool");
        }
    }

    /**
     * Only get the tools in the ModuleStart (ie: load) methods. If done in the constructor, the tool might not be available yet
     */
    public
    <T extends EndPointTool> T getTool(Class<?> toolClass) {
        if (toolClass == null) {
            throw new IllegalArgumentException("Tool must not be null! Unable to add tool");
        }

        @SuppressWarnings("unchecked")
        T tool = (T) this.toolMap.get(toolClass);
        return tool;
    }

    /**
     * Closes all connections ONLY (keeps the server/client running).
     * <p/>
     * This is used, for example, when reconnecting to a server. The server should ALWAYS use STOP.
     */
    public
    void close() {
        // give a chance to other threads.
        Thread.yield();

        // stop does the same as this + more
        this.connectionManager.closeConnections();

        this.isConnected.set(false);
    }

    protected final
    String stopWithErrorMessage(Logger logger2, String errorMessage, Throwable throwable) {
        if (logger2.isDebugEnabled() && throwable != null) {
            // extra info if debug is enabled
            logger2.error(errorMessage, throwable.getCause());
        }
        else {
            logger2.error(errorMessage);
        }

        stop();
        return errorMessage;
    }

    /**
     * Safely closes all associated resources/threads/connections.
     * <p/>
     * If we want to WAIT for this endpoint to shutdown, we must explicitly call waitForShutdown()
     * <p/>
     * Override stopExtraActions() if you want to provide extra behavior while stopping the endpoint
     */
    public final
    void stop() {
        // only permit us to "stop" once!
        if (!this.stopCalled.compareAndSet(false, true)) {
            return;
        }

        // check to make sure we are in our OWN thread, otherwise, this thread will never exit -- because it will wait indefinitely
        // for itself to finish (since it blocks itself).
        // This occurs when calling stop from within a listener callback.
        Thread currentThread = Thread.currentThread();
        String threadName = currentThread.getName();
        boolean inShutdownThread = !threadName.equals(shutdownHookName) && !threadName.equals(stopTreadName);

        // used to check the event groups to see if we are running from one of them. NOW we force to
        // ALWAYS shutdown inside a NEW thread

        if (!inShutdownThread) {
            stopInThread();
        }
        else {
            // we have to make sure always run this from within it's OWN thread -- because if it's run from within
            // a client/server thread executor, it will deadlock while waiting for the threadpool to terminate.
            boolean isInEventLoop = false;
            for (EventLoopGroup loopGroup : this.eventLoopGroups) {
                for (EventExecutor child : loopGroup.children()) {
                    if (child.inEventLoop()) {
                        isInEventLoop = true;
                        break;
                    }
                }
            }

            if (!isInEventLoop) {
                EndPoint.this.stopInThread();
            }
            else {
                Thread thread = new Thread(new Runnable() {
                    @Override
                    public
                    void run() {
                        EndPoint.this.stopInThread();
                    }
                });
                thread.setDaemon(false);
                thread.setName(stopTreadName);
                thread.start();
            }
        }
    }

    // This actually does the "stopping", since there is some logic to making sure we don't deadlock, this is important
    private
    void stopInThread() {
        // make sure we are not trying to stop during a startup procedure.
        // This will wait until we have finished starting up/shutting down.
        synchronized (this.shutdownInProgress) {
            close();

            this.logger.info("Stopping endpoint");

            // there is no need to call "stop" again if we close the connection.
            // however, if this is called WHILE from the shutdown hook, blammo! problems!

            // Also, you can call client/server.stop from another thread, which is run when the JVM is shutting down
            // (as there is nothing left to do), and also have problems.
            if (!Thread.currentThread()
                       .getName()
                       .equals(shutdownHookName)) {
                try {
                    Runtime.getRuntime()
                           .removeShutdownHook(this.shutdownHook);
                } catch (Exception e) {
                    // ignore
                }
            }

            this.connectionManager.stop();

            // Sometimes there might be "lingering" connections (ie, halfway though registration) that need to be closed.
            long maxShutdownWaitTimeInMilliSeconds = EndPoint.maxShutdownWaitTimeInMilliSeconds;
            RegistrationWrapper registrationWrapper2 = this.registrationWrapper;
            try {
                IntMap<MetaChannel> channelMap = registrationWrapper2.getAndLockChannelMap();
                Entries<MetaChannel> entries = channelMap.entries();
                while (entries.hasNext()) {
                    MetaChannel metaChannel = entries.next().value;
                    metaChannel.close(maxShutdownWaitTimeInMilliSeconds);
                    Thread.yield();
                }

                channelMap.clear();

            } finally {
                registrationWrapper2.releaseChannelMap();
            }


            // shutdown the database store
            this.propertyStore.shutdown();

            // now we stop all of our channels
            for (ChannelFuture f : this.shutdownChannelList) {
                Channel channel = f.channel();
                channel.close()
                       .awaitUninterruptibly(maxShutdownWaitTimeInMilliSeconds);
                Thread.yield();
            }

            // we have to clear the shutdown list.
            this.shutdownChannelList.clear();

            // we want to WAIT until after the event executors have completed shutting down.
            List<Future<?>> shutdownThreadList = new LinkedList<Future<?>>();

            for (EventLoopGroup loopGroup : this.eventLoopGroups) {
                shutdownThreadList.add(loopGroup.shutdownGracefully(maxShutdownWaitTimeInMilliSeconds,
                                                                    maxShutdownWaitTimeInMilliSeconds * 4,
                                                                    TimeUnit.MILLISECONDS));
                Thread.yield();
            }

            // now wait for them to finish!
            // It can take a few seconds to shut down the executor. This will affect unit testing, where connections are quickly created/stopped
            for (Future<?> f : shutdownThreadList) {
                f.syncUninterruptibly();
                Thread.yield();
            }

            // when the eventloop closes, the associated selectors are ALSO closed!
            stopExtraActions();
        }

        // tell the blocked "bind" method that it may continue (and exit)
        this.blockUntilDone.countDown();
    }

    /**
     * Extra EXTERNAL actions to perform when stopping this endpoint.
     */
    public
    void stopExtraActions() {
    }

    /**
     * Blocks the current thread until the endpoint has been stopped. If the endpoint is already stopped, this do nothing.
     */
    public final
    void waitForShutdown() {
        // we now BLOCK until the stop method is called.
        try {
            this.blockUntilDone.await();
        } catch (InterruptedException e) {
            this.logger.error("Thread interrupted while waiting for stop!");
        }
    }

    @Override
    public
    int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (this.privateKey == null ? 0 : this.privateKey.hashCode());
        result = prime * result + (this.publicKey == null ? 0 : this.publicKey.hashCode());
        return result;
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
        EndPoint other = (EndPoint) obj;

        if (this.privateKey == null) {
            if (other.privateKey != null) {
                return false;
            }
        }
        else if (!Crypto.ECC.compare(this.privateKey, other.privateKey)) {
            return false;
        }
        if (this.publicKey == null) {
            if (other.publicKey != null) {
                return false;
            }
        }
        else if (!Crypto.ECC.compare(this.publicKey, other.publicKey)) {
            return false;
        }
        return true;
    }

    @Override
    public
    String toString() {
        return "EndPoint [" + getName() + "]";
    }

    public
    String getName() {
        return this.type.getSimpleName();
    }

    /**
     * Creates a "global" RMI object for use by multiple connections.
     * @return the ID assigned to this RMI object
     */
    public
    <T> int createGlobalObject(final T globalObject) {
        int globalObjectId = globalRmiBridge.nextObjectId();
        globalRmiBridge.register(globalObjectId, globalObject);
        return globalObjectId;
    }
}
