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
package dorkbox.network.dns.decoder;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.dns.DnsResource;
import io.netty.handler.codec.dns.DnsResponse;
import dorkbox.network.dns.RecordDecoderFactory;
import dorkbox.network.dns.record.StartOfAuthorityRecord;

/**
 * Decodes SOA (start of authority) resource records.
 */
public class StartOfAuthorityDecoder implements RecordDecoder<StartOfAuthorityRecord> {

    /**
     * Returns a decoded SOA (start of authority) resource record, stored as an
     * instance of {@link StartOfAuthorityRecord}.
     *
     * @param response
     *            the DNS response that contains the resource record being
     *            decoded
     * @param resource
     *            the resource record being decoded
     */
    @Override
    public StartOfAuthorityRecord decode(DnsResponse response, DnsResource resource) {
        ByteBuf data = resource.content();

        String mName = RecordDecoderFactory.readName(data);
        String rName = RecordDecoderFactory.readName(data);
        long serial = data.readUnsignedInt();
        int refresh = data.readInt();
        int retry = data.readInt();
        int expire = data.readInt();
        long minimum = data.readUnsignedInt();

        return new StartOfAuthorityRecord(mName, rName, serial, refresh, retry, expire, minimum);
    }

}
