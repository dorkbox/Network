
package dorkbox.network.util.exceptions;

public class NetException extends RuntimeException {
    private static final long serialVersionUID = 2963139576811306988L;

    public NetException() {
		super();
	}

	public NetException(String message, Throwable cause) {
		super(message, cause);
	}

	public NetException(String message) {
		super(message);
	}

	public NetException(Throwable cause) {
		super(cause);
	}
}
