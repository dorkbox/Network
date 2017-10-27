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
import java.security.SecureRandom;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.slf4j.Logger;

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
import dorkbox.network.store.NullSettingsStore;
import dorkbox.network.store.SettingsStore;
import dorkbox.util.Property;
import dorkbox.util.crypto.CryptoECC;
import dorkbox.util.entropy.Entropy;
import dorkbox.util.exceptions.InitializationException;
import dorkbox.util.exceptions.SecurityException;
import io.netty.util.NetUtil;

/**
 * represents the base of a client/server end point
 */
public abstract
class EndPointBase<C extends Connection> extends EndPoint {
    // If TCP and UDP both fill the pipe, THERE WILL BE FRAGMENTATION and dropped UDP packets!
    // it results in severe UDP packet loss and contention.
    //
    // http://www.isoc.org/INET97/proceedings/F3/F3_1.HTM
    // also, a google search on just "INET97/proceedings/F3/F3_1.HTM" turns up interesting problems.
    // Usually it's with ISPs.

    // TODO: will also want an UDP keepalive? (TCP is already there b/c of socket options, but might need a heartbeat to detect dead connections?)
    //          routers sometimes need a heartbeat to keep the connection
    // TODO: maybe some sort of STUN-like connection keep-alive??


    public static final String LOCAL_CHANNEL = "local_channel";

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
     * To fit into that magic 576-byte MTU and avoid fragmentation, your
     * UDP payload should be restricted by 576-60-8=508 bytes.
     *
     * This can be set higher on an internal lan!
     *
     * DON'T go higher that 1400 over the internet, but 9k is possible
     * with jumbo frames on a local network (if it's supported)
     */
    @Property
    public static int udpMaxSize = 508;

    protected final ConnectionManager<C> connectionManager;
    protected final dorkbox.network.util.CryptoSerializationManager serializationManager;
    protected final RegistrationWrapper<C> registrationWrapper;

    final ECPrivateKeyParameters privateKey;
    final ECPublicKeyParameters publicKey;

    final SecureRandom secureRandom;
    final RmiBridge globalRmiBridge;

    private final Executor rmiExecutor;
    private final boolean rmiEnabled;

    SettingsStore propertyStore;
    boolean disableRemoteKeyValidation;

    /**
     * in milliseconds. default is disabled!
     */
    private volatile int idleTimeoutMs = 0;

    private AtomicBoolean isConnected = new AtomicBoolean(false);


    /**
     * @param type    this is either "Client" or "Server", depending on who is creating this endpoint.
     * @param options these are the specific connection options
     *
     * @throws InitializationException
     * @throws SecurityException
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public
    EndPointBase(Class<? extends EndPointBase> type, final Configuration options) throws InitializationException, SecurityException, IOException {
        super(type);

        // make sure that 'localhost' is ALWAYS our specific loopback IP address
        if (options.host != null && (options.host.equals("localhost") || options.host.startsWith("127."))) {
            // localhost IP might not always be 127.0.0.1
            options.host = NetUtil.LOCALHOST.getHostAddress();
        }

        // serialization stuff
        if (options.serialization != null) {
            this.serializationManager = options.serialization;
        } else {
            this.serializationManager = CryptoSerializationManager.DEFAULT();
        }

        // setup our RMI serialization managers. Can only be called once
        rmiEnabled = serializationManager.initRmiSerialization();
        rmiExecutor = options.rmiExecutor;


        // The registration wrapper permits the registration process to access protected/package fields/methods, that we don't want
        // to expose to external code. "this" escaping can be ignored, because it is benign.
        //noinspection ThisEscapedInObjectConstruction
        this.registrationWrapper = new RegistrationWrapper(this,
                                                           this.logger,
                                                           new KryoEncoder(this.serializationManager),
                                                           new KryoEncoderCrypto(this.serializationManager));


        // we have to be able to specify WHAT property store we want to use, since it can change!
        if (options.settingsStore == null) {
            this.propertyStore = new PropertyStore();
        }
        else {
            this.propertyStore = options.settingsStore;
        }

        this.propertyStore.init(this.serializationManager, null);

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

                    this.logger.debug("Now generating ECC (" + CryptoECC.curve25519 + ") keys. Please wait!");
                    AsymmetricCipherKeyPair generateKeyPair = CryptoECC.generateKeyPair(CryptoECC.curve25519, secureRandom);

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

        // we don't care about un-instantiated/constructed members, since the class type is the only interest.
        this.connectionManager = new ConnectionManager<C>(type.getSimpleName(), connection0(null).getClass());

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

        serializationManager.finishInit();
    }

    /**
     * Disables remote endpoint public key validation when the connection is established. This is not recommended as it is a security risk
     */
    public
    void disableRemoteKeyValidation() {
        Logger logger2 = this.logger;

        if (isConnected()) {
            logger2.error("Cannot disable the remote key validation after this endpoint is connected!");
        }
        else {
            logger2.info("WARNING: Disabling remote key validation is a security risk!!");
            this.disableRemoteKeyValidation = true;
        }
    }

    /**
     * Returns the property store used by this endpoint. The property store can store via properties,
     * a database, etc, or can be a "null" property store, which does nothing
     */
    @SuppressWarnings("unchecked")
    public
    <S extends SettingsStore> S getPropertyStore() {
        return (S) this.propertyStore;
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
     * The amount of milli-seconds that must elapse with no read or write before {@link Listener.OnIdle#idle(Connection)} }
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
     * Returns the serialization wrapper if there is an object type that needs to be added outside of the basics.
     */
    public
    dorkbox.network.util.CryptoSerializationManager getSerialization() {
        return this.serializationManager;
    }

    /**
     * This method allows the connections used by the client/server to be subclassed (custom implementations).
     * <p/>
     * As this is for the network stack, the new connection MUST subclass {@link ConnectionImpl}
     * <p/>
     * The parameters are ALL NULL when getting the base class, as this instance is just thrown away.
     *
     * @return a new network connection
     */
    protected
    ConnectionImpl newConnection(final Logger logger, final EndPointBase<C> endPointBaseConnection, final RmiBridge rmiBridge) {
        return new ConnectionImpl(logger, endPointBaseConnection, rmiBridge);
    }

    /**
     * Internal call by the pipeline when:
     * - creating a new network connection
     * - when determining the baseClass for listeners
     *
     * @param metaChannel can be NULL (when getting the baseClass)
     */
    @SuppressWarnings("unchecked")
    protected final
    Connection connection0(MetaChannel metaChannel) {
        ConnectionImpl connection;

        RmiBridge rmiBridge = null;
        if (metaChannel != null && rmiEnabled) {
            rmiBridge = new RmiBridge(logger, rmiExecutor, false);
        }

        // setup the extras needed by the network connection.
        // These properties are ASSIGNED in the same thread that CREATED the object. Only the AES info needs to be
        // volatile since it is the only thing that changes.
        if (metaChannel != null) {
            ChannelWrapper<C> wrapper;

            connection = newConnection(logger, this, rmiBridge);
            metaChannel.connection = connection;

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

            // now initialize the connection channels with whatever extra info they might need.
            connection.init(wrapper, (ConnectionManager<Connection>) this.connectionManager);

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
    @SuppressWarnings("unchecked")
    void connectionConnected0(ConnectionImpl connection) {
        this.isConnected.set(true);

        // prep the channel wrapper
        connection.prep();

        this.connectionManager.onConnected((C) connection);
    }

    /**
     * Expose methods to modify the listeners (connect/disconnect/idle/receive events).
     */
    public final
    Listeners listeners() {
        return this.connectionManager;
    }

    /**
     * Returns a non-modifiable list of active connections
     */
    public
    List<C> getConnections() {
        return this.connectionManager.getConnections();
    }

    /**
     * Returns a non-modifiable list of active connections
     */
    @SuppressWarnings("unchecked")
    public
    Collection<C> getConnectionsAs() {
        return this.connectionManager.getConnections();
    }

    /**
     * Expose methods to send objects to a destination.
     */
    public abstract
    ConnectionBridgeBase send();

    /**
     * Closes all connections ONLY (keeps the server/client running).  To STOP the client/server, use stop().
     * <p/>
     * This is used, for example, when reconnecting to a server.
     * <p/>
     * The server should ALWAYS use STOP.
     */
    public
    void closeConnections() {
        // give a chance to other threads.
        Thread.yield();

        // stop does the same as this + more
        this.connectionManager.closeConnections();

        // Sometimes there might be "lingering" connections (ie, halfway though registration) that need to be closed.
        this.registrationWrapper.closeChannels(maxShutdownWaitTimeInMilliSeconds);

        this.isConnected.set(false);
    }

    /**
     * Starts the shutdown process during JVM shutdown, if necessary.
     * </p>
     * By default, we always can shutdown via the JVM shutdown hook.
     */
    @Override
    protected
    boolean shouldShutdownHookRun() {
        // connectionManager.shutdown accurately reflects the state of the app. Safe to use here
        return (this.connectionManager != null && !this.connectionManager.shutdown.get());
    }

    @Override
    protected
    void shutdownChannelsPre() {
        closeConnections();

        // this does a closeConnections + clear_listeners
        this.connectionManager.stop();
    }

    @Override
    protected
    void stopExtraActionsInternal() {
        // shutdown the database store
        this.propertyStore.close();
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

    @SuppressWarnings("rawtypes")
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
        EndPointBase other = (EndPointBase) obj;

        if (this.privateKey == null) {
            if (other.privateKey != null) {
                return false;
            }
        }
        else if (!CryptoECC.compare(this.privateKey, other.privateKey)) {
            return false;
        }
        if (this.publicKey == null) {
            if (other.publicKey != null) {
                return false;
            }
        }
        else if (!CryptoECC.compare(this.publicKey, other.publicKey)) {
            return false;
        }
        return true;
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
