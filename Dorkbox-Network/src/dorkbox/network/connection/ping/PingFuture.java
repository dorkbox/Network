package dorkbox.network.connection.ping;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public class PingFuture implements Ping {

    private static AtomicInteger pingCounter = new AtomicInteger(0);

    private final Promise<Integer> promise;

    private final int id;
    private final long sentTime;

    /**
     * Protected constructor for when we are completely overriding this class. (Used by the "local" connection for instant pings)
     */
    protected PingFuture() {
        this(null);
    }

    public PingFuture(Promise<Integer> promise) {
        this.promise = promise;
        this.id = pingCounter.getAndIncrement();
        this.sentTime = System.currentTimeMillis();
    }

    /**
     * Wait for the ping to return, and returns the ping response time or -1 if it failed failed.
     */
    @Override
    public int getResponse() {
        try {
            return this.promise.syncUninterruptibly().get();
        } catch (InterruptedException e) {
        } catch (ExecutionException e) {
        }
        return -1;
    }


    /**
     * This is when the endpoint that ORIGINALLY sent the ping, finally receives a response.
     */
    public void setSuccess(PingMessage ping) {
        if (ping.id == this.id) {
            long longTime = System.currentTimeMillis() - this.sentTime;
            if (longTime < Integer.MAX_VALUE) {
                this.promise.setSuccess((int)longTime);
            } else {
                this.promise.setSuccess(Integer.MAX_VALUE);
            }
        }
    }

    /**
     * Adds the specified listener to this future. The specified listener is
     * notified when this future is done. If this future is already completed,
     * the specified listener is notified immediately.
     */
    @Override
    public void addListener(GenericFutureListener<? extends Future<? super Object>> listener) {
        this.promise.addListener(listener);
    }

    /**
     * Removes the specified listener from this future. The specified listener
     * is no longer notified when this future is done. If the specified listener
     * is not associated with this future, this method does nothing and returns
     * silently.
     */
    @Override
    public void removeListener(GenericFutureListener<? extends Future<? super Object>> listener) {
        this.promise.removeListener(listener);
    }

    /**
     * Cancel this Ping.
     */
    @Override
    public void cancel() {
        this.promise.tryFailure(new PingCanceledException());
    }

    /**
     * @return the ID of this ping future
     */
    public int getId() {
        return this.id;
    }
}