package dorkbox.network.aeron.client

/**
 * The server rejected this client when it tried to connect.
 */
class ClientRejectedException(message: String, cause: Throwable? = null) : ClientException(message, cause)
