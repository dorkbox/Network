package dorkbox.network.connection;

import org.slf4j.Logger;

import dorkbox.network.ConnectionOptions;
import dorkbox.network.connection.bridge.ConnectionBridge;
import dorkbox.network.connection.bridge.ConnectionBridgeFlushAlways;
import dorkbox.network.util.exceptions.InitializationException;
import dorkbox.network.util.exceptions.SecurityException;

/**
 * This serves the purpose of making sure that specific methods are not available to the end user.
 */
public class EndPointClient extends EndPoint {

    protected final Object registrationLock = new Object();
    protected volatile boolean registrationInProgress = false;

    protected volatile boolean registrationComplete = false;

    private volatile ConnectionBridgeFlushAlways connectionBridgeFlushAlways;


    public EndPointClient(String name, ConnectionOptions options) throws InitializationException, SecurityException {
        super(name, options);
    }

    /**
     * Internal call by the pipeline to notify the client to continue registering the different session protocols.
     * @return true if we are done registering bootstraps
     */
    @Override
    protected boolean continueRegistration0() {
        // we need to cache the value, since it can change in a different thread before we have the chance to return the value.
        boolean complete = this.registrationComplete;

        // notify the block, but only if we are not ready.
        if (!complete) {
            synchronized (this.registrationLock) {
                this.registrationLock.notifyAll();
            }
        }

        Logger logger2 = this.logger;
        if (logger2.isTraceEnabled()) {
            logger2.trace("Registered protocol from server.");
        }

        // only let us continue with connections (this starts up the client/server implementations) once ALL of the
        // bootstraps have connected
        return complete;
    }

    /**
     * Internal (to the networking stack) to notify the client that registration has completed. This is necessary because the client
     * will BLOCK until it has successfully registered it's connections.
     */
    @Override
    final void connectionConnected0(Connection connection) {
        // invokes the listener.connection() method, and initialize the connection channels with whatever extra info they might need.
        super.connectionConnected0(connection);

        // notify the block
        synchronized (this.registrationLock) {
            this.registrationLock.notifyAll();
        }
    }

    /**
     * Internal call to abort registration if the shutdown command is issued during channel registration.
     */
    void abortRegistration() {
        this.registrationInProgress = false;
        stop();
    }

    /**
     * Expose methods to send objects to a destination.
     * <p>
     * This returns a bridge that will flush after EVERY send! This is because sending data can occur on the client, outside
     * of the normal eventloop patterns, and it is confusing to the user to have to manually flush the channel each time.
     */
    @Override
    public ConnectionBridge send() {
        ConnectionBridgeFlushAlways connectionBridgeFlushAlways2 = this.connectionBridgeFlushAlways;
        if (connectionBridgeFlushAlways2 == null) {
            ConnectionBridge clientBridge = this.connectionManager.getConnection0().send();
            this.connectionBridgeFlushAlways = new ConnectionBridgeFlushAlways(clientBridge);
        }

        return this.connectionBridgeFlushAlways;
    }
}
