
package dorkbox.network.connection.idle;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Listener;


abstract public class IdleSender<C extends Connection, M> extends Listener<C, M> implements IdleBridge {
	boolean started;
    IdleListener<C, M> idleListener;

    @Override
    public void idle(C connection) {
		if (!started) {
			started = true;
			start();
		}

		M message = next();
		if (message == null) {
		    connection.listeners().remove(this);
		} else {
		    if (idleListener != null) {
		        idleListener.send(connection, message);
		    } else {
		        throw new RuntimeException("Invalid idle listener. Please specify .TCP(), .UDP(), or .UDT()");
		    }
		}
	}

	@Override
    public void TCP() {
	    idleListener = new IdleListenerTCP<C, M>();
    }

	@Override
    public void UDP() {
	    idleListener = new IdleListenerUDP<C, M>();
	}

	@Override
    public void UDT() {
	    idleListener = new IdleListenerUDT<C, M>();
	}



	/** Called once, before the first send. Subclasses can override this method to send something so the receiving side expects
	 * subsequent objects. */
	protected void start () {
	}

	/** Returns the next object to send, or null if no more objects will be sent. */
	abstract protected M next ();
}
