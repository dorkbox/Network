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

import dorkbox.network.dns.DnsResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.concurrent.Promise;

public class DnsResponseHandler extends SimpleChannelInboundHandler<DnsResponse> {
    private Promise<Object> promise;

    public DnsResponseHandler(Promise<Object> promise) {
        this.promise = promise;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DnsResponse msg) throws Exception {
        System.err.println("YOP! " + msg);
        // DnsResponseCode errorCode = msg.header().responseCode();
        //
        // if (errorCode == DnsResponseCode.NOERROR) {
        //     RecordDecoderFactory factory = RecordDecoderFactory.getFactory();
        //
        //     List<DnsResource> resources = msg.answers();
        //     List<Object> records = new ArrayList<>(resources.size());
        //     for (DnsResource resource : resources) {
        //         Object record = factory.decode(msg, resource);
        //         records.add(record);
        //     }
        //     this.promise.setSuccess(records);
        // } else {
        //     this.promise.setFailure(new DnsException(errorCode));
        // }
        // ctx.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        this.promise.setFailure(cause);
        ctx.close();
    }
}
