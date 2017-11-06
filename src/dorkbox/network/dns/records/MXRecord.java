// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import dorkbox.network.dns.Compression;
import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsRecordType;

/**
 * Mail Exchange - specifies where mail to a domain is sent
 *
 * @author Brian Wellington
 */

public
class MXRecord extends U16NameBase {

    private static final long serialVersionUID = 2914841027584208546L;

    MXRecord() {}

    @Override
    DnsRecord getObject() {
        return new MXRecord();
    }

    @Override
    public
    Name getAdditionalName() {
        return getNameField();
    }

    /**
     * Creates an MX Record from the given data
     *
     * @param priority The priority of this MX.  Records with lower priority
     *         are preferred.
     * @param target The host that mail is sent to
     */
    public
    MXRecord(Name name, int dclass, long ttl, int priority, Name target) {
        super(name, DnsRecordType.MX, dclass, ttl, priority, "priority", target, "target");
    }

    /**
     * Returns the target of the MX record
     */
    public
    Name getTarget() {
        return getNameField();
    }

    /**
     * Returns the priority of this MX record
     */
    public
    int getPriority() {
        return getU16Field();
    }

    @Override
    void rrToWire(DnsOutput out, Compression c, boolean canonical) {
        out.writeU16(u16Field);
        nameField.toWire(out, c, canonical);
    }

}
