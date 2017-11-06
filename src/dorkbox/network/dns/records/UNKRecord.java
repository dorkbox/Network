// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import java.io.IOException;

import dorkbox.network.dns.Compression;
import dorkbox.network.dns.DnsInput;
import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.Name;
import dorkbox.network.dns.utils.Tokenizer;

/**
 * A class implementing Records of unknown and/or unimplemented types.  This
 * class can only be initialized using static Record initializers.
 *
 * @author Brian Wellington
 */

public
class UNKRecord extends DnsRecord {

    private static final long serialVersionUID = -4193583311594626915L;

    private byte[] data;

    UNKRecord() {}

    @Override
    DnsRecord getObject() {
        return new UNKRecord();
    }

    @Override
    void rrFromWire(DnsInput in) throws IOException {
        data = in.readByteArray();
    }

    @Override
    void rrToWire(DnsOutput out, Compression c, boolean canonical) {
        out.writeByteArray(data);
    }

    /**
     * Converts this Record to the String "unknown format"
     */
    @Override
    void rrToString(StringBuilder sb) {
        sb.append(unknownToString(data));
    }

    @Override
    void rdataFromString(Tokenizer st, Name origin) throws IOException {
        throw st.exception("invalid unknown RR encoding");
    }

    /**
     * Returns the contents of this record.
     */
    public
    byte[] getData() {
        return data;
    }

}
