package dorkbox.network.connection.ping;


import dorkbox.network.connection.ConnectionImpl;
import dorkbox.network.connection.ListenerRaw;

public
class PingSystemListener extends ListenerRaw<ConnectionImpl, PingMessage> {

    public
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
