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

import java.security.SecureRandom;
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
import dorkbox.network.rmi.RmiObjectHandler;
import dorkbox.network.rmi.RmiObjectLocalHandler;
import dorkbox.network.rmi.RmiObjectNetworkHandler;
import dorkbox.network.serialization.Serialization;
import dorkbox.network.store.NullSettingsStore;
import dorkbox.network.store.SettingsStore;
import dorkbox.util.Property;
import dorkbox.util.crypto.CryptoECC;
import dorkbox.util.entropy.Entropy;
import dorkbox.util.exceptions.SecurityException;
import io.netty.util.NetUtil;

/**
 * represents the base of a client/server end point
 */
public abstract
class EndPointBase extends EndPoint {
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

    protected final ConnectionManager connectionManager;
    protected final dorkbox.network.serialization.CryptoSerializationManager serializationManager;
    protected final RegistrationWrapper registrationWrapper;

    final ECPrivateKeyParameters privateKey;
    final ECPublicKeyParameters publicKey;

    final SecureRandom secureRandom;

    // we only want one instance of these created. These will be called appropriately
    private final RmiObjectHandler rmiHandler;
    private final RmiObjectLocalHandler localRmiHandler;
    private final RmiObjectNetworkHandler networkRmiHandler;
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
     * @param type this is either "Client" or "Server", depending on who is creating this endpoint.
     * @param config these are the specific connection options
     *
     * @throws SecurityException if unable to initialize/generate ECC keys
     */
    public
    EndPointBase(Class<? extends EndPointBase> type, final Configuration config) throws SecurityException {
        super(type);

        // make sure that 'localhost' is ALWAYS our specific loopback IP address
        if (config.host != null && (config.host.equals("localhost") || config.host.startsWith("127."))) {
            // localhost IP might not always be 127.0.0.1
            config.host = NetUtil.LOCALHOST.getHostAddress();
        }

        // serialization stuff
        if (config.serialization != null) {
            serializationManager = config.serialization;
        } else {
            serializationManager = Serialization.DEFAULT();
        }

        // setup our RMI serialization managers. Can only be called once
        rmiEnabled = serializationManager.initRmiSerialization();
        rmiExecutor = config.rmiExecutor;


        // The registration wrapper permits the registration process to access protected/package fields/methods, that we don't want
        // to expose to external code. "this" escaping can be ignored, because it is benign.
        //noinspection ThisEscapedInObjectConstruction
        registrationWrapper = new RegistrationWrapper(this,
                                                      logger,
                                                      new KryoEncoder(serializationManager),
                                                      new KryoEncoderCrypto(serializationManager));


        // we have to be able to specify WHAT property store we want to use, since it can change!
        if (config.settingsStore == null) {
            propertyStore = new PropertyStore();
        }
        else {
            propertyStore = config.settingsStore;
        }

        propertyStore.init(serializationManager, null);

        // null it out, since it is sensitive!
        config.settingsStore = null;


        if (!(propertyStore instanceof NullSettingsStore)) {
            // initialize the private/public keys used for negotiating ECC handshakes
            // these are ONLY used for IP connections. LOCAL connections do not need a handshake!
            ECPrivateKeyParameters privateKey = propertyStore.getPrivateKey();
            ECPublicKeyParameters publicKey = propertyStore.getPublicKey();

            if (privateKey == null || publicKey == null) {
                try {
                    // seed our RNG based off of this and create our ECC keys
                    byte[] seedBytes = Entropy.get("There are no ECC keys for the " + type.getSimpleName() + " yet");
                    SecureRandom secureRandom = new SecureRandom(seedBytes);
                    secureRandom.nextBytes(seedBytes);

                    logger.debug("Now generating ECC (" + CryptoECC.curve25519 + ") keys. Please wait!");
                    AsymmetricCipherKeyPair generateKeyPair = CryptoECC.generateKeyPair(CryptoECC.curve25519, secureRandom);

                    privateKey = (ECPrivateKeyParameters) generateKeyPair.getPrivate();
                    publicKey = (ECPublicKeyParameters) generateKeyPair.getPublic();

                    // save to properties file
                    propertyStore.savePrivateKey(privateKey);
                    propertyStore.savePublicKey(publicKey);

                    logger.debug("Done with ECC keys!");
                } catch (Exception e) {
                    String message = "Unable to initialize/generate ECC keys. FORCED SHUTDOWN.";
                    logger.error(message);
                    throw new SecurityException(message);
                }
            }

            this.privateKey = privateKey;
            this.publicKey = publicKey;
        }
        else {
            this.privateKey = null;
            this.publicKey = null;
        }


        secureRandom = new SecureRandom(propertyStore.getSalt());

        // we don't care about un-instantiated/constructed members, since the class type is the only interest.
        //noinspection unchecked
        connectionManager = new ConnectionManager(type.getSimpleName(), connection0(null).getClass());

        // add the ping listener (internal use only!)
        connectionManager.add(new PingSystemListener());

        if (rmiEnabled) {
            rmiHandler = null;
            localRmiHandler = new RmiObjectLocalHandler();
            networkRmiHandler = new RmiObjectNetworkHandler();
            globalRmiBridge = new RmiBridge(logger, config.rmiExecutor, true);
        }
        else {
            rmiHandler = new RmiObjectHandler();
            localRmiHandler = null;
            networkRmiHandler = null;
            globalRmiBridge = null;
        }

        serializationManager.finishInit();
    }

