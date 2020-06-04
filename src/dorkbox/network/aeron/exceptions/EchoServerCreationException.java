package dorkbox.network.aeron.exceptions;

/**
 * An exception occurred whilst trying to create the server.
 */

public final
class EchoServerCreationException extends EchoServerException {
    /**
     * Create an exception.
     *
     * @param cause The cause
     */

    public
    EchoServerCreationException(final Exception cause) {
        super(cause);
    }
}
