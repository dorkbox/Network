package dorkbox.network.aeron.client

/**
 * The type of exceptions raised by the client.
 */
open class ClientException : Exception {
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
