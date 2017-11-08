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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import dorkbox.network.dns.records.DnsMessage;
import io.netty.channel.AddressedEnvelope;
import io.netty.util.internal.UnstableApi;

/**
 * A {@link DnsResponse} implementation for UDP/IP.
 */
@UnstableApi
public class DnsResponse extends DnsMessage implements AddressedEnvelope<DnsResponse, InetSocketAddress> {

    private final InetSocketAddress sender;
    private final InetSocketAddress recipient;

    /**
     * Creates a new instance.
     *
     * @param sender the address of the sender
     * @param recipient the address of the recipient
     */
    public
    DnsResponse(InetSocketAddress sender, InetSocketAddress recipient, final DnsInput dnsInput) throws IOException {
        super(dnsInput);

        if (recipient == null && sender == null) {
            throw new NullPointerException("recipient and sender");
        }

        this.sender = sender;
        this.recipient = recipient;
    }

    @Override
    public
    DnsResponse content() {
        return this;
    }

    @Override
    public InetSocketAddress sender() {
        return sender;
    }

    @Override
    public InetSocketAddress recipient() {
        return recipient;
    }




    @Override
    public
    DnsResponse touch() {
        return (DnsResponse) super.touch();
    }

    @Override
    public
    DnsResponse touch(Object hint) {
        return (DnsResponse) super.touch(hint);
    }

    @Override
    public
    DnsResponse retain() {
        return (DnsResponse) super.retain();
    }

    @Override
    public
    DnsResponse retain(int increment) {
        return (DnsResponse) super.retain(increment);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!super.equals(obj)) {
            return false;
        }

        if (!(obj instanceof AddressedEnvelope)) {
            return false;
        }

        @SuppressWarnings("unchecked")
        final AddressedEnvelope<?, SocketAddress> that = (AddressedEnvelope<?, SocketAddress>) obj;
        if (sender() == null) {
            if (that.sender() != null) {
                return false;
            }
        } else if (!sender().equals(that.sender())) {
            return false;
        }

        if (recipient() == null) {
            if (that.recipient() != null) {
                return false;
            }
        } else if (!recipient().equals(that.recipient())) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int hashCode = super.hashCode();
        if (sender() != null) {
            hashCode = hashCode * 31 + sender().hashCode();
        }
        if (recipient() != null) {
            hashCode = hashCode * 31 + recipient().hashCode();
        }
        return hashCode;
    }
}
