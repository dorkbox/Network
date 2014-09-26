package dorkbox.network.connection.idle;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.ListenerRaw;

abstract class IdleListener<C extends Connection, M> extends ListenerRaw<C, M> {

    /**
     *  used by the Idle Sender
     */
    abstract void send(C connection, M message);
}
