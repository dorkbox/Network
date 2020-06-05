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

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dorkbox.network.Client;
import dorkbox.network.ClientConfiguration;
import dorkbox.network.Configuration;
import dorkbox.network.Server;
import dorkbox.network.ServerConfiguration;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.wrapper.ChannelLocalWrapper;
import dorkbox.network.connection.wrapper.ChannelNetworkWrapper;
import dorkbox.network.connection.wrapper.ChannelWrapper;
import dorkbox.network.rmi.RmiBridge;
import dorkbox.network.serialization.NetworkSerializationManager;
import dorkbox.network.serialization.Serialization;
import dorkbox.network.store.NullSettingsStore;
import dorkbox.network.store.SettingsStore;
import dorkbox.util.OS;
import dorkbox.util.RandomUtil;
import dorkbox.util.crypto.CryptoECC;
import dorkbox.util.entropy.Entropy;
import dorkbox.util.exceptions.SecurityException;
import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
import io.netty.channel.local.LocalAddress;
import io.netty.util.NetUtil;

/**
 * represents the base of a client/server end point
 */
public abstract
class EndPoint<_Configuration extends Configuration> implements Closeable {
    /**
     * The inclusive lower bound of the reserved sessions range.
     */

    public static final int RESERVED_SESSION_ID_LOW = 1;

    /**
     * The inclusive upper bound of the reserved sessions range.
     */

    public static final int RESERVED_SESSION_ID_HIGH = 2147483647;

    public static final int UDP_STREAM_ID = 0x1337cafe;
    public static final int IPC_STREAM_ID = 0xdeadbeef;


    // If TCP and UDP both fill the pipe, THERE WILL BE FRAGMENTATION and dropped UDP packets!
    // it results in severe UDP packet loss and contention.
    //
    // http://www.isoc.org/INET97/proceedings/F3/F3_1.HTM
    // also, a google search on just "INET97/proceedings/F3/F3_1.HTM" turns up interesting problems.
    // Usually it's with ISPs.

    public static
    String getHostDetails(final SocketAddress socketAddress) {
        StringBuilder builder = new StringBuilder();
        getHostDetails(builder, socketAddress);
        return builder.toString();
    }

    public static
    void getHostDetails(StringBuilder stringBuilder, final SocketAddress socketAddress) {
        if (socketAddress instanceof InetSocketAddress) {
            InetSocketAddress address = (InetSocketAddress) socketAddress;

            InetAddress address1 = address.getAddress();

            String hostName = address1.getHostName();
            String hostAddress = address1.getHostAddress();

            if (!hostName.equals(hostAddress)) {
                stringBuilder.append(hostName)
                             .append('/')
                             .append(hostAddress);
            }
            else {
                stringBuilder.append(hostAddress);
            }

            stringBuilder.append(':')
                         .append(address.getPort());
        }
        else if (socketAddress instanceof LocalAddress) {
            stringBuilder.append(socketAddress.toString());
        }
    }




    protected final org.slf4j.Logger logger;

    private final Class<?> type;
    protected final _Configuration config;

    protected MediaDriver mediaDriver = null;
    protected Aeron aeron = null;

    protected final ConnectionManager connectionManager;
    protected final NetworkSerializationManager serializationManager;
    // protected final RegistrationWrapper registrationWrapper;

    final ECPrivateKeyParameters privateKey;
    final ECPublicKeyParameters publicKey;

    final SecureRandom secureRandom;

    final boolean rmiEnabled;

    // we only want one instance of these created. These will be called appropriately
    final RmiBridge rmiGlobalBridge;


    SettingsStore propertyStore;
    boolean disableRemoteKeyValidation;

    // the connection status of this endpoint. Once a server has connected to ANY client, it will always return true until server.close() is called
    protected final AtomicBoolean isConnected = new AtomicBoolean(false);


    protected
    EndPoint(final ClientConfiguration config) throws SecurityException, IOException {
        this(Client.class, config);
    }

    protected
    EndPoint(final ServerConfiguration config) throws SecurityException, IOException {
        this(Server.class, config);
    }

