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

import java.util.List;

import dorkbox.network.dns.exceptions.WireParseException;
import dorkbox.network.dns.records.Header;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.util.internal.UnstableApi;

/**
 * Decodes a {@link DatagramPacket} into a {@link DnsResponse}.
 */
@UnstableApi
@ChannelHandler.Sharable
public class DatagramDnsResponseDecoder extends MessageToMessageDecoder<DatagramPacket> {

    /**
     * Creates a new DNS Response decoder
     */
    public DatagramDnsResponseDecoder() {
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket packet, List<Object> out) throws Exception {
        final ByteBuf buf = packet.content();

        // Check that the response is long enough.
        if (buf.readableBytes() < Header.LENGTH) {
            throw new WireParseException("invalid DNS header - " + "too short");
        }

        DnsInput dnsInput = new DnsInput(buf);
        DnsResponse dnsMessage = new DnsResponse(packet.sender(), packet.recipient(), dnsInput);
        out.add(dnsMessage);
    }
}
