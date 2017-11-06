// Copyright (c) 2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import dorkbox.network.dns.Compression;
import dorkbox.network.dns.DnsInput;
import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsRecordType;
import dorkbox.network.dns.utils.Tokenizer;
import dorkbox.network.dns.utils.base16;

/**
 * NSAP Address Record.
 *
 * @author Brian Wellington
 */

public
class NSAPRecord extends DnsRecord {

    private static final long serialVersionUID = -1037209403185658593L;

    private byte[] address;

    NSAPRecord() {}

    @Override
    DnsRecord getObject() {
        return new NSAPRecord();
    }

    @Override
    void rrFromWire(DnsInput in) throws IOException {
        address = in.readByteArray();
    }

    @Override
    void rrToWire(DnsOutput out, Compression c, boolean canonical) {
        out.writeByteArray(address);
    }

    @Override
    void rrToString(StringBuilder sb) {
        sb.append("0x")
          .append(base16.toString(address));
    }

    @Override
    void rdataFromString(Tokenizer st, Name origin) throws IOException {
        String addr = st.getString();
        this.address = checkAndConvertAddress(addr);
        if (this.address == null) {
            throw st.exception("invalid NSAP address " + addr);
        }
    }

    /**
     * Creates an NSAP Record from the given data
     *
     * @param address The NSAP address.
     *
     * @throws IllegalArgumentException The address is not a valid NSAP address.
     */
    public
    NSAPRecord(Name name, int dclass, long ttl, String address) {
        super(name, DnsRecordType.NSAP, dclass, ttl);
        this.address = checkAndConvertAddress(address);
        if (this.address == null) {
            throw new IllegalArgumentException("invalid NSAP address " + address);
        }
    }

    private static
    byte[] checkAndConvertAddress(String address) {
        if (!address.substring(0, 2)
                    .equalsIgnoreCase("0x")) {
            return null;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean partial = false;
        int current = 0;
        for (int i = 2; i < address.length(); i++) {
            char c = address.charAt(i);
            if (c == '.') {
                continue;
            }
            int value = Character.digit(c, 16);
            if (value == -1) {
                return null;
            }
            if (partial) {
                current += value;
                bytes.write(current);
                partial = false;
            }
            else {
                current = value << 4;
                partial = true;
            }

        }
        if (partial) {
            return null;
        }
        return bytes.toByteArray();
    }

    /**
     * Returns the NSAP address.
     */
    public
    String getAddress() {
        return byteArrayToString(address, false);
    }

}
