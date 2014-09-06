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
package dorkbox.network.dns;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.dns.DnsResource;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsType;
import io.netty.util.CharsetUtil;

import java.util.HashMap;
import java.util.Map;

import dorkbox.network.dns.decoder.AddressDecoder;
import dorkbox.network.dns.decoder.DomainDecoder;
import dorkbox.network.dns.decoder.MailExchangerDecoder;
import dorkbox.network.dns.decoder.RecordDecoder;
import dorkbox.network.dns.decoder.ServiceDecoder;
import dorkbox.network.dns.decoder.StartOfAuthorityDecoder;
import dorkbox.network.dns.decoder.TextDecoder;

/**
 * Handles the decoding of resource records. Some default decoders are mapped to
 * their resource types in the map {@code decoders}.
 */
public final class RecordDecoderFactory {

    private static RecordDecoderFactory factory = new RecordDecoderFactory(null);

    /**
     * Returns the active {@link RecordDecoderFactory}, which is the same as the
     * default factory if it has not been changed by the user.
     */
    public static RecordDecoderFactory getFactory() {
        return factory;
    }

    /**
     * Sets the active {@link RecordDecoderFactory} to be used for decoding
     * resource records.
     *
     * @param factory
     *            the {@link RecordDecoderFactory} to use
     */
    public static void setFactory(RecordDecoderFactory factory) {
        if (factory == null) {
            throw new NullPointerException("Cannot set record decoder factory to null.");
        }
        RecordDecoderFactory.factory = factory;
    }

    private final Map<DnsType, RecordDecoder<?>> decoders = new HashMap<DnsType, RecordDecoder<?>>();

    /**
     * Creates a new {@link RecordDecoderFactory} only using the default
     * decoders.
     */
    public RecordDecoderFactory() {
        this(true, null);
    }

    /**
     * Creates a new {@link RecordDecoderFactory} using the default decoders and
     * custom decoders (custom decoders override defaults).
     *
     * @param customDecoders
     *            user supplied resource record decoders, mapping the resource
     *            record's type to the decoder
     */
    public RecordDecoderFactory(Map<DnsType, RecordDecoder<?>> customDecoders) {
        this(true, customDecoders);
    }

    /**
     * Creates a {@link RecordDecoderFactory} using either custom resource
     * record decoders, default decoders, or both. If a custom decoder has the
     * same record type as a default decoder, the default decoder is overridden.
     *
     * @param useDefaultDecoders
     *            if {@code true}, adds default decoders
     * @param customDecoders
     *            if not {@code null} or empty, adds custom decoders
     */
    public RecordDecoderFactory(boolean useDefaultDecoders, Map<DnsType, RecordDecoder<?>> customDecoders) {
        if (!useDefaultDecoders && (customDecoders == null || customDecoders.isEmpty())) {
            throw new IllegalStateException("No decoders have been included to be used with this factory.");
        }

        if (useDefaultDecoders) {
            this.decoders.put(DnsType.A, new AddressDecoder(4));
            this.decoders.put(DnsType.AAAA, new AddressDecoder(16));
            this.decoders.put(DnsType.MX, new MailExchangerDecoder());
            this.decoders.put(DnsType.TXT, new TextDecoder());
            this.decoders.put(DnsType.SRV, new ServiceDecoder());

            RecordDecoder<?> decoder = new DomainDecoder();
            this.decoders.put(DnsType.NS, decoder);
            this.decoders.put(DnsType.CNAME, decoder);
            this.decoders.put(DnsType.PTR, decoder);
            this.decoders.put(DnsType.SOA, new StartOfAuthorityDecoder());
        }

        if (customDecoders != null) {
            this.decoders.putAll(customDecoders);
        }
    }

    /**
     * Decodes a resource record and returns the result.
     *
     * @param response
     *            the DNS response that contains the resource record being
     *            decoded
     * @param resource
     *            the resource record being decoded
     * @return the decoded resource record
     */
    @SuppressWarnings("unchecked")
    public <T> T decode(DnsResponse response, DnsResource resource) {
        DnsType type = resource.type();
        RecordDecoder<?> decoder = this.decoders.get(type);

        if (decoder == null) {
            throw new IllegalStateException("Unsupported resource record type [id: " + type + "].");
        }
        T result = null;
        try {
            result = (T) decoder.decode(response, resource);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }


    /**
     * Retrieves a domain name given a buffer containing a DNS packet. If the
     * name contains a pointer, the position of the buffer will be set to
     * directly after the pointer's index after the name has been read.
     *
     * @param buf
     *            the byte buffer containing the DNS packet
     * @return the domain name for an entry
     */
    public static String readName(ByteBuf buf) {
        int position = -1;
        int checked = 0;
        int length = buf.writerIndex();
        StringBuilder name = new StringBuilder();
        for (int len = buf.readUnsignedByte(); buf.isReadable() && len != 0; len = buf.readUnsignedByte()) {
            boolean pointer = (len & 0xc0) == 0xc0;
            if (pointer) {
                if (position == -1) {
                    position = buf.readerIndex() + 1;
                }
                buf.readerIndex((len & 0x3f) << 8 | buf.readUnsignedByte());
                // check for loops
                checked += 2;
                if (checked >= length) {
                    throw new CorruptedFrameException("name contains a loop.");
                }
            } else {
                name.append(buf.toString(buf.readerIndex(), len, CharsetUtil.UTF_8)).append('.');
                buf.skipBytes(len);
            }
        }
        if (position != -1) {
            buf.readerIndex(position);
        }
        if (name.length() == 0) {
            return "";
        }

        return name.substring(0, name.length() - 1);
    }

    /**
     * Retrieves a domain name given a buffer containing a DNS packet without
     * advancing the readerIndex for the buffer.
     *
     * @param buf    the byte buffer containing the DNS packet
     * @param offset the position at which the name begins
     * @return the domain name for an entry
     */
    public static String getName(ByteBuf buf, int offset) {
      StringBuilder name = new StringBuilder();
      for (int len = buf.getUnsignedByte(offset++); buf.writerIndex() > offset && len != 0; len = buf
        .getUnsignedByte(offset++)) {
        boolean pointer = (len & 0xc0) == 0xc0;
        if (pointer) {
          offset = (len & 0x3f) << 8 | buf.getUnsignedByte(offset++);
        } else {
          name.append(buf.toString(offset, len, CharsetUtil.UTF_8)).append(".");
          offset += len;
        }
      }
      if (name.length() == 0) {
        return null;
      }
      return name.substring(0, name.length() - 1);
    }
}
