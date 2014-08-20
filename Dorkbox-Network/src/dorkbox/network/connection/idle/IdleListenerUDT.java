package dorkbox.network.connection.idle;

import dorkbox.network.connection.Connection;

public class IdleListenerUDT<C extends Connection, M> extends IdleListener<C, M> {

    /**
     *  used by the Idle Sender
     */
    IdleListenerUDT() {
    }

    /**
     *  used by the Idle Sender
     */
    @Override
    void send(C connection, M message) {
        connection.send().UDT(message);
    }
}
