/*
 * Copyright 2015 The Netty Project
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
package dorkbox.network.dns;

import java.net.InetSocketAddress;
import java.util.List;

import dorkbox.network.dns.records.DnsMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.AddressedEnvelope;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.util.internal.UnstableApi;

/**
 * Encodes an {@link AddressedEnvelope} of {@link DnsQuestion}} into a {@link DatagramPacket}.
 */
@UnstableApi
@ChannelHandler.Sharable
public
class DatagramDnsQueryEncoder extends MessageToMessageEncoder<AddressedEnvelope<DnsQuestion, InetSocketAddress>> {

    private final int maxPayloadSize;

    /**
     * Creates a new encoder
     */
    public
    DatagramDnsQueryEncoder(int maxPayloadSize) {
        this.maxPayloadSize = maxPayloadSize;
    }

    @Override
    protected
    void encode(ChannelHandlerContext ctx, AddressedEnvelope<DnsQuestion, InetSocketAddress> in, List<Object> out) throws Exception {

        final InetSocketAddress recipient = in.recipient();
        final DnsMessage query = in.content();
        final ByteBuf buf = ctx.alloc()
                               .ioBuffer(maxPayloadSize);

        boolean success = false;
        try {
            DnsOutput dnsOutput = new DnsOutput(buf);
            query.toWire(dnsOutput);
            success = true;
        } finally {
            if (!success) {
                buf.release();
            }
        }

        out.add(new DatagramPacket(buf, recipient, null));
    }

    @Override
    public
    void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
        cause.printStackTrace();
    }
}
