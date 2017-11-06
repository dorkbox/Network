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
 * Certification Authority Authorization
 *
 * @author Brian Wellington
 */

public
class CAARecord extends DnsRecord {

    private static final long serialVersionUID = 8544304287274216443L;
    private int flags;
    private byte[] tag;
    private byte[] value;


    public static
    class Flags {
        public static final int IssuerCritical = 128;

        private
        Flags() {}
    }

    CAARecord() {}

    @Override
    DnsRecord getObject() {
        return new CAARecord();
    }

    @Override
    void rrFromWire(DnsInput in) throws IOException {
        flags = in.readU8();
        tag = in.readCountedString();
        value = in.readByteArray();
    }

    @Override
    void rrToWire(DnsOutput out, Compression c, boolean canonical) {
        out.writeU8(flags);
        out.writeCountedString(tag);
        out.writeByteArray(value);
    }

    @Override
    void rrToString(StringBuilder sb) {
        sb.append(flags);
        sb.append(" ");
        sb.append(byteArrayToString(tag, false));
        sb.append(" ");
        sb.append(byteArrayToString(value, true));
    }

    @Override
    void rdataFromString(Tokenizer st, Name origin) throws IOException {
        flags = st.getUInt8();
        try {
            tag = byteArrayFromString(st.getString());
            value = byteArrayFromString(st.getString());
        } catch (TextParseException e) {
            throw st.exception(e.getMessage());
        }
    }

    /**
     * Creates an CAA Record from the given data.
     *
     * @param flags The flags.
     * @param tag The tag.
     * @param value The value.
     */
    public
    CAARecord(Name name, int dclass, long ttl, int flags, String tag, String value) {
        super(name, DnsRecordType.CAA, dclass, ttl);
        this.flags = checkU8("flags", flags);
        try {
            this.tag = byteArrayFromString(tag);
            this.value = byteArrayFromString(value);
        } catch (TextParseException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    /**
     * Returns the flags.
     */
    public
    int getFlags() {
        return flags;
    }

    /**
     * Returns the tag.
     */
    public
    String getTag() {
        return byteArrayToString(tag, false);
    }

    /**
     * Returns the value
     */
    public
    String getValue() {
        return byteArrayToString(value, false);
    }

}
