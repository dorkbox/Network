package dorkbox.network.aeron.server

/**
 * A session/stream could not be allocated.
 */
class AllocationException(message: String) : ServerException(message)
