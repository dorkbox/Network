// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsRecordType;

/**
 * NSAP Pointer Record  - maps a domain name representing an NSAP Address to
 * a hostname.
 *
 * @author Brian Wellington
 */

public
class NSAP_PTRRecord extends SingleNameBase {

    private static final long serialVersionUID = 2386284746382064904L;

    NSAP_PTRRecord() {}

    @Override
    DnsRecord getObject() {
        return new NSAP_PTRRecord();
    }

    /**
     * Creates a new NSAP_PTR Record with the given data
     *
     * @param target The name of the host with this address
     */
    public
    NSAP_PTRRecord(Name name, int dclass, long ttl, Name target) {
        super(name, DnsRecordType.NSAP_PTR, dclass, ttl, target, "target");
    }

    /**
     * Gets the target of the NSAP_PTR Record
     */
    public
    Name getTarget() {
        return getSingleName();
    }

}
