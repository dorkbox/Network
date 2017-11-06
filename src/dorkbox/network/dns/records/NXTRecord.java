// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import java.io.IOException;
import java.util.BitSet;

import dorkbox.network.dns.Compression;
import dorkbox.network.dns.DnsInput;
import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsRecordType;
import dorkbox.network.dns.utils.Tokenizer;

/**
 * Next name - this record contains the following name in an ordered list
 * of names in the zone, and a set of types for which records exist for
 * this name.  The presence of this record in a response signifies a
 * failed query for data in a DNSSEC-signed zone.
 *
 * @author Brian Wellington
 */

public
class NXTRecord extends DnsRecord {

    private static final long serialVersionUID = -8851454400765507520L;

    private Name next;
    private BitSet bitmap;

    NXTRecord() {}

    @Override
    DnsRecord getObject() {
        return new NXTRecord();
    }

    @Override
    void rrFromWire(DnsInput in) throws IOException {
        next = new Name(in);
        bitmap = new BitSet();
        int bitmapLength = in.remaining();
        for (int i = 0; i < bitmapLength; i++) {
            int t = in.readU8();
            for (int j = 0; j < 8; j++) {
                if ((t & (1 << (7 - j))) != 0) {
                    bitmap.set(i * 8 + j);
                }
            }
        }
    }

    @Override
    void rrToWire(DnsOutput out, Compression c, boolean canonical) {
        next.toWire(out, null, canonical);
        int length = bitmap.length();
        for (int i = 0, t = 0; i < length; i++) {
            t |= (bitmap.get(i) ? (1 << (7 - i % 8)) : 0);
            if (i % 8 == 7 || i == length - 1) {
                out.writeU8(t);
                t = 0;
            }
        }
    }

    /**
     * Converts rdata to a String
     */
    @Override
    void rrToString(StringBuilder sb) {
        sb.append(next);
        int length = bitmap.length();
        for (short i = 0; i < length; i++) {
            if (bitmap.get(i)) {
                sb.append(" ");
                sb.append(DnsRecordType.string(i));
            }
        }
    }

    @Override
    void rdataFromString(Tokenizer st, Name origin) throws IOException {
        next = st.getName(origin);
        bitmap = new BitSet();
        while (true) {
            Tokenizer.Token t = st.get();
            if (!t.isString()) {
                break;
            }
            int typecode = DnsRecordType.value(t.value, true);
            if (typecode <= 0 || typecode > 128) {
                throw st.exception("Invalid type: " + t.value);
            }
            bitmap.set(typecode);
        }
        st.unget();
    }

    /**
     * Creates an NXT Record from the given data
     *
     * @param next The following name in an ordered list of the zone
     * @param bitmap The set of type for which records exist at this name
     */
    public
    NXTRecord(Name name, int dclass, long ttl, Name next, BitSet bitmap) {
        super(name, DnsRecordType.NXT, dclass, ttl);
        this.next = checkName("next", next);
        this.bitmap = bitmap;
    }

    /**
     * Returns the next name
     */
    public
    Name getNext() {
        return next;
    }

    /**
     * Returns the set of types defined for this name
     */
    public
    BitSet getBitmap() {
        return bitmap;
    }

}
