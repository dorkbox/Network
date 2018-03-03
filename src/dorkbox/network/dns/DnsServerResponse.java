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

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import dorkbox.network.dns.records.DnsMessage;
import io.netty.channel.AddressedEnvelope;
import io.netty.util.internal.UnstableApi;

/**
 * A {@link DnsServerResponse} implementation for UDP/IP.
 */
@UnstableApi
public
class DnsServerResponse extends DnsEnvelope {

    /**
     * Creates a new instance.
     *
     * @param localAddress the address of the sender
     * @param remoteAddress the address of the recipient
     */
    public
    DnsServerResponse(final DnsMessage dnsQuestion, InetSocketAddress localAddress, InetSocketAddress remoteAddress) {
        super(dnsQuestion.getHeader()
                         .getID(), localAddress, remoteAddress);

        if (remoteAddress == null && localAddress == null) {
            throw new NullPointerException("localAddress and remoteAddress");
        }
    }

    @Override
    public
    int hashCode() {
        int hashCode = super.hashCode();
        if (sender() != null) {
            hashCode = hashCode * 31 + sender().hashCode();
        }
        if (recipient() != null) {
            hashCode = hashCode * 31 + recipient().hashCode();
        }
        return hashCode;
    }

    @Override
    public
    boolean equals(Object obj) {
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
        }
        else if (!sender().equals(that.sender())) {
            return false;
        }

        if (recipient() == null) {
            if (that.recipient() != null) {
                return false;
            }
        }
        else if (!recipient().equals(that.recipient())) {
            return false;
        }

        return true;
    }
}
