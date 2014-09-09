package dorkbox.network.connection.ping;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

public class PingFutureLocal extends PingFuture {

    public PingFutureLocal() {
        super();
    }

    /**
     * Wait for the ping to return, and returns the ping response time or -1 if it failed failed.
     */
    @Override
    public int getResponse() {
        return 0;
    }

    /**
     * Tells this ping future, that it was successful
     */
    @Override
    public void setSuccess(PingMessage ping) {
    }

    /**
     * Adds the specified listener to this future. The specified listener is
     * notified when this future is done. If this future is already completed,
     * the specified listener is notified immediately.
     */
    @Override
    public void addListener(GenericFutureListener<? extends Future<? super Object>> listener) {
        try {
            listener.operationComplete(null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Removes the specified listener from this future. The specified listener
     * is no longer notified when this future is done. If the specified listener
     * is not associated with this future, this method does nothing and returns
     * silently.
     */
    @Override
    public void removeListener(GenericFutureListener<? extends Future<? super Object>> listener) {
    }

    /**
     * Cancel this Ping.
     */
    @Override
    public void cancel() {
    }
}