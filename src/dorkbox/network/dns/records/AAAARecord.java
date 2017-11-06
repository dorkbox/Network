// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

import dorkbox.network.dns.Compression;
import dorkbox.network.dns.DnsInput;
import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsRecordType;
import dorkbox.network.dns.utils.Address;
import dorkbox.network.dns.utils.Tokenizer;

/**
 * IPv6 Address Record - maps a domain name to an IPv6 address
 *
 * @author Brian Wellington
 */

public
class AAAARecord extends DnsRecord {

    private static final long serialVersionUID = -4588601512069748050L;

    private byte[] address;

    AAAARecord() {}

    @Override
    DnsRecord getObject() {
        return new AAAARecord();
    }

    @Override
    void rrFromWire(DnsInput in) throws IOException {
        address = in.readByteArray(16);
    }

    @Override
    void rrToWire(DnsOutput out, Compression c, boolean canonical) {
        out.writeByteArray(address);
    }

    /**
     * Converts rdata to a String
     */
    @Override
    void rrToString(StringBuilder sb) {
        InetAddress addr;
        try {
            addr = InetAddress.getByAddress(null, address);
        } catch (UnknownHostException e) {
            return;
        }
        if (addr.getAddress().length == 4) {
            // Deal with Java's broken handling of mapped IPv4 addresses.
            sb.append("0:0:0:0:0:ffff:");
            int high = ((address[12] & 0xFF) << 8) + (address[13] & 0xFF);
            int low = ((address[14] & 0xFF) << 8) + (address[15] & 0xFF);
            sb.append(Integer.toHexString(high));
            sb.append(':');
            sb.append(Integer.toHexString(low));

            return;
        }

        sb.append(addr.getHostAddress());
    }

    @Override
    void rdataFromString(Tokenizer st, Name origin) throws IOException {
        address = st.getAddressBytes(Address.IPv6);
    }

    /**
     * Creates an AAAA Record from the given data
     *
     * @param address The address suffix
     */
    public
    AAAARecord(Name name, int dclass, long ttl, InetAddress address) {
        super(name, DnsRecordType.AAAA, dclass, ttl);
        if (Address.familyOf(address) != Address.IPv6) {
            throw new IllegalArgumentException("invalid IPv6 address");
        }
        this.address = address.getAddress();
    }

    /**
     * Returns the address
     */
    public
    InetAddress getAddress() {
        try {
            if (name == null) {
                return InetAddress.getByAddress(address);
            }
            else {
                return InetAddress.getByAddress(name.toString(true), address);
            }
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