    /**
     * @param type this is either "Client" or "Server", depending on who is creating this endpoint.
     * @param config these are the specific connection options
     *
     * @throws SecurityException if unable to initialize/generate ECC keys
     */
    private
    EndPoint(Class<?> type, final Configuration config) throws SecurityException, IOException {
        this.type = type;

        logger = org.slf4j.LoggerFactory.getLogger(type.getSimpleName());

        //noinspection unchecked
        this.config = (_Configuration) config;


        if (config instanceof ServerConfiguration) {
            ServerConfiguration sConfig = (ServerConfiguration) config;

            if (sConfig.listenIpAddress == null) {
                throw new RuntimeException("The listen IP address cannot be null");
            }

            String listenIpAddress = sConfig.listenIpAddress = sConfig.listenIpAddress.toLowerCase();

            if (listenIpAddress.equals("localhost") || listenIpAddress.equals("loopback") || listenIpAddress.equals("lo") ||
                listenIpAddress.startsWith("127.") || listenIpAddress.startsWith("::1")) {

                // localhost/loopback IP might not always be 127.0.0.1 or ::1
                sConfig.listenIpAddress = NetUtil.LOCALHOST.getHostAddress();
            }
            else if (listenIpAddress.equals("*")) {
                // we set this to "0.0.0.0" so that it is clear that we are trying to bind to that address.
                sConfig.listenIpAddress = "0.0.0.0";
            }
        }


        // Aeron configuration
        File aeronLogDirectory = config.aeronLogDirectory;
        if (aeron == null) {
            File baseFile;
            if (OS.isLinux()) {
                // this is significantly faster for linux than using the temp dir
                baseFile = new File(System.getProperty("/dev/shm/"));
            } else {
                baseFile = new File(System.getProperty("java.io.tmpdir"));
            }
            // note: MacOS should create a ram-drive for this
        /*
         * Linux
Linux normally requires some settings of sysctl values. One is net.core.rmem_max to allow larger SO_RCVBUF and net.core.wmem_max to allow larger SO_SNDBUF values to be set.
Windows

Windows tends to use SO_SNDBUF values that are too small. It is recommended to use values more like 1MB or so.
Mac/Darwin

Mac tends to use SO_SNDBUF values that are too small. It is recommended to use larger values, like 16KB.

Note: Since Mac OS does not have a built-in support for /dev/shm it is advised to create a RAM disk for the Aeron directory (aeron.dir).

You can create a RAM disk with the following command:

$ diskutil erasevolume HFS+ "DISK_NAME" `hdiutil attach -nomount ram://$((2048 * SIZE_IN_MB))`

where:

    DISK_NAME should be replaced with a name of your choice.
    SIZE_IN_MB is the size in megabytes for the disk (e.g. 4096 for a 4GB disk).

For example, the following command creates a RAM disk named DevShm which is 2GB in size:

$ diskutil erasevolume HFS+ "DevShm" `hdiutil attach -nomount ram://$((2048 * 2048))`

After this command is executed the new disk will be mounted under /Volumes/DevShm.
         */

            String baseName = "aeron-" + type.getSimpleName();
            aeronLogDirectory = new File(baseFile, baseName);
            while (aeronLogDirectory.exists()) {
                logger.error("Aeron log directory already exists! This might not be what you want!");
                // avoid a collision
                aeronLogDirectory = new File(baseFile, baseName + RandomUtil.get().nextInt(1000));
            }
        }

        logger.debug("Aeron log directory: " + aeronLogDirectory);


        // LOW-LATENCY SETTINGS
        // .termBufferSparseFile(false)
        //             .useWindowsHighResTimer(true)
        //             .threadingMode(ThreadingMode.DEDICATED)
        //             .conductorIdleStrategy(BusySpinIdleStrategy.INSTANCE)
        //             .receiverIdleStrategy(NoOpIdleStrategy.INSTANCE)
        //             .senderIdleStrategy(NoOpIdleStrategy.INSTANCE);

        final MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
                                                               .publicationReservedSessionIdLow(RESERVED_SESSION_ID_LOW)
                                                               .publicationReservedSessionIdHigh(RESERVED_SESSION_ID_HIGH)
                                                               .dirDeleteOnShutdown(true)
                                                               .threadingMode(config.threadingMode)
                                                               .mtuLength(config.networkMtuSize)
                                                               .socketSndbufLength(config.sendBufferSize)
                                                               .socketRcvbufLength(config.receiveBufferSize)
                                                               .aeronDirectoryName(aeronLogDirectory.getAbsolutePath());

        final Aeron.Context aeronContext = new Aeron.Context().aeronDirectoryName(mediaDriverContext.aeronDirectoryName());

        try {
            mediaDriver = MediaDriver.launch(mediaDriverContext);
            aeron = Aeron.connect(aeronContext);
        } catch (final Exception e) {
            try {
                close();
            } catch (final Exception secondaryException) {
                e.addSuppressed(secondaryException);
            }
            throw new IOException(e);
        }


        // serialization stuff
        if (config.serialization != null) {
            serializationManager = config.serialization;
        } else {
            serializationManager = Serialization.DEFAULT();
        }

        // setup our RMI serialization managers. Can only be called once
        rmiEnabled = serializationManager.initRmiSerialization();


        // The registration wrapper permits the registration process to access protected/package fields/methods, that we don't want
        // to expose to external code. "this" escaping can be ignored, because it is benign.
        //noinspection ThisEscapedInObjectConstruction
        // if (type == Server.class) {
        //     registrationWrapper = new RegistrationWrapperServer(this, logger);
        // } else {
        //     registrationWrapper = new RegistrationWrapperClient(this, logger);
        // }


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
        connectionManager = new ConnectionManager(type.getSimpleName(), connection0(null, null).getClass());

        if (rmiEnabled) {
            rmiGlobalBridge = new RmiBridge(logger, true);
        }
        else {
            rmiGlobalBridge = null;
        }

        Logger readLogger = LoggerFactory.getLogger(type.getSimpleName() + ".READ");
        Logger writeLogger = LoggerFactory.getLogger(type.getSimpleName() + ".WRITE");
        serializationManager.finishInit(readLogger, writeLogger);
    }

