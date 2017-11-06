// Copyright (c) 2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import java.io.IOException;

import dorkbox.network.dns.Compression;
import dorkbox.network.dns.DnsInput;
import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsRecordType;
import dorkbox.network.dns.exceptions.TextParseException;
import dorkbox.network.dns.utils.Tokenizer;

/**
 * ISDN - identifies the ISDN number and subaddress associated with a name.
 *
 * @author Brian Wellington
 */

public
class ISDNRecord extends DnsRecord {

    private static final long serialVersionUID = -8730801385178968798L;

    private byte[] address;
    private byte[] subAddress;

    ISDNRecord() {}

    @Override
    DnsRecord getObject() {
        return new ISDNRecord();
    }

    @Override
    void rrFromWire(DnsInput in) throws IOException {
        address = in.readCountedString();
        if (in.remaining() > 0) {
            subAddress = in.readCountedString();
        }
    }

    @Override
    void rrToWire(DnsOutput out, Compression c, boolean canonical) {
        out.writeCountedString(address);
        if (subAddress != null) {
            out.writeCountedString(subAddress);
        }
    }

    @Override
    void rrToString(StringBuilder sb) {
        sb.append(byteArrayToString(address, true));

        if (subAddress != null) {
            sb.append(" ");
            sb.append(byteArrayToString(subAddress, true));
        }
    }

    @Override
    void rdataFromString(Tokenizer st, Name origin) throws IOException {
        try {
            address = byteArrayFromString(st.getString());
            Tokenizer.Token t = st.get();
            if (t.isString()) {
                subAddress = byteArrayFromString(t.value);
            }
            else {
                st.unget();
            }
        } catch (TextParseException e) {
            throw st.exception(e.getMessage());
        }
    }

    /**
     * Creates an ISDN Record from the given data
     *
     * @param address The ISDN number associated with the domain.
     * @param subAddress The subaddress, if any.
     *
     * @throws IllegalArgumentException One of the strings is invalid.
     */
    public
    ISDNRecord(Name name, int dclass, long ttl, String address, String subAddress) {
        super(name, DnsRecordType.ISDN, dclass, ttl);
        try {
            this.address = byteArrayFromString(address);
            if (subAddress != null) {
                this.subAddress = byteArrayFromString(subAddress);
            }
        } catch (TextParseException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    /**
     * Returns the ISDN number associated with the domain.
     */
    public
    String getAddress() {
        return byteArrayToString(address, false);
    }

    /**
     * Returns the ISDN subaddress, or null if there is none.
     */
    public
    String getSubAddress() {
        if (subAddress == null) {
            return null;
        }
        return byteArrayToString(subAddress, false);
    }

}
