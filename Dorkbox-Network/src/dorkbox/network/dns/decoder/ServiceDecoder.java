package dorkbox.network.dns.decoder;

import dorkbox.network.dns.record.ServiceRecord;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.resolver.dns.DnsNameResolverAccess;

/**
 * Decodes SRV (service) resource records.
 */
public class ServiceDecoder implements RecordDecoder<ServiceRecord> {

    /**
     * Returns a decoded SRV (service) resource record
     */
    @Override
    public ServiceRecord decode(final DnsRecord record, final ByteBuf response) {
        int priority = response.readShort();
        int weight = response.readShort();
        int port = response.readUnsignedShort();
        String target =  DnsNameResolverAccess.decodeDomainName(response);

        return new ServiceRecord(record.name(), priority, weight, port, target);
    }
}
