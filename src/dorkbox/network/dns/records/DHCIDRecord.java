// Copyright (c) 2008 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import java.io.IOException;

import dorkbox.network.dns.Compression;
import dorkbox.network.dns.DnsInput;
import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsRecordType;
import dorkbox.network.dns.utils.Tokenizer;
import dorkbox.util.Base64Fast;

/**
 * DHCID - Dynamic Host Configuration Protocol (DHCP) ID (RFC 4701)
 *
 * @author Brian Wellington
 */

public
class DHCIDRecord extends DnsRecord {

    private static final long serialVersionUID = -8214820200808997707L;

    private byte[] data;

    DHCIDRecord() {}

    @Override
    DnsRecord getObject() {
        return new DHCIDRecord();
    }

    @Override
    void rrFromWire(DnsInput in) throws IOException {
        data = in.readByteArray();
    }

    @Override
    void rrToWire(DnsOutput out, Compression c, boolean canonical) {
        out.writeByteArray(data);
    }

    @Override
    void rrToString(StringBuilder sb) {
        sb.append(Base64Fast.encode2(data));
    }

    @Override
    void rdataFromString(Tokenizer st, Name origin) throws IOException {
        data = st.getBase64();
    }

    /**
     * Creates an DHCID Record from the given data
     *
     * @param data The binary data, which is opaque to DNS.
     */
    public
    DHCIDRecord(Name name, int dclass, long ttl, byte[] data) {
        super(name, DnsRecordType.DHCID, dclass, ttl);
        this.data = data;
    }

    /**
     * Returns the binary data.
     */
    public
    byte[] getData() {
        return data;
    }

}
