package dorkbox.network.connection;

import com.esotericsoftware.kryo.factories.SerializerFactory;
import dorkbox.network.ConnectionOptions;
import dorkbox.network.connection.bridge.ConnectionBridgeBase;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import dorkbox.network.connection.wrapper.ChannelLocalWrapper;
import dorkbox.network.connection.wrapper.ChannelNetworkWrapper;
import dorkbox.network.connection.wrapper.ChannelWrapper;
import dorkbox.network.pipeline.KryoEncoder;
import dorkbox.network.pipeline.KryoEncoderCrypto;
import dorkbox.network.rmi.RemoteObject;
import dorkbox.network.rmi.Rmi;
import dorkbox.network.rmi.RmiBridge;
import dorkbox.network.rmi.TimeoutException;
import dorkbox.network.util.EndpointTool;
import dorkbox.network.util.KryoSerializationManager;
import dorkbox.network.util.SerializationManager;
import dorkbox.network.util.entropy.Entropy;
import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.NetException;
import dorkbox.network.util.exceptions.SecurityException;
import dorkbox.network.util.serializers.FieldAnnotationAwareSerializer;
import dorkbox.network.util.serializers.IgnoreSerialization;
import dorkbox.network.util.store.NullSettingsStore;
import dorkbox.network.util.store.SettingsStore;
import dorkbox.util.Sys;
import dorkbox.util.collections.IntMap;
import dorkbox.util.collections.IntMap.Entries;
import dorkbox.util.crypto.Crypto;
import dorkbox.util.crypto.serialization.EccPrivateKeySerializer;
import dorkbox.util.crypto.serialization.EccPublicKeySerializer;
import dorkbox.util.crypto.serialization.IesParametersSerializer;
import dorkbox.util.crypto.serialization.IesWithCipherParametersSerializer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.IESParameters;
import org.bouncycastle.crypto.params.IESWithCipherParameters;
import org.slf4j.Logger;

