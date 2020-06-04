package dorkbox.network.aeron.exceptions;

import java.util.Objects;

/**
 * The type of exceptions raised by the server.
 */

public abstract class EchoServerException extends Exception
{
  /**
   * Create an exception.
   *
   * @param message The message
   */

  public EchoServerException(final String message)
  {
    super(Objects.requireNonNull(message, "message"));
  }

  /**
   * Create an exception.
   *
   * @param cause The cause
   */

  public EchoServerException(final Throwable cause)
  {
    super(Objects.requireNonNull(cause, "cause"));
  }
}
