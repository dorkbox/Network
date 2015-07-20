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

import dorkbox.network.connection.ConnectionPointWriter;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

import java.util.concurrent.atomic.AtomicBoolean;

public
class ChannelNetwork implements ConnectionPointWriter {

    private final Channel channel;
    private final AtomicBoolean shouldFlush = new AtomicBoolean(false);

    private volatile ChannelFuture lastWriteFuture;


    public
    ChannelNetwork(Channel channel) {
        this.channel = channel;
    }

    /**
     * Write an object to the underlying channel
     */
    @Override
    public
    void write(Object object) {
        this.lastWriteFuture = this.channel.write(object);
        this.shouldFlush.set(true);
    }

    /**
     * Waits for the last write to complete. Useful when sending large amounts of data at once.
     * <b>DO NOT use this in the same thread as receiving messages! It will deadlock.</b>
     */
    @Override
    public
    void waitForWriteToComplete() {
        if (this.lastWriteFuture != null) {
            this.lastWriteFuture.awaitUninterruptibly();
        }
    }

    @Override
    public
    void flush() {
        if (this.shouldFlush.compareAndSet(true, false)) {
            this.channel.flush();
        }
    }

    public
    void close(long maxShutdownWaitTimeInMilliSeconds) {
        // Wait until all messages are flushed before closing the channel.
        if (this.lastWriteFuture != null) {
            this.lastWriteFuture.awaitUninterruptibly(maxShutdownWaitTimeInMilliSeconds);
            this.lastWriteFuture = null;
        }

        this.shouldFlush.set(false);
        this.channel.close()
                    .awaitUninterruptibly(maxShutdownWaitTimeInMilliSeconds);
    }

    public
    int id() {
        return this.channel.hashCode();
    }
}
