// Copyright (c) 2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import java.io.IOException;

import dorkbox.network.dns.Compression;
import dorkbox.network.dns.DnsInput;
import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsRecordType;
import dorkbox.network.dns.utils.Tokenizer;

/**
 * The NULL Record.  This has no defined purpose, but can be used to
 * hold arbitrary data.
 *
 * @author Brian Wellington
 */

public
class NULLRecord extends DnsRecord {

    private static final long serialVersionUID = -5796493183235216538L;

    private byte[] data;

    NULLRecord() {}

    @Override
    DnsRecord getObject() {
        return new NULLRecord();
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
        sb.append(unknownToString(data));
    }

    @Override
    void rdataFromString(Tokenizer st, Name origin) throws IOException {
        throw st.exception("no defined text format for NULL records");
    }

    /**
     * Creates a NULL record from the given data.
     *
     * @param data The contents of the record.
     */
    public
    NULLRecord(Name name, int dclass, long ttl, byte[] data) {
        super(name, DnsRecordType.NULL, dclass, ttl);

        if (data.length > 0xFFFF) {
            throw new IllegalArgumentException("data must be <65536 bytes");
        }
        this.data = data;
    }

    /**
     * Returns the contents of this record.
     */
    public
    byte[] getData() {
        return data;
    }

}
