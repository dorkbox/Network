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
package dorkbox.network.connection.wrapper;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import dorkbox.network.connection.ConnectionPoint;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.Promise;

public
class ChannelNetwork implements ConnectionPoint {

    final Channel channel;
    final AtomicBoolean shouldFlush = new AtomicBoolean(false);
    private final ChannelPromise voidPromise;

    public
    ChannelNetwork(Channel channel) {
        this.channel = channel;
        this.voidPromise = channel.voidPromise();
    }

    /**
     * Write an object to the underlying channel. If the underlying channel is NOT writable, this will block unit it is writable
     */
    @Override
    public
    void write(Object object) throws Exception {
        shouldFlush.set(true);
        // we don't care, or want to save the future. This is so GC is less.
        channel.write(object, voidPromise);
    }

    /**
     * @return true if the channel is writable. Useful when sending large amounts of data at once.
     */
    @Override
    public
    boolean isWritable() {
        return channel.isWritable();
    }

    @Override
    public
    void flush() {
        if (shouldFlush.compareAndSet(true, false)) {
            channel.flush();
        }
    }

    @Override
    public
    <V> Promise<V> newPromise() {
        return channel.eventLoop().newPromise();
    }

    void close(final int delay, final long maxShutdownWaitTimeInMilliSeconds) {
        shouldFlush.set(false);
        if (channel.isActive()) {
            if (delay > 0) {
                // for UDP, we send a hint to the other connection that we should close. While not always 100% successful, this helps
                // clean up connections on the remote end. So we want to wait a short amount of time for this to be successful
                channel.eventLoop()
                       .schedule(new Runnable() {
                           @Override
                           public
                           void run() {
                               channel.close()
                                      .awaitUninterruptibly(maxShutdownWaitTimeInMilliSeconds);
                           }
                       }, delay, TimeUnit.MILLISECONDS);
            }
            else {
                channel.close()
                       .awaitUninterruptibly(maxShutdownWaitTimeInMilliSeconds);
            }
        }
    }
}
