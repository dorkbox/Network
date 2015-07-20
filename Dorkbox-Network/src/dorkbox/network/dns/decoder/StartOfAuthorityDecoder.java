/*
 * Copyright 2013 The Netty Project
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
package dorkbox.network.dns.decoder;

import dorkbox.network.dns.record.StartOfAuthorityRecord;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.resolver.dns.DnsNameResolverAccess;

public
class StartOfAuthorityDecoder implements RecordDecoder<StartOfAuthorityRecord> {

    @Override
    public
    StartOfAuthorityRecord decode(final DnsRecord record, final ByteBuf response) {
        String primaryName = DnsNameResolverAccess.decodeDomainName(response);
        String personName = DnsNameResolverAccess.decodeDomainName(response);

        long serial = response.readUnsignedInt();
        int refresh = response.readInt();
        int retry = response.readInt();
        int expire = response.readInt();
        long minimum = response.readUnsignedInt();

        return new StartOfAuthorityRecord(primaryName, personName, serial, refresh, retry, expire, minimum);
    }
}
