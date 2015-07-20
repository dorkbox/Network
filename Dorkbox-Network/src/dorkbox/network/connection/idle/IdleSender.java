package dorkbox.network.connection.idle;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.ListenerRaw;
import dorkbox.network.util.exceptions.NetException;


public abstract
class IdleSender<C extends Connection, M> extends ListenerRaw<C, M> implements IdleBridge {
    volatile boolean started;
    IdleListener<C, M> idleListener;

    @Override
    public
    void idle(C connection) {
        if (!this.started) {
            this.started = true;
            start();
        }

        M message = next();
        if (message == null) {
            connection.listeners()
                      .remove(this);
        }
        else {
            if (this.idleListener != null) {
                this.idleListener.send(connection, message);
            }
            else {
                throw new NetException("Invalid idle listener. Please specify .TCP(), .UDP(), or .UDT()");
            }
        }
    }

    @Override
    public
    void TCP() {
        this.idleListener = new IdleListenerTCP<C, M>();
    }

    @Override
    public
    void UDP() {
        this.idleListener = new IdleListenerUDP<C, M>();
    }

    @Override
    public
    void UDT() {
        this.idleListener = new IdleListenerUDT<C, M>();
    }



    /**
     * Called once, before the first send. Subclasses can override this method to send something so the receiving side expects
     * subsequent objects.
     */
    protected
    void start() {
    }

    /**
     * Returns the next object to send, or null if no more objects will be sent.
     */
    protected abstract
    M next();
}
