package dorkbox.network.util;

public class InitializationException extends Exception {

    private static final long serialVersionUID = -3743402298699150389L;

    public InitializationException() {
        super();
    }

    public InitializationException(String message, Throwable cause) {
        super(message, cause);
    }

    public InitializationException(String message) {
        super(message);
    }

    public InitializationException(Throwable cause) {
        super(cause);
    }
}
