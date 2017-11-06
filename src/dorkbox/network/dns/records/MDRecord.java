// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsRecordType;

/**
 * Mail Destination Record  - specifies a mail agent which delivers mail
 * for a domain (obsolete)
 *
 * @author Brian Wellington
 */

public
class MDRecord extends SingleNameBase {

    private static final long serialVersionUID = 5268878603762942202L;

    MDRecord() {}

    @Override
    DnsRecord getObject() {
        return new MDRecord();
    }

    @Override
    public
    Name getAdditionalName() {
        return getSingleName();
    }

    /**
     * Creates a new MD Record with the given data
     *
     * @param mailAgent The mail agent that delivers mail for the domain.
     */
    public
    MDRecord(Name name, int dclass, long ttl, Name mailAgent) {
        super(name, DnsRecordType.MD, dclass, ttl, mailAgent, "mail agent");
    }

    /**
     * Gets the mail agent for the domain
     */
    public
    Name getMailAgent() {
        return getSingleName();
    }

}
