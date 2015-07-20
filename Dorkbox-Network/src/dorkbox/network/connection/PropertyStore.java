package dorkbox.network.connection;


import dorkbox.network.util.store.SettingsStore;
import dorkbox.util.SerializationManager;
import dorkbox.util.bytes.ByteArrayWrapper;
import dorkbox.util.database.DB_Server;
import dorkbox.util.database.DatabaseStorage;
import dorkbox.util.storage.Storage;
import dorkbox.util.storage.Store;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * The property store is the DEFAULT type of store for the network stack.
 * This is package private.
 */
public
class PropertyStore extends SettingsStore {

    protected Storage storage;
    protected Map<ByteArrayWrapper, DB_Server> servers;

    public
    PropertyStore() {
    }

    /**
     * Method of preference for creating/getting this connection store. package only since only the ConnectionStoreProxy calls this
     *
     * @param type                 this is either "Client" or "Server", depending on who is creating this endpoint.
     * @param serializationManager this is the serialization used for saving objects into the storage database
     */
    @Override
    public
    void init(Class<? extends EndPoint> type, final SerializationManager serializationManager, Storage storage) throws IOException {
        // make sure our custom types are registered
        serializationManager.register(HashMap.class);
        serializationManager.register(ByteArrayWrapper.class);
        serializationManager.register(DB_Server.class);

        if (storage == null) {
            this.storage = Store.Memory()
                                .make();
        }
        else {
            this.storage = storage;
        }

        servers = this.storage.load(DatabaseStorage.SERVERS, new HashMap<ByteArrayWrapper, DB_Server>(16));

        //use map to keep track of recid, so we can get record info during restarts.
        DB_Server localServer = servers.get(DB_Server.IP_0_0_0_0);
        if (localServer == null) {
            localServer = new DB_Server();
            servers.put(DB_Server.IP_0_0_0_0, localServer);

            // have to always specify what we are saving
            this.storage.commit(DatabaseStorage.SERVERS, servers);
        }
    }

    /**
     * Simple, property based method to getting the private key of the server
     */
    @Override
    public synchronized
    ECPrivateKeyParameters getPrivateKey() throws dorkbox.network.util.exceptions.SecurityException {
        checkAccess(EndPoint.class);

        return servers.get(DB_Server.IP_0_0_0_0)
                      .getPrivateKey();
    }

    /**
     * Simple, property based method for saving the private key of the server
     */
    @Override
    public synchronized
    void savePrivateKey(ECPrivateKeyParameters serverPrivateKey) throws dorkbox.network.util.exceptions.SecurityException {
        checkAccess(EndPoint.class);

        servers.get(DB_Server.IP_0_0_0_0)
               .setPrivateKey(serverPrivateKey);

        // have to always specify what we are saving
        storage.commit(DatabaseStorage.SERVERS, servers);
    }

    /**
     * Simple, property based method to getting the public key of the server
     */
    @Override
    public synchronized
    ECPublicKeyParameters getPublicKey() throws dorkbox.network.util.exceptions.SecurityException {
        checkAccess(EndPoint.class);

        return servers.get(DB_Server.IP_0_0_0_0)
                      .getPublicKey();
    }

    /**
     * Simple, property based method for saving the public key of the server
     */
    @Override
    public synchronized
    void savePublicKey(ECPublicKeyParameters serverPublicKey) throws dorkbox.network.util.exceptions.SecurityException {
        checkAccess(EndPoint.class);

        servers.get(DB_Server.IP_0_0_0_0)
               .setPublicKey(serverPublicKey);

        // have to always specify what we are saving
        storage.commit(DatabaseStorage.SERVERS, servers);
    }

    /**
     * Simple, property based method to getting the server salt
     */
    @Override
    public synchronized
    byte[] getSalt() {
        final DB_Server localServer = servers.get(DB_Server.IP_0_0_0_0);
        byte[] salt = localServer.getSalt();

        // we don't care who gets the server salt
        if (salt == null) {
            SecureRandom secureRandom = new SecureRandom();

            // server salt is used to salt usernames and other various connection handshake parameters
            byte[] bytes = new byte[256];
            secureRandom.nextBytes(bytes);

            salt = bytes;

            localServer.setSalt(bytes);

            // have to always specify what we are saving
            storage.commit(DatabaseStorage.SERVERS, servers);
        }

        return salt;
    }

    /**
     * Simple, property based method to getting a connected computer by host IP address
     */
    @Override
    public synchronized
    ECPublicKeyParameters getRegisteredServerKey(byte[] hostAddress) throws dorkbox.network.util.exceptions.SecurityException {
        checkAccess(RegistrationWrapper.class);

        final DB_Server db_server = this.servers.get(ByteArrayWrapper.wrap(hostAddress));
        if (db_server != null) {
            return db_server.getPublicKey();
        }
        return null;
    }

    /**
     * Saves a connected computer by host IP address and public key
     */
    @Override
    public synchronized
    void addRegisteredServerKey(byte[] hostAddress, ECPublicKeyParameters publicKey)
                    throws dorkbox.network.util.exceptions.SecurityException {
        checkAccess(RegistrationWrapper.class);

        final ByteArrayWrapper wrap = ByteArrayWrapper.wrap(hostAddress);
        DB_Server db_server = this.servers.get(wrap);
        if (db_server == null) {
            db_server = new DB_Server();
        }

        db_server.setPublicKey(publicKey);
        servers.put(wrap, db_server);
    }

    /**
     * Deletes a registered computer by host IP address
     */
    @Override
    public synchronized
    boolean removeRegisteredServerKey(byte[] hostAddress) throws dorkbox.network.util.exceptions.SecurityException {
        checkAccess(RegistrationWrapper.class);

        final ByteArrayWrapper wrap = ByteArrayWrapper.wrap(hostAddress);
        DB_Server db_server = this.servers.remove(wrap);

        return db_server != null;
    }

    @Override
    public
    void shutdown() {
        Store.close(storage);
    }
}
