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
import dorkbox.network.dns.record.ServiceRecord;

/**
 * Decodes SRV (service) resource records.
 */
public class ServiceDecoder implements RecordDecoder<ServiceRecord> {

    /**
     * Returns a decoded SRV (service) resource record, stored as an instance of
     * {@link ServiceRecord}.
     *
     * @param response
     *            the DNS response that contains the resource record being
     *            decoded
     * @param resource
     *            the resource record being decoded
     */
    @Override
    public ServiceRecord decode(DnsResponse response, DnsResource resource) {
        ByteBuf packet = resource.content();
        int priority = packet.readShort();
        int weight = packet.readShort();
        int port = packet.readUnsignedShort();
        String target = RecordDecoderFactory.readName(packet);
        return new ServiceRecord(resource.name(), priority, weight, port, target);
    }

}
