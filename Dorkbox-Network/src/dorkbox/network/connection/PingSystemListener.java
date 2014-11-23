package dorkbox.network.connection;


class PingSystemListener extends ListenerRaw<ConnectionImpl, PingMessage> {

    PingSystemListener() {
    }

    @Override
    public void received(ConnectionImpl connection, PingMessage ping) {
        if (ping.isReply) {
            connection.updatePingResponse(ping);
        } else {
            // return the ping from whence it came
            ping.isReply = true;

            connection.ping0(ping);
        }
    }
}
