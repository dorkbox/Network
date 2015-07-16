package dorkbox.network.dns.decoder;

import dorkbox.network.dns.record.StartOfAuthorityRecord;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.resolver.dns.DnsNameResolverAccess;

/**
 * Decodes SOA (start of authority) resource records.
 */
public class StartOfAuthorityDecoder implements RecordDecoder<StartOfAuthorityRecord> {

    /**
     * Returns a decoded SOA (start of authority) resource record
     */
    @Override
    public StartOfAuthorityRecord decode(final DnsRecord record, final ByteBuf response) {
        String mName = DnsNameResolverAccess.decodeDomainName(response);
        String rName = DnsNameResolverAccess.decodeDomainName(response);

        long serial = response.readUnsignedInt();
        int refresh = response.readInt();
        int retry = response.readInt();
        int expire = response.readInt();
        long minimum = response.readUnsignedInt();

        return new StartOfAuthorityRecord(mName, rName, serial, refresh, retry, expire, minimum);
    }
}
