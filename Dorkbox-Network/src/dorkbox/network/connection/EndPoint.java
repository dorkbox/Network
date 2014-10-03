package dorkbox.network.connection;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;

import java.security.AccessControlException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.slf4j.Logger;

import dorkbox.network.ConnectionOptions;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.rmi.RmiBridge;
import dorkbox.network.util.entropy.Entropy;
import dorkbox.network.util.entropy.SimpleEntropy;
import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.SecurityException;
import dorkbox.network.util.store.NullSettingsStore;
import dorkbox.network.util.store.SettingsStore;
import dorkbox.network.util.udt.UdtEndpointProxy;
import dorkbox.util.collections.IntMap;
import dorkbox.util.collections.IntMap.Entries;
import dorkbox.util.crypto.Crypto;

/** represents the base of a client/server end point */
public abstract class EndPoint {
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
     *  this can be changed to a more specialized value, if necessary
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
     *
     * You could increase or decrease this value to avoid truncated packets
     * or to improve memory footprint respectively.
     *
     * Please also note that a large UDP packet might be truncated or
     * dropped by your router no matter how you configured this option.
     * In UDP, a packet is truncated or dropped if it is larger than a
     * certain size, depending on router configuration.  IPv4 routers
     * truncate and IPv6 routers drop a large packet.  That's why it is
     * safe to send small packets in UDP.
     *
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

