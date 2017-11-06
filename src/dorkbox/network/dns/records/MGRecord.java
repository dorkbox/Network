// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsRecordType;

/**
 * Mail Group Record  - specifies a mailbox which is a member of a mail group.
 *
 * @author Brian Wellington
 */

public
class MGRecord extends SingleNameBase {

    private static final long serialVersionUID = -3980055550863644582L;

    MGRecord() {}

    @Override
    DnsRecord getObject() {
        return new MGRecord();
    }

    /**
     * Creates a new MG Record with the given data
     *
     * @param mailbox The mailbox that is a member of the group specified by the
     *         domain.
     */
    public
    MGRecord(Name name, int dclass, long ttl, Name mailbox) {
        super(name, DnsRecordType.MG, dclass, ttl, mailbox, "mailbox");
    }

    /**
     * Gets the mailbox in the mail group specified by the domain
     */
    public
    Name getMailbox() {
        return getSingleName();
    }

}
