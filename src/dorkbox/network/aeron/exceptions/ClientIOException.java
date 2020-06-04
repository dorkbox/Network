package dorkbox.network.aeron.exceptions;

import java.io.IOException;

public final class ClientIOException extends EchoClientException
{
  public
  ClientIOException(final IOException cause)
  {
    super(cause);
  }
}
