package dorkbox.network.aeron.server

/**
 * The type of exceptions raised by the server.
 */
open class ServerException : Exception {
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

    /**
     * Create an exception.
     *
     * @param message The message
     *  @param cause The cause
     */
    constructor(message: String, cause: Throwable?) : super(message, cause)
}
