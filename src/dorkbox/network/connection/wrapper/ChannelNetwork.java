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
import io.netty.channel.ChannelPromise;

import java.util.concurrent.atomic.AtomicBoolean;

public
class ChannelNetwork implements ConnectionPointWriter {

    private final Channel channel;
    private final AtomicBoolean shouldFlush = new AtomicBoolean(false);
    private final ChannelPromise voidPromise;

    public
    ChannelNetwork(Channel channel) {
        this.channel = channel;
        voidPromise = channel.voidPromise();
    }

    /**
     * Write an object to the underlying channel. If the underlying channel is NOT writable, this will block unit it is writable
     */
    @Override
    public
    void write(Object object) {
        // we don't care, or want to save the future. This is so GC is less.
        channel.write(object, voidPromise);
        shouldFlush.set(true);
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

    public
    void close(long maxShutdownWaitTimeInMilliSeconds) {
        shouldFlush.set(false);
        channel.close()
               .awaitUninterruptibly(maxShutdownWaitTimeInMilliSeconds);
    }

    public
    int id() {
        return channel.hashCode();
    }
}