import java.lang.annotation.Annotation;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.AccessControlException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
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


    protected static final String shutdownHookName = "::SHUTDOWN_HOOK::";
    protected static final String stopTreadName = "::STOP_THREAD::";


    /**
     * this can be changed to a more specialized value, if necessary
     */
    public static int DEFAULT_THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors() * 2;
    public static final String LOCAL_CHANNEL = "local_channel";


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
        } catch (AccessControlException ignored) {
        }
    }

    protected final org.slf4j.Logger logger;
    protected final String name;

    // make sure that the endpoint is closed on JVM shutdown (if it's still open at that point in time)
    protected Thread shutdownHook;

    protected final ConnectionManager connectionManager;
    protected final SerializationManager serializationManager;

    protected final RegistrationWrapper registrationWrapper;

    // The remote object space is used by RMI.
    private final RmiBridge rmiBridge;

    // the eventLoop groups are used to track and manage the event loops for startup/shutdown
    private List<EventLoopGroup> eventLoopGroups = new ArrayList<EventLoopGroup>(8);
    private List<ChannelFuture> shutdownChannelList = new ArrayList<ChannelFuture>();

    private final CountDownLatch blockUntilDone = new CountDownLatch(1);
    private final CountDownLatch blockWhileShutdown = new CountDownLatch(1);

    protected final Object shutdownInProgress = new Object();
    protected AtomicBoolean stopCalled = new AtomicBoolean(false);

    protected AtomicBoolean isConnected = new AtomicBoolean(false);

    /**
     * in milliseconds. default is disabled!
     */
    private volatile int idleTimeout = 0;

    private ConcurrentHashMap<Class<?>, EndpointTool> toolMap = new ConcurrentHashMap<Class<?>, EndpointTool>();

    final ECPrivateKeyParameters privateKey;
    final ECPublicKeyParameters publicKey;

    final SecureRandom secureRandom;
    SettingsStore propertyStore;
    boolean disableRemoteKeyValidation;


    public
    EndPoint(String name, ConnectionOptions options) throws InitializationException, SecurityException {
        this.name = name;
        this.logger = org.slf4j.LoggerFactory.getLogger(name);

        this.registrationWrapper = new RegistrationWrapper(this, this.logger);

        // make sure that 'localhost' is REALLY our specific IP address
        if (options.host != null && (options.host.equals("localhost") || options.host.startsWith("127."))) {
            try {
                InetAddress localHostLanAddress = Sys.getLocalHostLanAddress();
                options.host = localHostLanAddress.getHostAddress();
                this.logger.info("Network localhost request, using real IP instead: {}", options.host);
            } catch (UnknownHostException e) {
                this.logger.error("Unable to get the actual 'localhost' IP address", e);
            }
        }

        // we have to be able to specify WHAT property store we want to use, since it can change!
        if (options.settingsStore == null) {
            this.propertyStore = new PropertyStore(name);
        }
        else {
            this.propertyStore = options.settingsStore;
        }

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
                    byte[] seedBytes = Entropy.get("There are no ECC keys for the " + name + " yet");
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
        Runtime.getRuntime().addShutdownHook(this.shutdownHook);


        // serialization stuff
        if (options.serializationManager != null) {
            this.serializationManager = options.serializationManager;
        }
        else {
            this.serializationManager = new KryoSerializationManager();
        }

        // we don't care about un-instantiated/constructed members, since the class type is the only interest.
        this.connectionManager = new ConnectionManager(name, connection0(null).getClass());

        // setup our TCP kryo encoders
        this.registrationWrapper.setKryoTcpEncoder(new KryoEncoder(this.serializationManager));
        this.registrationWrapper.setKryoTcpCryptoEncoder(new KryoEncoderCrypto(this.serializationManager));


        this.serializationManager.setReferences(false);
        this.serializationManager.setRegistrationRequired(true);

        this.serializationManager.register(PingMessage.class);
        this.serializationManager.register(byte[].class);
        this.serializationManager.register(IESParameters.class, new IesParametersSerializer());
        this.serializationManager.register(IESWithCipherParameters.class, new IesWithCipherParametersSerializer());
        this.serializationManager.register(ECPublicKeyParameters.class, new EccPublicKeySerializer());
        this.serializationManager.register(ECPrivateKeyParameters.class, new EccPrivateKeySerializer());
        this.serializationManager.register(Registration.class);


        // ignore fields that have the "IgnoreSerialization" annotation.
        Set<Class<? extends Annotation>> marks = new HashSet<Class<? extends Annotation>>();
        marks.add(IgnoreSerialization.class);
        SerializerFactory disregardingFactory = new FieldAnnotationAwareSerializer.Factory(marks, true);
        this.serializationManager.setDefaultSerializer(disregardingFactory);


        // add the ping listener (internal use only!)
        this.connectionManager.add(new PingSystemListener());

        /*
         * Creates the remote method invocation (RMI) bridge for this endpoint.
         * <p>
         * there is some housekeeping that is necessary BEFORE a connection is actually connected..
         */
        if (options.enableRmi) {
            this.rmiBridge = new RmiBridge(this.logger, this.serializationManager);
        }
        else {
            this.rmiBridge = null;
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
     * The {@link Listener:idle()} will be triggered when neither read nor write
     * has happened for the specified period of time (in milli-seconds)
     * <br>
     * Specify {@code 0} to disable (default).
     */
    public
    void setIdleTimeout(int idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    /**
     * The amount of milli-seconds that must elapse with no read or write before {@link Listener.idle()}
     * will be triggered
     */
    public
    int getIdleTimeout() {
        return this.idleTimeout;
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
    SerializationManager getSerialization() {
        return this.serializationManager;
    }

    /**
     * Gets the remote method invocation (RMI) bridge for this endpoint.
     */
    public
    Rmi rmi() {
        if (this.rmiBridge == null) {
            throw new NetException("Cannot use a remote object space that has NOT been created first! Configure the ConnectionOptions!");
        }

        return this.rmiBridge;
    }

    /**
     * This method allows the connections used by the client/server to be subclassed (custom implementations).
     * <p/>
     * As this is for the network stack, the new connection type MUST subclass {@link Connection}
     *
     * @param bridge null when retrieving the subclass type (internal use only). Non-null when creating a new (and real) connection.
     * @return a new network connection
     */
    public
    Connection newConnection(String name) {
        return new ConnectionImpl(name);
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

        // setup the extras needed by the network connection.
        // These properties are ASSGINED in the same thread that CREATED the object. Only the AES info needs to be
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

            connection = newConnection(this.name);

            // now initialize the connection channels with whatever extra info they might need.
            connection.init(this, new Bridge(wrapper, this.connectionManager));

            metaChannel.connection = connection;

            // notify our remote object space that it is able to receive method calls.
            if (this.rmiBridge != null) {
                connection.listeners().add(this.rmiBridge.getListener());
            }
        }
        else {
            // getting the baseClass

            // have to add the networkAssociate to a map of "connected" computers
            connection = newConnection(this.name);
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
     * Returns a proxy object that implements the specified interfaces. Methods
     * invoked on the proxy object will be invoked remotely on the object with
     * the specified ID in the ObjectSpace for the specified connection. If the
     * remote end of the connection has not {@link #addConnection(Connection)
     * added} the connection to the ObjectSpace, the remote method invocations
     * will be ignored.
     * <p/>
     * Methods that return a value will throw {@link TimeoutException} if the
     * response is not received with the
     * {@link RemoteObject#setResponseTimeout(int) response timeout}.
     * <p/>
     * If {@link RemoteObject#setNonBlocking(boolean) non-blocking} is false
     * (the default), then methods that return a value must not be called from
     * the update thread for the connection. An exception will be thrown if this
     * occurs. Methods with a void return value can be called on the update
     * thread.
     * <p/>
     * If a proxy returned from this method is part of an object graph sent over
     * the network, the object graph on the receiving side will have the proxy
     * object replaced with the registered object.
     *
     * @see RemoteObject
     */
    public
    RemoteObject getRemoteObject(Connection connection, int objectID, Class<?>[] ifaces) {
        return this.rmiBridge.getRemoteObject(connection, objectID, ifaces);
    }

    /**
     * Registers a tool with the server, to be used by other services.
     */
    public
    void registerTool(EndpointTool toolClass) {
        if (toolClass == null) {
            throw new IllegalArgumentException("Tool must not be null! Unable to add tool");
        }

        Class<?>[] interfaces = toolClass.getClass().getInterfaces();
        int length = interfaces.length;
        int index = -1;

        if (length > 1) {
            Class<?> clazz2;
            Class<EndpointTool> cls = EndpointTool.class;

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
        EndpointTool put = this.toolMap.put(clazz, toolClass);
        if (put != null) {
            throw new IllegalArgumentException("Tool must be unique! Unable to add tool");
        }
    }

    /**
     * Only get the tools in the ModuleStart (ie: load) methods. If done in the constructor, the tool might not be available yet
     */
    public
    <T extends EndpointTool> T getTool(Class<?> toolClass) {
        if (toolClass == null) {
            throw new IllegalArgumentException("Tool must not be null! Unable to add tool");
        }

        @SuppressWarnings("unchecked")
        T tool = (T) this.toolMap.get(toolClass);
        return tool;
    }

    public
    String getName() {
        return this.name;
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
     * Safely closes all associated resources/threads/connections
     * <p/>
     * Override stopExtraActions() if you want to provide extra behavior to stopping the endpoint
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
        boolean inEventThread = !threadName.equals(shutdownHookName) && !threadName.equals(stopTreadName);

        // used to check the event groups to see if we are running from one of them. NOW we force to
        // ALWAYS shutdown inside a NEW thread

        if (!inEventThread) {
            stopInThread();
        }
        else {
            Thread thread = new Thread(new Runnable() {
                @Override
                public
                void run() {
                    EndPoint.this.stopInThread();
                    EndPoint.this.blockWhileShutdown.countDown();
                }
            });
            thread.setDaemon(false);
            thread.setName(stopTreadName);
            thread.start();

            // we want to wait for this to finish before we continue
            try {
                this.blockWhileShutdown.await();
            } catch (InterruptedException e) {
                this.logger.error("Thread interrupted while waiting for shutdown to finish!");
            }
        }
    }

    // This actually does the "stopping", since there is some logic to making sure we don't deadlock, this is important
    private final
    void stopInThread() {
        // make sure we are not trying to stop during a startup procedure.
        // This will wait until we have finished starting up/shutting down.
        synchronized (this.shutdownInProgress) {
            close();

            // there is no need to call "stop" again if we close the connection.
            // however, if this is called WHILE from the shutdown hook, blammo! problems!

            // Also, you can call client/server.stop from another thread, which is run when the JVM is shutting down
            // (as there is nothing left to do), and also have problems.
            if (!Thread.currentThread().getName().equals(shutdownHookName)) {
                try {
                    Runtime.getRuntime().removeShutdownHook(this.shutdownHook);
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
                channel.close().awaitUninterruptibly(maxShutdownWaitTimeInMilliSeconds);
            }

            // we have to clear the shutdown list.
            this.shutdownChannelList.clear();

            // we want to WAIT until after the event executors have completed shutting down.
            List<Future<?>> shutdownThreadList = new LinkedList<Future<?>>();

            for (EventLoopGroup loopGroup : this.eventLoopGroups) {
                shutdownThreadList.add(loopGroup.shutdownGracefully());
            }

            // now wait for them to finish!
            for (Future<?> f : shutdownThreadList) {
                f.awaitUninterruptibly(maxShutdownWaitTimeInMilliSeconds);
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
     * Determines if the specified thread (usually the current thread) is a member of a group's threads.
     */
    protected static final
    boolean checkInEventGroup(Thread currentThread, EventLoopGroup group) {
        if (group != null) {
            Set<EventExecutor> children = group.children();
            for (EventExecutor e : children) {
                if (e.inEventLoop(currentThread)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Blocks the current thread until the endpoint has been stopped.
     *
     * @param blockUntilTerminate if TRUE, then this endpoint will block until STOP is called, otherwise it will not block
     */
    public final
    void waitForStop(boolean blockUntilTerminate) {
        if (blockUntilTerminate) {
            // we now BLOCK until the stop method is called.
            try {
                this.blockUntilDone.await();
            } catch (InterruptedException e) {
                this.logger.error("Thread interrupted while waiting for stop!");
            }
        }
    }

    @Override
    public
    String toString() {
        return "EndPoint [" + this.name + "]";
    }

    @Override
    public
    int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (this.name == null ? 0 : this.name.hashCode());
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
        if (this.name == null) {
            if (other.name != null) {
                return false;
            }
        }
        else if (!this.name.equals(other.name)) {
            return false;
        }
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
}
