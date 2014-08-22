package dorkbox.network.connection.ping;

import org.slf4j.Logger;

import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.Listener;

public class PingListener extends Listener<ConnectionImpl, PingMessage> {
    private final org.slf4j.Logger logger;

    public PingListener(String name) {
        this.logger = org.slf4j.LoggerFactory.getLogger(name);
    }

    @Override
    public void received(ConnectionImpl connection, PingMessage ping) {
        Logger logger2 = this.logger;
        if (ping.isReply) {
            if (logger2.isTraceEnabled()) {
                logger2.trace("Received a reply to my issued ping request.");
            }
            connection.updatePingResponse(ping);
        } else {
            if (logger2.isTraceEnabled()) {
                logger2.trace( "Received a ping from {}", connection);
            }
            ping.isReply = true;

            connection.ping0(ping);
        }
    }
}
