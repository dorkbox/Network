/*
 * Copyright 2018 dorkbox, llc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package dorkbox.network.dns.serverHandlers.wip;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.concurrent.Promise;

public class SuccessHandler implements ChannelFutureListener {
    private Promise<Object> promise;

    public SuccessHandler(Promise<Object> promise) {
        this.promise = promise;
    }

    @Override
    public void operationComplete(ChannelFuture future) throws Exception {
        System.err.println("SUCCESS COMPLETE");
        if (future.isSuccess()) {
            future.channel().pipeline().addLast(new DnsResponseHandler(this.promise));
        } else {
            if (!future.isDone()) {
                this.promise.setFailure(future.cause());
            }
        }
    }
}
