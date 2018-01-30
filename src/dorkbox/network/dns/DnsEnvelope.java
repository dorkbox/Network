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
package dorkbox.network.dns;

import java.io.IOException;
import java.net.InetSocketAddress;

import dorkbox.network.dns.records.DnsMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.AddressedEnvelope;

/**
 *
 */
public
class DnsEnvelope extends DnsMessage implements AddressedEnvelope<DnsEnvelope, InetSocketAddress> {

    private InetSocketAddress localAddress;
    private InetSocketAddress remoteAddress;

    public
    DnsEnvelope() {
        super();
    }


    public
    DnsEnvelope(final int id, final InetSocketAddress localAddress, final InetSocketAddress remoteAddress) {
        super(id);

        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
    }

    public
    DnsEnvelope(final ByteBuf buffer, final InetSocketAddress localAddress, final InetSocketAddress remoteAddress) throws IOException {
        super(buffer);

        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
    }


    public
    DnsEnvelope(final DnsInput input, final InetSocketAddress localAddress, final InetSocketAddress remoteAddress) throws IOException {
        super(input);

        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
    }

    public
    void setLocalAddress(final InetSocketAddress localAddress) {
        this.localAddress = localAddress;
    }

    public
    void setRemoteAddress(final InetSocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    @Override
    public
    DnsEnvelope content() {
        return this;
    }

    @Override
    public final
    InetSocketAddress sender() {
        return localAddress;
    }

    @Override
    public final
    InetSocketAddress recipient() {
        return remoteAddress;
    }



    @Override
    public
    DnsEnvelope touch() {
        return (DnsEnvelope) super.touch();
    }

    @Override
    public
    DnsEnvelope touch(Object hint) {
        return (DnsEnvelope) super.touch(hint);
    }

    @Override
    public
    DnsEnvelope retain() {
        return (DnsEnvelope) super.retain();
    }

    @Override
    public
    DnsEnvelope retain(int increment) {
        return (DnsEnvelope) super.retain(increment);
    }
}
