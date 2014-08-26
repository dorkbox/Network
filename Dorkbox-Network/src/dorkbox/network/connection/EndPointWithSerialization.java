package dorkbox.network.connection;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.IESParameters;
import org.bouncycastle.crypto.params.IESWithCipherParameters;

import com.esotericsoftware.kryo.factories.SerializerFactory;

import dorkbox.network.ConnectionOptions;
import dorkbox.network.connection.ping.PingListener;
import dorkbox.network.connection.ping.PingMessage;
import dorkbox.network.connection.registration.MetaChannel;
import dorkbox.network.connection.registration.Registration;
import dorkbox.network.connection.wrapper.ChannelLocalWrapper;
import dorkbox.network.connection.wrapper.ChannelNetworkWrapper;
import dorkbox.network.connection.wrapper.ChannelWrapper;
import dorkbox.network.pipeline.KryoEncoder;
import dorkbox.network.pipeline.KryoEncoderCrypto;
import dorkbox.network.rmi.RmiBridge;
import dorkbox.network.util.KryoSerializationManager;
import dorkbox.network.util.SerializationManager;
import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.SecurityException;
import dorkbox.network.util.serializers.FieldAnnotationAwareSerializer;
import dorkbox.network.util.serializers.IgnoreSerialization;
import dorkbox.util.crypto.serialization.EccPrivateKeySerializer;
import dorkbox.util.crypto.serialization.EccPublicKeySerializer;
import dorkbox.util.crypto.serialization.IesParametersSerializer;
import dorkbox.util.crypto.serialization.IesWithCipherParametersSerializer;

public class EndPointWithSerialization extends EndPoint {

    protected final ConnectionManager connectionManager;

    protected final SerializationManager serializationManager;


    public EndPointWithSerialization(String name, ConnectionOptions options) throws InitializationException, SecurityException {
        super(name, options);

        if (options.serializationManager != null) {
            this.serializationManager = options.serializationManager;
        } else {
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
        this.connectionManager.add(new PingListener(name));

        Runtime.getRuntime().removeShutdownHook(this.shutdownHook);
        this.shutdownHook = new Thread() {
            @Override
            public void run() {
                // connectionManager.shutdown accurately reflects the state of the app. Safe to use here
                if (EndPointWithSerialization.this.connectionManager != null && !EndPointWithSerialization.this.connectionManager.shutdown) {
                    EndPointWithSerialization.this.stop();
                }
            }
        };
    }


    /**
     * Returns the serialization wrapper if there is an object type that needs to be added outside of the basics.
     */
    public SerializationManager getSerialization() {
        return this.serializationManager;
    }

    /**
     * Creates the remote (RMI) object space for this endpoint.
     * <p>
     * This method is safe, and is recommended. Make sure to call it BEFORE a connection is established, as
     * there is some housekeeping that is necessary BEFORE a connection is actually connected..
     */
    public RmiBridge getRmiBridge() {
        synchronized (this) {
            if (this.remoteObjectSpace == null) {
                if (isConnected()) {
                    throw new RuntimeException("Cannot create a remote object space after the remote endpoint has already connected!");
                }

                this.remoteObjectSpace = new RmiBridge(this.logger, this.name);
            }
        }

        return this.remoteObjectSpace;
    }


    /**
     * This method allows the connections used by the client/server to be subclassed (custom implementations).
     * <p>
     * As this is for the network stack, the new connection type MUST subclass {@link Connection}
     *
     * @param bridge null when retrieving the subclass type (internal use only). Non-null when creating a new (and real) connection.
     * @return a new network connection
     */
    public Connection newConnection(String name) {
        return new ConnectionImpl(name);
    }


    /**
     * Internal call by the pipeline when:
     * - creating a new network connection
     * - when determining the baseClass for listeners
     *
     * @param metaChannel can be NULL (when getting the baseClass)
     */
    protected final Connection connection0(MetaChannel metaChannel) {
        Connection connection;

        // setup the extras needed by the network connection.
        // These properties are ASSGINED in the same thread that CREATED the object. Only the AES info needs to be
        // volatile since it is the only thing that changes.
        if (metaChannel != null) {
            ChannelWrapper wrapper;

            if (metaChannel.localChannel != null) {
                wrapper = new ChannelLocalWrapper(metaChannel);
            } else {
                if (this instanceof EndPointServer) {
                    wrapper = new ChannelNetworkWrapper(metaChannel, this.registrationWrapper);
                } else {
                    wrapper = new ChannelNetworkWrapper(metaChannel, null);
                }
            }

            connection = newConnection(this.name);

            // now initialize the connection channels with whatever extra info they might need.
            connection.init(this, new Bridge(wrapper, this.connectionManager));

            metaChannel.connection = connection;

            // notify our remote object space that it is able to receive method calls.
            synchronized (this) {
                if (this.remoteObjectSpace != null) {
                    this.remoteObjectSpace.addConnection(connection);
                }
            }
        } else {
            // getting the baseClass

            // have to add the networkAssociate to a map of "connected" computers
            connection = newConnection(this.name);
        }

        return connection;
    }

    /**
     * Internal call by the pipeline to notify the "Connection" object that it has "connected", meaning that modifications
     * to the pipeline are finished.
     *
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
    public final ListenerBridge listeners() {
        return this.connectionManager;
    }

    /**
     * Returns a non-modifiable list of active connections
     */
    public List<Connection> getConnections() {
        return this.connectionManager.getConnections();
    }

    /**
     * Returns a non-modifiable list of active connections
     */
    @SuppressWarnings("unchecked")
    public <C extends Connection> Collection<C> getConnectionsAs() {
        return (Collection<C>) this.connectionManager.getConnections();
    }

    /**
     * Closes all connections ONLY (keeps the server/client running)
     */
    @Override
    public void close() {
        // stop does the same as this + more
        this.connectionManager.closeConnections();

        super.close();
    }

    /**
     * Extra actions to perform when stopping this endpoint.
     */
    @Override
    protected void stopExtraActions() {
        this.connectionManager.stop();
    }
}
