package dorkbox.network.connection;


import java.io.File;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;

import dorkbox.network.util.ByteArrayWrapper;
import dorkbox.network.util.store.SettingsStore;
import dorkbox.util.Storage;
import dorkbox.util.properties.PropertiesProvider;

/**
 * The property store is the DEFAULT type of store for the network stack.
 * This is package private.
 */
class PropertyStore extends SettingsStore {

    private static class Props {
        private volatile ECPrivateKeyParameters serverPrivateKey = null;
        private volatile ECPublicKeyParameters serverPublicKey = null;
        private volatile byte[] salt = null;

        private volatile Map<ByteArrayWrapper, ECPublicKeyParameters> registeredServer = new HashMap<ByteArrayWrapper, ECPublicKeyParameters>(16);

        private Props() {
        }
    }


    // the name of the file that contains the saved properties
    private static final String SETTINGS_FILE_NAME = "settings.dat";

    private final Storage storage;
    private Props props = new Props();

    // Method of preference for creating/getting this connection store. Private since only the ConnectionStoreProxy calls this
    public PropertyStore() {
        File propertiesFile;

        if (PropertiesProvider.basePath.isEmpty()) {
            propertiesFile = new File(SETTINGS_FILE_NAME);
        } else {
            // sometimes we want to change the base path location
            propertiesFile = new File(PropertiesProvider.basePath, SETTINGS_FILE_NAME);
        }

        propertiesFile = propertiesFile.getAbsoluteFile();

        // loads the saved data into the props
        this.storage = Storage.load(propertiesFile, this.props);
    }

    /**
     * Simple, property based method to getting the private key of the server
     */
    @Override
    public synchronized ECPrivateKeyParameters getPrivateKey() throws dorkbox.network.util.SecurityException {
        checkAccess(EndPoint.class);

        return this.props.serverPrivateKey;
    }

    /**
     * Simple, property based method for saving the private key of the server
     */
    @Override
    public synchronized void savePrivateKey(ECPrivateKeyParameters serverPrivateKey) throws dorkbox.network.util.SecurityException {
        checkAccess(EndPoint.class);

        this.props.serverPrivateKey = serverPrivateKey;

        this.storage.save();
    }

    /**
     * Simple, property based method to getting the public key of the server
     */
    @Override
    public synchronized ECPublicKeyParameters getPublicKey() throws dorkbox.network.util.SecurityException {
        checkAccess(EndPoint.class);

        return this.props.serverPublicKey;
    }

    /**
     * Simple, property based method for saving the public key of the server
     */
    @Override
    public synchronized void savePublicKey(ECPublicKeyParameters serverPublicKey) throws dorkbox.network.util.SecurityException {
        checkAccess(EndPoint.class);

        this.props.serverPublicKey = serverPublicKey;
        this.storage.save();
    }

    /**
     * Simple, property based method to getting the server salt
     */
    @Override
    public synchronized byte[] getSalt() {
        // we don't care who gets the server salt
        if (this.props.salt == null) {
            SecureRandom secureRandom = new SecureRandom();

            // server salt is used to salt usernames and other various connection handshake parameters
            this.props.salt = new byte[256];
            secureRandom.nextBytes(this.props.salt);

            this.storage.save();
            return this.props.salt;
        }

        return this.props.salt;
    }

    /**
     * Simple, property based method to getting a connected computer by host IP address
     */
    @Override
    public synchronized ECPublicKeyParameters getRegisteredServerKey(byte[] hostAddress) throws dorkbox.network.util.SecurityException {
        checkAccess(RegistrationWrapper.class);

        return this.props.registeredServer.get(new ByteArrayWrapper(hostAddress));
    }

    /**
     * Saves a connected computer by host IP address and public key
     */
    @Override
    public synchronized void addRegisteredServerKey(byte[] hostAddress, ECPublicKeyParameters publicKey) throws dorkbox.network.util.SecurityException {
        checkAccess(RegistrationWrapper.class);

        this.props.registeredServer.put(new ByteArrayWrapper(hostAddress), publicKey);
        this.storage.save();
    }

    /**
     * Deletes a registered computer by host IP address
     */
    @Override
    public synchronized boolean removeRegisteredServerKey(byte[] hostAddress) throws dorkbox.network.util.SecurityException {
        checkAccess(RegistrationWrapper.class);

        ECPublicKeyParameters remove = this.props.registeredServer.remove(new ByteArrayWrapper(hostAddress));
        this.storage.save();

        return remove != null;
    }

    @Override
    public void shutdown() {
        Storage.shutdown();
    }
}
