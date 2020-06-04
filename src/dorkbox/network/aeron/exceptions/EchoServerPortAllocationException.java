package dorkbox.network.aeron.exceptions;

/**
 * A port could not be allocated.
 */

public final class EchoServerPortAllocationException extends EchoServerException
{
  /**
   * Create an exception.
   *
   * @param message The message
   */

  public EchoServerPortAllocationException(
    final String message)
  {
    super(message);
  }
}
