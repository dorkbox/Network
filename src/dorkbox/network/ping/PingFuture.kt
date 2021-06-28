/*
 * Copyright 2020 dorkbox, llc
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
@file:Suppress("UNUSED_PARAMETER")

package dorkbox.network.ping

import dorkbox.network.connection.Connection
import java.util.concurrent.atomic.AtomicInteger

class PingFuture internal constructor() {
    /**
     * @return the ID of this ping future
     */
    // private final Promise<PingTuple<? extends Connection>> promise;
    val id: Int
    private val sentTime: Long
    // public
    // PingFuture(Promise<PingTuple<? extends Connection>> promise) {
    //     this.promise = promise;
    //     this.id = pingCounter.getAndIncrement();
    //     this.sentTime = System.currentTimeMillis();
    //
    //     if (this.id == Integer.MAX_VALUE) {
    //         pingCounter.set(0);
    //     }
    // }// try {
    //     PingTuple<? extends Connection> entry = this.promise.syncUninterruptibly()
    //                                                         .get();
    //     if (entry != null) {
    //         return entry.responseTime;
    //     }
    // } catch (InterruptedException e) {
    // } catch (ExecutionException e) {
    // }
    /**
     * Wait for the ping to return, and returns the ping response time in MS or -1 if it failed.
     */
    val response: Int
        get() =// try {
        //     PingTuple<? extends Connection> entry = this.promise.syncUninterruptibly()
        //                                                         .get();
        //     if (entry != null) {
        //         return entry.responseTime;
        //     }
        // } catch (InterruptedException e) {
        // } catch (ExecutionException e) {
                // }
            -1

    /**
     * Adds the specified listener to this future. The specified listener is notified when this future is done. If this future is already
     * completed, the specified listener is notified immediately.
     */
    fun <C : Connection> add(listener: PingListener<C>) {
        // this.promise.addListener((GenericFutureListener) listener);
    }

    /**
     * Removes the specified listener from this future. The specified listener is no longer notified when this future is done. If the
     * specified listener is not associated with this future, this method does nothing and returns silently.
     */
    fun <C : Connection> remove(listener: PingListener<C>) {
        // this.promise.removeListener((GenericFutureListener) listener);
    }

    /**
     * Cancel this Ping.
     */
    fun cancel() {
        // this.promise.tryFailure(new PingCanceledException());
    }

    /**
     * This is when the endpoint that ORIGINALLY sent the ping, finally receives a response.
     */
    fun <C : Connection?> setSuccess(connection: C, ping: PingMessage?) {
        // if (ping.id == this.id) {
        //     long longTime = System.currentTimeMillis() - this.sentTime;
        //     if (longTime < Integer.MAX_VALUE) {
        //         this.promise.setSuccess(new PingTuple<C>(connection, (int) longTime));
        //     }
        //     else {
        //         this.promise.setSuccess(new PingTuple<C>(connection, Integer.MAX_VALUE));
        //     }
        // }
    }

    // return this.promise.isSuccess();
    val isSuccess: Boolean
        get() =// return this.promise.isSuccess();
            false

    companion object {
        private val pingCounter = AtomicInteger(0)
    }

    /**
     * Protected constructor for when we are completely overriding this class. (Used by the "local" connection for instant pings)
     */
    init {
        // this(null);
        id = -1
        sentTime = -2
    }
}
