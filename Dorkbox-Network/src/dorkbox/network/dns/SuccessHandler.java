/*
 * Copyright (c) 2013 The Netty Project
 * ------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *     The Eclipse Public License is available at
 *     http://www.eclipse.org/legal/epl-v10.html
 *
 *     The Apache License v2.0 is available at
 *     http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */
package dorkbox.network.dns;

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
        if (future.isSuccess()) {
            future.channel().pipeline().addLast(new DnsResponseHandler(this.promise));
        } else {
            if (!future.isDone()) {
                this.promise.setFailure(future.cause());
            }
        }
    }
}