            // need to make sure UDT uses our loader instead of the default loader
            try {
                // all of this must be proxied to another class, so THIS class doesn't have unmet dependencies.
                // Annoying and abusing the classloader, but it works well.
                Class.forName("com.barchart.udt.nio.SelectorProviderUDT");
                UdtEndpointProxy.setLibraryLoaderClassName();
            } catch (Throwable ignored) {}

        } catch (AccessControlException ignored) {}
    }

    protected final org.slf4j.Logger logger;
    protected final String name;

    // make sure that the endpoint is closed on JVM shutdown (if it's still open at that point in time)
    protected Thread shutdownHook;

    protected final RegistrationWrapper registrationWrapper;

    // The remote object space is used by RMI.
    protected RmiBridge remoteObjectSpace = null;

    // the eventLoop groups are used to track and manage the event loops for startup/shutdown
    private List<EventLoopGroup> eventLoopGroups = new ArrayList<EventLoopGroup>(8);
    private List<ChannelFuture> shutdownChannelList = new ArrayList<ChannelFuture>();

    private final CountDownLatch blockUntilDone = new CountDownLatch(1);
    protected final Object shutdownInProgress = new Object();

    protected AtomicBoolean isConnected = new AtomicBoolean(false);

    /** in milliseconds. default is disabled! */
    private volatile int idleTimeout = 0;

    final ECPrivateKeyParameters privateKey;
    final ECPublicKeyParameters publicKey;

    final SecureRandom secureRandom;
    SettingsStore propertyStore;
    boolean disableRemoteKeyValidation;


    public EndPoint(String name, ConnectionOptions options) throws InitializationException, SecurityException {
        this.name = name;
        this.logger = org.slf4j.LoggerFactory.getLogger(name);

        this.registrationWrapper = new RegistrationWrapper(this, this.logger);

        // we have to be able to specify WHAT property store we want to use, since it can change!
        if (options.settingsStore == null) {
            this.propertyStore = new PropertyStore(name);
        } else {
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
                    Object entropy = Entropy.init(SimpleEntropy.class);
                    if (!(entropy instanceof SimpleEntropy)) {
                        System.err.println("There are no ECC keys for the " + name + " yet. Please press keyboard keys (numbers/letters/etc) to generate entropy.");
                        System.err.flush();
                    }

                    // seed our RNG based off of this and create our ECC keys
                    byte[] seedBytes = Entropy.get();
                    SecureRandom secureRandom = new SecureRandom(seedBytes);
                    secureRandom.nextBytes(seedBytes);

                    System.err.println("Now generating ECC (" + Crypto.ECC.p521_curve + ") keys. Please wait!");
                    AsymmetricCipherKeyPair generateKeyPair = Crypto.ECC.generateKeyPair(Crypto.ECC.p521_curve, new SecureRandom(seedBytes));

                    privateKey = (ECPrivateKeyParameters) generateKeyPair.getPrivate();
                    publicKey = (ECPublicKeyParameters) generateKeyPair.getPublic();

                    // save to properties file
                    this.propertyStore.savePrivateKey(privateKey);
                    this.propertyStore.savePublicKey(publicKey);

                    System.err.println("Done with ECC keys!");
                } catch (Exception e) {
                    String message = "Unable to initialize/generate ECC keys. FORCED SHUTDOWN.";
                    this.logger.error(message);
                    throw new InitializationException(message);
                }
            }

            this.privateKey = privateKey;
            this.publicKey = publicKey;
        } else {
            this.privateKey = null;
            this.publicKey = null;
        }


        this.secureRandom = new SecureRandom(this.propertyStore.getSalt());

        this.shutdownHook = new Thread() {
            @Override
            public void run() {
                EndPoint.this.stop();
            }
        };
        this.shutdownHook.setName(shutdownHookName);
        Runtime.getRuntime().addShutdownHook(this.shutdownHook);
    }

    public void disableRemoteKeyValidation() {
        Logger logger2 = this.logger;

        if (isConnected()) {
            logger2.error("Cannot disable the remote key validation after this endpoint is connected!");
        } else {
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
    public <T extends SettingsStore> T getPropertyStore() {
        return (T) this.propertyStore;
    }

    /**
     * TODO maybe remove this? method call is used by jetty ssl
     * @return the ECC public key in use by this endpoint
     */
    public ECPrivateKeyParameters getPrivateKey() {
        return this.privateKey;
    }

    /**
     * TODO maybe remove this? method call is used by jetty ssl
     * @return the ECC private key in use by this endpoint
     */
    public ECPublicKeyParameters getPublicKey() {
        return this.publicKey;
    }

    /**
     * Internal call by the pipeline to notify the client to continue registering the different session protocols.
     * The server does not use this.
     */
    protected boolean continueRegistration0() {
        return true;
    }

    /**
     *  The {@link Listener:idle()} will be triggered when neither read nor write
     *  has happened for the specified period of time (in milli-seconds)
     *  <br>
     *  Specify {@code 0} to disable (default).
     */
    public void setIdleTimeout(int idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    /**
     *  The amount of milli-seconds that must elapse with no read or write before {@link Listener.idle()}
     *  will be triggered
     */
    public int getIdleTimeout() {
        return this.idleTimeout;
    }

    /**
     * Return the connection status of this endpoint.
     * <p>
     * Once a server has connected to ANY client, it will always return true until server.close() is called
     */
    public final boolean isConnected() {
        return this.isConnected.get();
    }

    /**
     * Add a channel future to be tracked and managed for shutdown.
     */
    protected final void manageForShutdown(ChannelFuture future) {
        synchronized (this.shutdownChannelList) {
            this.shutdownChannelList.add(future);
        }
    }

    /**
     * Add an eventloop group to be tracked & managed for shutdown
     */
    protected final void manageForShutdown(EventLoopGroup loopGroup) {
        synchronized (this.eventLoopGroups) {
            this.eventLoopGroups.add(loopGroup);
        }
    }

    /**
     * Closes all connections ONLY (keeps the server/client running).
     * <p>
     * This is used, for example, when reconnecting to a server. The server should ALWAYS use STOP.
     */
    public void close() {
        // give a chance to other threads.
        Thread.yield();

        this.isConnected.set(false);
    }

    protected final String stopWithErrorMessage(Logger logger2, String errorMessage, Throwable throwable) {
        if (logger2.isDebugEnabled() && throwable != null) {
            // extra info if debug is enabled
            logger2.error(errorMessage, throwable.getCause());
        } else {
            logger2.error(errorMessage);
        }

        stop();
        return errorMessage;
    }

    /**
     * Safely closes all associated resources/threads/connections
     */
    public void stop() {
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
        } else {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    EndPoint.this.stopInThread();
                }
            });
            thread.setDaemon(false);
            thread.setName(stopTreadName);
            thread.start();
        }
    }

    // This actually does the "stopping", since there is some logic to making sure we don't deadlock, this is important
    private final void stopInThread() {
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

            stopExtraActions();

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
        }

        // tell the blocked "bind" method that it may continue (and exit)
        this.blockUntilDone.countDown();
    }

    /**
     * Extra actions to perform when stopping this endpoint.
     */
    void stopExtraActions() {
    }

    public String getName() {
        return this.name;
    }

    /**
     * Determines if the specified thread (usually the current thread) is a member of a group's threads.
     */
    protected static final boolean checkInEventGroup(Thread currentThread, EventLoopGroup group) {
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
    public final void waitForStop(boolean blockUntilTerminate) {
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
    public String toString() {
        return "EndPoint [" + this.name + "]";
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (this.name == null ? 0 : this.name.hashCode());
        result = prime * result + (this.privateKey == null ? 0 : this.privateKey.hashCode());
        result = prime * result + (this.publicKey == null ? 0 : this.publicKey.hashCode());
        return result;
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
        EndPoint other = (EndPoint) obj;
        if (this.name == null) {
            if (other.name != null) {
                return false;
            }
        } else if (!this.name.equals(other.name)) {
            return false;
        }
        if (this.privateKey == null) {
            if (other.privateKey != null) {
                return false;
            }
        } else if (!Crypto.ECC.compare(this.privateKey, other.privateKey)) {
            return false;
        }
        if (this.publicKey == null) {
            if (other.publicKey != null) {
                return false;
            }
        } else if (!Crypto.ECC.compare(this.publicKey, other.publicKey)) {
            return false;
        }
        return true;
    }
}
