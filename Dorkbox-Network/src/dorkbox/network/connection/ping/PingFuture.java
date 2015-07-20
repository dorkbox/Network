/*
 * Copyright 2010 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.network.connection.ping;

import dorkbox.network.connection.Connection;
import dorkbox.network.connection.Ping;
import dorkbox.network.connection.PingListener;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public
class PingFuture implements Ping {

    private static final AtomicInteger pingCounter = new AtomicInteger(0);

    private final Promise<PingTuple<? extends Connection>> promise;

    private final int id;
    private final long sentTime;

    /**
     * Protected constructor for when we are completely overriding this class. (Used by the "local" connection for instant pings)
     */
    @SuppressWarnings("unused")
    PingFuture() {
        this(null);
    }

    public
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
    public
    int getResponse() {
        try {
            PingTuple<? extends Connection> entry = this.promise.syncUninterruptibly()
                                                                .get();
            if (entry != null) {
                return entry.responseTime;
            }
        } catch (InterruptedException e) {
        } catch (ExecutionException e) {
        }
        return -1;
    }

    /**
     * Adds the specified listener to this future. The specified listener is
     * notified when this future is done. If this future is already completed,
     * the specified listener is notified immediately.
     */
    @Override
    @SuppressWarnings("unchecked")
    public
    <C extends Connection> void addListener(PingListener<C> listener) {
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
    public
    <C extends Connection> void removeListener(PingListener<C> listener) {
        this.promise.removeListener((GenericFutureListener<? extends Future<? super PingTuple<? extends Connection>>>) listener);
    }

    /**
     * Cancel this Ping.
     */
    @Override
    public
    void cancel() {
        this.promise.tryFailure(new PingCanceledException());
    }

    /**
     * This is when the endpoint that ORIGINALLY sent the ping, finally receives a response.
     */
    public
    <C extends Connection> void setSuccess(C connection, PingMessage ping) {
        if (ping.id == this.id) {
            long longTime = System.currentTimeMillis() - this.sentTime;
            if (longTime < Integer.MAX_VALUE) {
                this.promise.setSuccess(new PingTuple<C>(connection, (int) longTime));
            }
            else {
                this.promise.setSuccess(new PingTuple<C>(connection, Integer.MAX_VALUE));
            }
        }
    }

    public
    boolean isSuccess() {
        return this.promise.isSuccess();
    }

    /**
     * @return the ID of this ping future
     */
    public
    int getId() {
        return this.id;
    }
}
