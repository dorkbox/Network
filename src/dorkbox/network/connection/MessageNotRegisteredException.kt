package dorkbox.network.connection

/**
 * thrown when a message is received, and does not have any registered 'onMessage' handlers.
 */
class MessageNotRegisteredException(errorMessage: String) : Exception(errorMessage)
