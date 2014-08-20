package dorkbox.network.connection.idle;

import dorkbox.network.connection.Connection;

public class IdleListenerUDP<C extends Connection, M> extends IdleListener<C, M> {

    /**
     *  used by the Idle Sender
     */
    IdleListenerUDP() {
    }

    /**
     *  used by the Idle Sender
     */
    @Override
    void send(C connection, M message) {
        connection.send().UDP(message);
    }
}
