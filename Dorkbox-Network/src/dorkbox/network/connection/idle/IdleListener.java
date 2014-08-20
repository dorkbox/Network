package dorkbox.network.connection.idle;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Listener;

abstract class IdleListener<C extends Connection, M> extends Listener<C, M> {

    /**
     *  used by the Idle Sender
     */
    abstract void send(C connection, M message);
}
