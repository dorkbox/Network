package dorkbox.network.connection.idle;

import dorkbox.network.connection.Connection;

public class IdleObjectSender<C extends Connection, M> extends IdleSender<C,M> {

    private final M message;

    public IdleObjectSender(M message) {
        this.message = message;
    }

    @Override
    public void idle(C connection) {
        if (!started) {
            started = true;
            start();
        }

        connection.listeners().remove(this);
        if (idleListener != null) {
            idleListener.send(connection, message);
        } else {
            throw new RuntimeException("Invlaid idle listener. Please specify .TCP(), .UDP(), or .UDT()");
        }
    }

    @Override
    protected M next() {
        return null;
    }
}