    /**
     * Disables remote endpoint public key validation when the connection is established. This is not recommended as it is a security risk
     */
    public
    void disableRemoteKeyValidation() {
        if (isConnected()) {
            logger.error("Cannot disable the remote key validation after this endpoint is connected!");
        }
        else {
            logger.info("WARNING: Disabling remote key validation is a security risk!!");
            disableRemoteKeyValidation = true;
        }
    }

    /**
     * Returns the property store used by this endpoint. The property store can store via properties,
     * a database, etc, or can be a "null" property store, which does nothing
     */
    @SuppressWarnings("unchecked")
    public
    <S extends SettingsStore> S getPropertyStore() {
        return (S) propertyStore;
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
        return idleTimeoutMs;
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
        return isConnected.get();
    }

    /**
     * Returns the serialization wrapper if there is an object type that needs to be added outside of the basics.
     */
    public
    dorkbox.network.serialization.CryptoSerializationManager getSerialization() {
        return serializationManager;
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
    ConnectionImpl newConnection(final Logger logger, final EndPointBase endPoint, final RmiBridge rmiBridge) {
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
        ConnectionImpl connection;

        RmiBridge rmiBridge = null;
        if (metaChannel != null && rmiEnabled) {
            rmiBridge = new RmiBridge(logger, rmiExecutor, false);
        }

        // setup the extras needed by the network connection.
        // These properties are ASSIGNED in the same thread that CREATED the object. Only the AES info needs to be
        // volatile since it is the only thing that changes.
        if (metaChannel != null) {
            ChannelWrapper wrapper;

            connection = newConnection(logger, this, rmiBridge);
            metaChannel.connection = connection;

            if (metaChannel.localChannel != null) {
                if (rmiEnabled) {
                    wrapper = new ChannelLocalWrapper(metaChannel, localRmiHandler);
                }
                else {
                    wrapper = new ChannelLocalWrapper(metaChannel, rmiHandler);
                }
            }
            else {
                RmiObjectHandler rmiObjectHandler = rmiHandler;
                if (rmiEnabled) {
                    rmiObjectHandler = networkRmiHandler;
                }

                if (this instanceof EndPointServer) {
                    wrapper = new ChannelNetworkWrapper(metaChannel, registrationWrapper, rmiObjectHandler);
                }
                else {
                    wrapper = new ChannelNetworkWrapper(metaChannel, null, rmiObjectHandler);
                }
            }

            // now initialize the connection channels with whatever extra info they might need.
            connection.init(wrapper, connectionManager);
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
    void connectionConnected0(ConnectionImpl connection) {
        isConnected.set(true);

        // prep the channel wrapper
        connection.prep();

        connectionManager.onConnected(connection);
    }

    /**
     * Expose methods to modify the listeners (connect/disconnect/idle/receive events).
     */
    public final
    Listeners listeners() {
        return connectionManager;
    }

    /**
     * Returns a non-modifiable list of active connections
     */
    public
    <C extends Connection> List<C> getConnections() {
        return connectionManager.getConnections();
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
    void closeConnections(boolean shouldKeepListeners) {
        // give a chance to other threads.
        Thread.yield();

        // stop does the same as this + more.  Only keep the listeners for connections IF we are the client. If we remove listeners as a client,
        // ALL of the client logic will be lost. The server is reactive, so listeners are added to connections as needed (instead of before startup)
        connectionManager.closeConnections(shouldKeepListeners);

        // Sometimes there might be "lingering" connections (ie, halfway though registration) that need to be closed.
        registrationWrapper.closeChannels(maxShutdownWaitTimeInMilliSeconds);

        isConnected.set(false);
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
        return (connectionManager != null && !connectionManager.shutdown.get());
    }

    @Override
    protected
    void shutdownChannelsPre() {
        closeConnections(false);

        // this does a closeConnections + clear_listeners
        connectionManager.stop();
    }

    @Override
    protected
    void stopExtraActionsInternal() {
        // shutdown the database store
        propertyStore.close();
    }

    @Override
    public
    int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (privateKey == null ? 0 : privateKey.hashCode());
        result = prime * result + (publicKey == null ? 0 : publicKey.hashCode());
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
        EndPointBase other = (EndPointBase) obj;

        if (privateKey == null) {
            if (other.privateKey != null) {
                return false;
            }
        }
        else if (!CryptoECC.compare(privateKey, other.privateKey)) {
            return false;
        }
        if (publicKey == null) {
            if (other.publicKey != null) {
                return false;
            }
        }
        else if (!CryptoECC.compare(publicKey, other.publicKey)) {
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
