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
package dorkbox.network.connection;

import dorkbox.network.connection.ping.PingTuple;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

// note that we specifically DO NOT implement equals/hashCode, because we cannot create two separate
// listeners that are somehow equal to each other.
public abstract
class PingListener<C extends Connection> implements GenericFutureListener<Future<PingTuple<C>>> {

    public
    PingListener() {
    }

    @Override
    public
    void operationComplete(Future<PingTuple<C>> future) throws Exception {
        PingTuple<C> pingTuple = future.get();
        response(pingTuple.connection, pingTuple.responseTime);
    }

    /**
     * Called when the ping response has been received.
     */
    public abstract
    void response(C connection, int pingResponseTime);

    @Override
    public
    String toString() {
        return "PingListener";
    }
}
