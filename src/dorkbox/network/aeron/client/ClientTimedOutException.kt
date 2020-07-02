package dorkbox.network.aeron.client

/**
 * The client timed out when it attempted to connect to the server.
 */
class ClientTimedOutException : ClientException {
    /**
     * Create an exception.
     *
     * @param message The message
     */
    constructor(message: String) : super(message)

    /**
     * Create an exception.
     *
     * @param cause The cause
     */
    constructor(cause: Throwable) : super(cause)
}
