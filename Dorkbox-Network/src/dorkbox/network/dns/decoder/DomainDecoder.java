package dorkbox.network.dns.decoder;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.resolver.dns.DnsNameResolverAccess;

/**
 * Decodes domain names, such as NS (name * server) and CNAME (canonical name) resource records
 */
public
class DomainDecoder implements RecordDecoder<String> {

    @Override
    public
    String decode(final DnsRecord record, final ByteBuf response) {
        return DnsNameResolverAccess.decodeDomainName(response);
    }
}
