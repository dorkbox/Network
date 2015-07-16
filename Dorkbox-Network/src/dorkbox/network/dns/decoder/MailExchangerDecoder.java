package dorkbox.network.dns.decoder;

import dorkbox.network.dns.record.MailExchangerRecord;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.resolver.dns.DnsNameResolverAccess;

/**
 * Decodes MX (mail exchanger) resource records.
 */
public class MailExchangerDecoder implements RecordDecoder<MailExchangerRecord> {

    /**
     * Returns a decoded MX (mail exchanger) resource record
     */
    @Override
    public MailExchangerRecord decode(final DnsRecord record, final ByteBuf response) {
        int priority = response.readUnsignedShort();

        String name = DnsNameResolverAccess.decodeDomainName(response);
        return new MailExchangerRecord(priority, name);
    }
}
