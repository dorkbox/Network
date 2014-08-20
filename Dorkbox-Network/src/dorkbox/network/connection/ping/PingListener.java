package dorkbox.network.connection.ping;

import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.Listener;

public class PingListener extends Listener<ConnectionImpl, PingMessage> {
    private final org.slf4j.Logger logger;

    public PingListener(String name) {
        logger = org.slf4j.LoggerFactory.getLogger(name);
    }

    @Override
    public void received(ConnectionImpl connection, PingMessage ping) {
        if (ping.isReply) {
            logger.trace("Received a reply to my issued ping request.");
            connection.updatePingResponse(ping);
        } else {
            logger.trace( "Received a ping from {}", connection);
            ping.isReply = true;

            connection.ping0(ping);
        }
    }
}
