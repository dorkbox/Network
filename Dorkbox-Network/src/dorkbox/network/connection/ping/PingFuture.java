package dorkbox.network.connection.ping;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;

import java.util.concurrent.ExecutionException;

public class PingFuture implements Ping {

    private final Promise<Integer> promise;

    /**
     * Protected constructor for when we are completely overriding this class. (Used by the "local" connection for instant pings)
     */
    protected PingFuture() {
        promise = null;
    }

    public PingFuture(Promise<Integer> promise) {
        this.promise = promise;
    }

    /**
     * Wait for the ping to return, and returns the ping response time or -1 if it failed failed.
     */
    @Override
    public int getResponse() {
        try {
            return promise.syncUninterruptibly().get();
        } catch (InterruptedException e) {
        } catch (ExecutionException e) {
        }
        return -1;
    }


    /**
     * Tells this ping future, that it was successful
     */
    public void setSuccess(PingUtil pingUtil) {
        promise.setSuccess(pingUtil.getReturnTripTime());
    }

    /**
     * Adds the specified listener to this future. The specified listener is
     * notified when this future is done. If this future is already completed,
     * the specified listener is notified immediately.
     */
    @Override
    public void addListener(GenericFutureListener<? extends Future<? super Object>> listener) {
        promise.addListener(listener);
    }

    /**
     * Removes the specified listener from this future. The specified listener
     * is no longer notified when this future is done. If the specified listener
     * is not associated with this future, this method does nothing and returns
     * silently.
     */
    @Override
    public void removeListener(GenericFutureListener<? extends Future<? super Object>> listener) {
        promise.removeListener(listener);
    }

    /**
     * Cancel this Ping.
     */
    @Override
    public void cancel() {
        promise.tryFailure(new PingCanceledException());
    }
}