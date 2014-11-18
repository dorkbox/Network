
package dorkbox.network.rmi;

import dorkbox.network.util.exceptions.NetException;

/** Thrown when a method with a return value is invoked on a remote object and the response is not received with the
 * {@link RemoteObject#setResponseTimeout(int) response timeout}.
 * @see RmiBridge#getRemoteObject(com.esotericsoftware.kryonet.Connection, int, Class...)
 * @author Nathan Sweet <misc@n4te.com> */
public class TimeoutException extends NetException {
    private static final long serialVersionUID = -3526277240277423682L;

    public TimeoutException () {
        super();
    }

    public TimeoutException (String message, Throwable cause) {
        super(message, cause);
    }

    public TimeoutException (String message) {
        super(message);
    }

    public TimeoutException (Throwable cause) {
        super(cause);
    }
}
