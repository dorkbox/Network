package dorkbox.network.connection.idle;

import dorkbox.network.connection.Connection;
import dorkbox.network.util.exceptions.NetException;

public class IdleObjectSender<C extends Connection, M> extends IdleSender<C,M> {

    private final M message;

    public IdleObjectSender(M message) {
        this.message = message;
    }

    @Override
    public void idle(C connection) {
        if (!this.started) {
            this.started = true;
            start();
        }

        connection.listeners().remove(this);
        if (this.idleListener != null) {
            this.idleListener.send(connection, this.message);
        } else {
            throw new NetException("Invalid idle listener. Please specify .TCP(), .UDP(), or .UDT()");
        }
    }

    @Override
    protected M next() {
        return null;
    }
}
