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

import dorkbox.network.connection.ConnectionPoint;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;

public
class ChannelNull implements ConnectionPoint {

    private static final ConnectionPoint INSTANCE = new ChannelNull();

    public static
    ConnectionPoint get() {
        return INSTANCE;
    }

    private
    ChannelNull() {
    }

    @Override
    public
    void write(Object object) {
    }

    @Override
    public
    void flush() {
    }

    /**
     * @return true if the channel is writable. Useful when sending large amounts of data at once.
     */
    @Override
    public
    boolean isWritable() {
        // this channel is ALWAYS writable! (it just does nothing...)
        return true;
    }

    @Override
    public
    <V> Promise<V> newPromise() {
        return ImmediateEventExecutor.INSTANCE.newPromise();
    }
}
