package dorkbox.network.connection;


import java.io.File;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;

import dorkbox.network.util.store.SettingsStore;
import dorkbox.util.bytes.ByteArrayWrapper;
import dorkbox.util.properties.PropertiesProvider;
import dorkbox.util.storage.Storage;

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

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + (this.serverPrivateKey == null ? 0 : this.serverPrivateKey.getD().hashCode());
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
            Props other = (Props) obj;
            if (this.serverPrivateKey == null) {
                if (other.serverPrivateKey != null) {
                    return false;
                }
            } else if (!this.serverPrivateKey.getD().equals(other.serverPrivateKey.getD())) {
                return false;
            } else if (!this.serverPrivateKey.getParameters().getCurve().equals(other.serverPrivateKey.getParameters().getCurve())) {
                return false;
            } else if (!this.serverPrivateKey.getParameters().getG().equals(other.serverPrivateKey.getParameters().getG())) {
                return false;
            } else if (!this.serverPrivateKey.getParameters().getH().equals(other.serverPrivateKey.getParameters().getH())) {
                return false;
            } else if (!this.serverPrivateKey.getParameters().getN().equals(other.serverPrivateKey.getParameters().getN())) {
                return false;
            }
            return true;
        }
    }


    // the name of the file that contains the saved properties
    private static final String SETTINGS_FILE_NAME = "settings.db";

    private String name;
    private final Storage storage;
    private Props props = new Props();


    // Method of preference for creating/getting this connection store. Private since only the ConnectionStoreProxy calls this
    public PropertyStore(String name) {
        this.name = name;
        File propertiesFile;

        if (PropertiesProvider.basePath.isEmpty()) {
            propertiesFile = new File(SETTINGS_FILE_NAME);
        } else {
            // sometimes we want to change the base path location
            propertiesFile = new File(PropertiesProvider.basePath, SETTINGS_FILE_NAME);
        }

        propertiesFile = propertiesFile.getAbsoluteFile();

        // loads the saved data into the props
        this.storage = Storage.open(propertiesFile);
        this.storage.load(name, this.props);
    }

    /**
     * Simple, property based method to getting the private key of the server
     */
    @Override
    public synchronized ECPrivateKeyParameters getPrivateKey() throws dorkbox.network.util.exceptions.SecurityException {
        checkAccess(EndPoint.class);

        return this.props.serverPrivateKey;
    }

    /**
     * Simple, property based method for saving the private key of the server
     */
    @Override
    public synchronized void savePrivateKey(ECPrivateKeyParameters serverPrivateKey) throws dorkbox.network.util.exceptions.SecurityException {
        checkAccess(EndPoint.class);

        this.props.serverPrivateKey = serverPrivateKey;

        this.storage.save(this.name);
    }

    /**
     * Simple, property based method to getting the public key of the server
     */
    @Override
    public synchronized ECPublicKeyParameters getPublicKey() throws dorkbox.network.util.exceptions.SecurityException {
        checkAccess(EndPoint.class);

        return this.props.serverPublicKey;
    }

    /**
     * Simple, property based method for saving the public key of the server
     */
    @Override
    public synchronized void savePublicKey(ECPublicKeyParameters serverPublicKey) throws dorkbox.network.util.exceptions.SecurityException {
        checkAccess(EndPoint.class);

        this.props.serverPublicKey = serverPublicKey;
        this.storage.save(this.name, this.props);
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

            this.storage.save(this.name, this.props);
            return this.props.salt;
        }

        return this.props.salt;
    }

    /**
     * Simple, property based method to getting a connected computer by host IP address
     */
    @Override
    public synchronized ECPublicKeyParameters getRegisteredServerKey(byte[] hostAddress) throws dorkbox.network.util.exceptions.SecurityException {
        checkAccess(RegistrationWrapper.class);

        return this.props.registeredServer.get(ByteArrayWrapper.wrap(hostAddress));
    }

    /**
     * Saves a connected computer by host IP address and public key
     */
    @Override
    public synchronized void addRegisteredServerKey(byte[] hostAddress, ECPublicKeyParameters publicKey) throws dorkbox.network.util.exceptions.SecurityException {
        checkAccess(RegistrationWrapper.class);

        this.props.registeredServer.put(ByteArrayWrapper.wrap(hostAddress), publicKey);
        this.storage.save(this.name, this.props);
    }

    /**
     * Deletes a registered computer by host IP address
     */
    @Override
    public synchronized boolean removeRegisteredServerKey(byte[] hostAddress) throws dorkbox.network.util.exceptions.SecurityException {
        checkAccess(RegistrationWrapper.class);

        ECPublicKeyParameters remove = this.props.registeredServer.remove(ByteArrayWrapper.wrap(hostAddress));
        this.storage.save(this.name, this.props);

        return remove != null;
    }

    @Override
    public void shutdown() {
        Storage.close(this.storage);
    }
}
