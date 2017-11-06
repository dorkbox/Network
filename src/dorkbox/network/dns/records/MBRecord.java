// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsRecordType;

/**
 * Mailbox Record  - specifies a host containing a mailbox.
 *
 * @author Brian Wellington
 */

public
class MBRecord extends SingleNameBase {

    private static final long serialVersionUID = 532349543479150419L;

    MBRecord() {}

    @Override
    DnsRecord getObject() {
        return new MBRecord();
    }

    @Override
    public
    Name getAdditionalName() {
        return getSingleName();
    }

    /**
     * Creates a new MB Record with the given data
     *
     * @param mailbox The host containing the mailbox for the domain.
     */
    public
    MBRecord(Name name, int dclass, long ttl, Name mailbox) {
        super(name, DnsRecordType.MB, dclass, ttl, mailbox, "mailbox");
    }

    /**
     * Gets the mailbox for the domain
     */
    public
    Name getMailbox() {
        return getSingleName();
    }

}
