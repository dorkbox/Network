/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package dorkbox.network.dns.resolver;

import dorkbox.network.dns.DnsResponse;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.Promise;

final
class DnsNameResolverResponseHandler extends ChannelInboundHandlerAdapter {

    private DnsNameResolver dnsNameResolver;
    private final Promise<Channel> channelActivePromise;

    DnsNameResolverResponseHandler(final DnsNameResolver dnsNameResolver, Promise<Channel> channelActivePromise) {
        this.dnsNameResolver = dnsNameResolver;
        this.channelActivePromise = channelActivePromise;
    }

    @Override
    public
    void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        channelActivePromise.setSuccess(ctx.channel());
    }

    @Override
    public
    void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        final DnsResponse response = (DnsResponse) msg;

        final int queryId = response.getHeader().getID();

        if (DnsNameResolver.logger.isDebugEnabled()) {
            DnsNameResolver.logger.debug("{} RECEIVED: [{}: {}], {}", dnsNameResolver.ch, queryId, response.sender(), response);
        }

        final DnsQueryContext qCtx = dnsNameResolver.queryContextManager.get(response.sender(), queryId);
        if (qCtx == null) {
            DnsNameResolver.logger.warn("{} Received a DNS response with an unknown ID: {}", dnsNameResolver.ch, queryId);
            return;
        }

        qCtx.finish(response);
    }

    @Override
    public
    void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        DnsNameResolver.logger.warn("{} Unexpected exception: ", dnsNameResolver.ch, cause);
    }
}
