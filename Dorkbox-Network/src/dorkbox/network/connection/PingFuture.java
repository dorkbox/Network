package dorkbox.network.connection;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

class PingFuture implements Ping {

    private static AtomicInteger pingCounter = new AtomicInteger(0);

    private final Promise<PingTuple<? extends Connection>> promise;

    private final int id;
    private final long sentTime;

    /**
     * Protected constructor for when we are completely overriding this class. (Used by the "local" connection for instant pings)
     */
    PingFuture() {
        this(null);
    }

    PingFuture(Promise<PingTuple<? extends Connection>> promise) {
        this.promise = promise;
        this.id = pingCounter.getAndIncrement();
        this.sentTime = System.currentTimeMillis();

        if (this.id == Integer.MAX_VALUE) {
            pingCounter.set(0);
        }
    }

    /**
     * Wait for the ping to return, and returns the ping response time or -1 if it failed failed.
     */
    @Override
    public int getResponse() {
        try {
            PingTuple<? extends Connection> entry = this.promise.syncUninterruptibly().get();
            if (entry != null) {
                return entry.responseTime;
            }
        } catch (InterruptedException e) {
        } catch (ExecutionException e) {
        }
        return -1;
    }


    /**
     * This is when the endpoint that ORIGINALLY sent the ping, finally receives a response.
     * @param <C>
     * @param connectionImpl
     */
    public <C extends Connection> void setSuccess(C connection, PingMessage ping) {
        if (ping.id == this.id) {
            long longTime = System.currentTimeMillis() - this.sentTime;
            if (longTime < Integer.MAX_VALUE) {
                this.promise.setSuccess(new PingTuple<C>(connection, (int) longTime));
            } else {
                this.promise.setSuccess(new PingTuple<C>(connection, Integer.MAX_VALUE));
            }
        }
    }

    public boolean isSuccess() {
        return this.promise.isSuccess();
    }

    /**
     * Adds the specified listener to this future. The specified listener is
     * notified when this future is done. If this future is already completed,
     * the specified listener is notified immediately.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <C extends Connection> void addListener(PingListener<C> listener) {
        this.promise.addListener((GenericFutureListener<? extends Future<? super PingTuple<? extends Connection>>>) listener);
    }

    /**
     * Removes the specified listener from this future. The specified listener
     * is no longer notified when this future is done. If the specified listener
     * is not associated with this future, this method does nothing and returns
     * silently.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <C extends Connection> void removeListener(PingListener<C> listener) {
        this.promise.removeListener((GenericFutureListener<? extends Future<? super PingTuple<? extends Connection>>>) listener);
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