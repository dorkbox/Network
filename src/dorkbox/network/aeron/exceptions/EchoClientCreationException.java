package dorkbox.network.aeron.exceptions;

/**
 * An exception occurred whilst trying to create the client.
 */

public final class EchoClientCreationException extends EchoClientException
{
  /**
   * Create an exception.
   *
   * @param cause The cause
   */

  public EchoClientCreationException(final Exception cause)
  {
    super(cause);
  }
}
