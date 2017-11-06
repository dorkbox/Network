// Copyright (c) 2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import java.io.IOException;

import dorkbox.network.dns.Compression;
import dorkbox.network.dns.DnsInput;
import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.Name;
import dorkbox.network.dns.utils.Tokenizer;

/**
 * Implements common functionality for the many record types whose format
 * is an unsigned 16 bit integer followed by a name.
 *
 * @author Brian Wellington
 */

abstract
class U16NameBase extends DnsRecord {

    private static final long serialVersionUID = -8315884183112502995L;

    protected int u16Field;
    protected Name nameField;

    protected
    U16NameBase() {}

    protected
    U16NameBase(Name name, int type, int dclass, long ttl) {
        super(name, type, dclass, ttl);
    }

    protected
    U16NameBase(Name name, int type, int dclass, long ttl, int u16Field, String u16Description, Name nameField, String nameDescription) {
        super(name, type, dclass, ttl);
        this.u16Field = checkU16(u16Description, u16Field);
        this.nameField = checkName(nameDescription, nameField);
    }

    @Override
    void rrFromWire(DnsInput in) throws IOException {
        u16Field = in.readU16();
        nameField = new Name(in);
    }

    @Override
    void rrToWire(DnsOutput out, Compression c, boolean canonical) {
        out.writeU16(u16Field);
        nameField.toWire(out, null, canonical);
    }

    @Override
    void rrToString(StringBuilder sb) {
        sb.append(u16Field);
        sb.append(" ");
        sb.append(nameField);
    }

    @Override
    void rdataFromString(Tokenizer st, Name origin) throws IOException {
        u16Field = st.getUInt16();
        nameField = st.getName(origin);
    }

    protected
    int getU16Field() {
        return u16Field;
    }

    protected
    Name getNameField() {
        return nameField;
    }

}
