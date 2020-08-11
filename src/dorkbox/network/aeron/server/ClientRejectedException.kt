package dorkbox.network.aeron.server

/**
 * The server rejected this client when it tried to connect.
 */
class ClientRejectedException(message: String, cause: Throwable? = null) : ServerException(message, cause)