    /**
     * Disables remote endpoint public key validation when the connection is established. This is not recommended as it is a security risk
     */
    public
    void disableRemoteKeyValidation() {
        if (isConnected.get()) {
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
     * Returns the serialization wrapper if there is an object type that needs to be added outside of the basics.
     */
    public
    NetworkSerializationManager getSerialization() {
        return serializationManager;
    }

    /**
     * This method allows the connections used by the client/server to be subclassed (with custom implementations).
     * <p/>
     * As this is for the network stack, the new connection MUST subclass {@link ConnectionImpl}
     * <p/>
     * The parameters are ALL NULL when getting the base class, as this instance is just thrown away.
     *
     * @return a new network connection
     */
    protected
    <E extends EndPoint> ConnectionImpl newConnection(final E endPoint, final ChannelWrapper wrapper) {
        return new ConnectionImpl(endPoint, wrapper);
    }

    /**
     * Internal call by the pipeline when:
     * - creating a new network connection
     * - when determining the baseClass for listeners
     *
     * @param metaChannel can be NULL (when getting the baseClass)
     * @param remoteAddress be NULL (when getting the baseClass or when creating a local channel)
     */
    final
    ConnectionImpl connection0(final MetaChannel metaChannel, final InetSocketAddress remoteAddress) {
        ConnectionImpl connection;

        // setup the extras needed by the network connection.
        // These properties are ASSIGNED in the same thread that CREATED the object. Only the AES info needs to be
        // volatile since it is the only thing that changes.
        if (metaChannel != null) {
            ChannelWrapper wrapper;

            if (metaChannel.localChannel != null) {
                wrapper = new ChannelLocalWrapper(metaChannel);
            }
            else {
                wrapper = new ChannelNetworkWrapper(metaChannel, remoteAddress);
            }

            connection = newConnection(this, wrapper);

            isConnected.set(true);
            connectionManager.addConnection(connection);
        }
        else {
            // getting the connection baseClass

            // have to add the networkAssociate to a map of "connected" computers
            connection = newConnection(null, null);
        }

        return connection;
    }

    /**
     * Internal call by the pipeline to notify the "Connection" object that it has "connected", meaning that modifications
     * to the pipeline are finished.
     * <p/>
     * Only the CLIENT injects in front of this
     */
    void connectionConnected0(ConnectionImpl connection) {
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
     * Creates a "global" RMI object for use by multiple connections.
     *
     * @return the ID assigned to this RMI object
     */
    public
    <T> int createGlobalObject(final T globalObject) {
        return rmiGlobalBridge.register(globalObject);
    }

    /**
     * Gets a previously created "global" RMI object
     *
     * @param objectRmiId the ID of the RMI object to get
     *
     * @return null if the object doesn't exist or the ID is invalid.
     */
    @SuppressWarnings("unchecked")
    public
    <T> T getGlobalObject(final int objectRmiId) {
        return (T) rmiGlobalBridge.getRegisteredObject(objectRmiId);
    }

    @Override
    public
    String toString() {
        return "EndPoint [" + getName() + "]";
    }

    /**
     * @return the type class of this connection endpoint
     */
    public Class<?> getType() {
        return type;
    }

    /**
     * @return the simple name (for the class) of this connection endpoint
     */
    public
    String getName() {
        return type.getSimpleName();
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
        EndPoint other = (EndPoint) obj;

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

    @Override
    public void close() {
        if (aeron != null) {
            aeron.close();
        }
        if (mediaDriver != null) {
            mediaDriver.close();
        }

        if (propertyStore != null) {
            propertyStore.close();
        }
    }
}
