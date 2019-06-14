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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.CopyOnWriteArrayList;

import dorkbox.network.Configuration;
import dorkbox.network.Server;
import dorkbox.network.connection.bridge.ConnectionBridgeServer;
import dorkbox.network.connection.connectionType.ConnectionRule;
import dorkbox.network.connection.connectionType.ConnectionType;
import dorkbox.util.exceptions.SecurityException;
import io.netty.handler.ipfilter.IpFilterRule;
import io.netty.handler.ipfilter.IpFilterRuleType;
import io.netty.util.NetUtil;

/**
 * This serves the purpose of making sure that specific methods are not available to the end user.
 */
public
class EndPointServer extends EndPoint {

    /**
     * Maintains a thread-safe collection of rules to allow/deny connectivity to this server.
     */
    protected final CopyOnWriteArrayList<IpFilterRule> ipFilterRules = new CopyOnWriteArrayList<>();

    /**
     * Maintains a thread-safe collection of rules used to define the connection type with this server.
     */
    protected final CopyOnWriteArrayList<ConnectionRule> connectionRules = new CopyOnWriteArrayList<>();

    public
    EndPointServer(final Configuration config) throws SecurityException {
        super(Server.class, config);
    }

    /**
     * Expose methods to send objects to a destination.
     */
    @Override
    public
    ConnectionBridgeServer send() {
        return this.connectionManager;
    }

    /**
     * Safely sends objects to a destination (such as a custom object or a standard ping). This will automatically choose which protocol
     * is available to use. If you want specify the protocol, use {@link #send()}, followed by the protocol you wish to use.
     */
    @Override
    public
    ConnectionPoint send(final Object message) {
        return this.connectionManager.send(message);
    }


    /**
     * When called by a server, NORMALLY listeners are added at the GLOBAL level (meaning, I add one listener,
     * and ALL connections are notified of that listener.
     * <br>
     * It is POSSIBLE to add a server-connection 'local' listener (via connection.addListener), meaning that ONLY
     * that listener attached to the connection is notified on that event (ie, admin type listeners)
     *
     * @return a newly created listener manager for the connection
     */
    final
    ConnectionManager addListenerManager(final Connection connection) {
        return this.connectionManager.addListenerManager(connection);
    }

    /**
     * When called by a server, NORMALLY listeners are added at the GLOBAL level (meaning, I add one listener,
     * and ALL connections are notified of that listener.
     * <br>
     * It is POSSIBLE to remove a server-connection 'local' listener (via connection.removeListener), meaning that ONLY
     * that listener attached to the connection is removed
     * <p/>
     * This removes the listener manager for that specific connection
     */
    final
    void removeListenerManager(final Connection connection) {
        this.connectionManager.removeListenerManager(connection);
    }

    /**
     * Adds a custom connection to the server.
     * <p>
     * This should only be used in situations where there can be DIFFERENT types of connections (such as a 'web-based' connection) and
     * you want *this* server instance to manage listeners + message dispatch
     *
     * @param connection the connection to add
     */
    public
    void add(Connection connection) {
        connectionManager.addConnection0(connection);
    }

    /**
     * Removes a custom connection to the server.
     * <p>
     * This should only be used in situations where there can be DIFFERENT types of connections (such as a 'web-based' connection) and
     * you want *this* server instance to manage listeners + message dispatch
     *
     * @param connection the connection to remove
     */
    public
    void remove(Connection connection) {
        connectionManager.removeConnection(connection);
    }

    @Override
    protected
    void shutdownChannelsPre() {
        // Sometimes there might be "lingering" connections (ie, halfway though registration) that need to be closed.
        registrationWrapper.clearSessions();

        // this calls connectionManager.stop()
        super.shutdownChannelsPre();
    }

    // if no rules, then always yes
    // if rules, then default no unless a rule says yes. ACCEPT rules take precedence over REJECT (so if you have both rules, ACCEPT will happen)
    boolean acceptRemoteConnection(final InetSocketAddress remoteAddress) {
        int size = ipFilterRules.size();

        if (size == 0) {
            return true;
        }

        InetAddress address = remoteAddress.getAddress();

        // it's possible for a remote address to match MORE than 1 rule.
        boolean isAllowed = false;
        for (int i = 0; i < size; i++) {
            final IpFilterRule rule = ipFilterRules.get(i);
            if (rule == null) {
                continue;
            }

            if (isAllowed) {
                break;
            }

            if (rule.matches(remoteAddress)) {
                isAllowed = rule.ruleType() == IpFilterRuleType.ACCEPT;
            }
        }

        logger.debug("Validating {}  Connection allowed: {}", address, isAllowed);
        return isAllowed;
    }

    // after the handshake, what sort of connection do we want (NONE, COMPRESS, ENCRYPT+COMPRESS)
    byte getConnectionUpgradeType(final InetSocketAddress remoteAddress) {
        InetAddress address = remoteAddress.getAddress();

        int size = connectionRules.size();

        // if it's unknown, then by default we encrypt the traffic
        ConnectionType connectionType = ConnectionType.COMPRESS_AND_ENCRYPT;
        if (size == 0 && address.equals(NetUtil.LOCALHOST)) {
            // if nothing is specified, then by default localhost is compression and everything else is encrypted
            connectionType = ConnectionType.COMPRESS;
        }

        for (int i = 0; i < size; i++) {
            final ConnectionRule rule = connectionRules.get(i);
            if (rule == null) {
                continue;
            }

            if (rule.matches(remoteAddress)) {
                connectionType = rule.ruleType();
                break;
            }
        }

        logger.debug("Validating {}  Permitted type is: {}", remoteAddress, connectionType);
        return connectionType.getType();
    }
}
