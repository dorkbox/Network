package dorkbox.network.connection.ping;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

public interface Ping {
    /**
     * Wait for the ping to return, and returns the ping response time or -1 if it failed failed.
     */
    public int getResponse() ;

    /**
     * Adds the specified listener to this future. The specified listener is
     * notified when this future is done. If this future is already completed,
     * the specified listener is notified immediately.
     */
    public void addListener(GenericFutureListener<? extends Future<? super Object>> listener);

    /**
     * Removes the specified listener from this future. The specified listener
     * is no longer notified when this future is done. If the specified listener
     * is not associated with this future, this method does nothing and returns
     * silently.
     */
    public void removeListener(GenericFutureListener<? extends Future<? super Object>> listener);

    /**
     * Cancel this Ping.
     */
    public void cancel();
}
