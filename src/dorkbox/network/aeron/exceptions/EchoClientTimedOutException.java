package dorkbox.network.aeron.exceptions;

/**
 * The client timed out when it attempted to connect to the server.
 */

public final class EchoClientTimedOutException extends EchoClientException
{
  /**
   * Create an exception.
   *
   * @param message The message
   */

  public EchoClientTimedOutException(final String message)
  {
    super(message);
  }
}
