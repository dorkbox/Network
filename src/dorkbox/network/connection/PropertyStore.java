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
import java.util.HashMap;
import java.util.Map;

import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;

import dorkbox.network.util.store.SettingsStore;
import dorkbox.util.SerializationManager;
import dorkbox.util.bytes.ByteArrayWrapper;
import dorkbox.util.database.DB_Server;
import dorkbox.util.database.DatabaseStorage;
import dorkbox.util.exceptions.SecurityException;
import dorkbox.util.storage.Storage;
import dorkbox.util.storage.StorageSystem;

/**
 * The property store is the DEFAULT type of store for the network stack.
 * This is package private, and not intended to be extended.
 */
final
class PropertyStore extends SettingsStore {

    private  Storage storage;
    private Map<ByteArrayWrapper, DB_Server> servers;

    PropertyStore() {
    }

    /**
     * Method of preference for creating/getting this connection store.
     *
     * @param serializationManager this is the serialization used for saving objects into the storage database
     */
    @Override
    public
    void init(final SerializationManager serializationManager, final Storage ignored) throws IOException {
        // make sure our custom types are registered
        // only register if not ALREADY initialized, since we can initialize in the server and in the client. This creates problems if
        // running inside the same JVM (we don't permit it)
        if (serializationManager != null && !serializationManager.initialized()) {
            serializationManager.register(HashMap.class);
            serializationManager.register(ByteArrayWrapper.class);
            serializationManager.register(DB_Server.class);
        }

        this.storage = StorageSystem.Memory()
                                    .make();

        servers = this.storage.getAndPut(DatabaseStorage.SERVERS, new HashMap<ByteArrayWrapper, DB_Server>(16));

        DB_Server localServer = servers.get(DB_Server.IP_SELF); // this will always be null and is here to help people that copy/paste code
        if (localServer == null) {
            localServer = new DB_Server();
            servers.put(DB_Server.IP_SELF, localServer);

            // have to always specify what we are saving
            this.storage.putAndSave(DatabaseStorage.SERVERS, servers);
        }
    }

    /**
     * Simple, property based method to getting the private key of the server
     */
    @Override
    public synchronized
    ECPrivateKeyParameters getPrivateKey() throws dorkbox.util.exceptions.SecurityException {
        checkAccess(EndPoint.class);

        return servers.get(DB_Server.IP_SELF)
                      .getPrivateKey();
    }

    /**
     * Simple, property based method for saving the private key of the server
     */
    @Override
    public synchronized
    void savePrivateKey(final ECPrivateKeyParameters serverPrivateKey) throws SecurityException {
        checkAccess(EndPoint.class);

        servers.get(DB_Server.IP_SELF)
               .setPrivateKey(serverPrivateKey);

        // have to always specify what we are saving
        storage.putAndSave(DatabaseStorage.SERVERS, servers);
    }

    /**
     * Simple, property based method to getting the public key of the server
     */
    @Override
    public synchronized
    ECPublicKeyParameters getPublicKey() throws SecurityException {
        checkAccess(EndPoint.class);

        return servers.get(DB_Server.IP_SELF)
                      .getPublicKey();
    }

    /**
     * Simple, property based method for saving the public key of the server
     */
    @Override
    public synchronized
    void savePublicKey(final ECPublicKeyParameters serverPublicKey) throws SecurityException {
        checkAccess(EndPoint.class);

        servers.get(DB_Server.IP_SELF)
               .setPublicKey(serverPublicKey);

        // have to always specify what we are saving
        storage.putAndSave(DatabaseStorage.SERVERS, servers);
    }

    /**
     * Simple, property based method to getting the server salt
     */
    @Override
    public synchronized
    byte[] getSalt() {
        final DB_Server localServer = servers.get(DB_Server.IP_SELF);
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
            storage.putAndSave(DatabaseStorage.SERVERS, servers);
        }

        return salt;
    }

    /**
     * Simple, property based method to getting a connected computer by host IP address
     */
    @Override
    public synchronized
    ECPublicKeyParameters getRegisteredServerKey(final byte[] hostAddress) throws SecurityException {
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
    void addRegisteredServerKey(final byte[] hostAddress, ECPublicKeyParameters publicKey)
                    throws SecurityException {
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
    boolean removeRegisteredServerKey(final byte[] hostAddress) throws SecurityException {
        checkAccess(RegistrationWrapper.class);

        final ByteArrayWrapper wrap = ByteArrayWrapper.wrap(hostAddress);
        DB_Server db_server = this.servers.remove(wrap);

        return db_server != null;
    }

    @Override
    public
    void close() {
        StorageSystem.close(storage);
    }
}
