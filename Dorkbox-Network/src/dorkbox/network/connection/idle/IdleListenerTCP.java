package dorkbox.network.connection.idle;

import dorkbox.network.connection.Connection;

public class IdleListenerTCP<C extends Connection, M> extends IdleListener<C, M> {

    /**
     *  used by the Idle Sender
     */
    IdleListenerTCP() {
    }

    /**
     *  used by the Idle Sender
     */
    @Override
    void send(C connection, M message) {
        connection.send().TCP(message);
    }
}
