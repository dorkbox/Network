package dorkbox.network.dns.decoder;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.dns.DnsRecord;

public
interface RecordDecoder<T> {

    T decode(final DnsRecord record, final ByteBuf response) throws DecoderException;
}
