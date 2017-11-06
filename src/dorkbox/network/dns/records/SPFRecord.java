// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import java.util.List;

import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsRecordType;

/**
 * Sender Policy Framework (RFC 4408, experimental)
 *
 * @author Brian Wellington
 */

public
class SPFRecord extends TXTBase {

    private static final long serialVersionUID = -2100754352801658722L;

    SPFRecord() {}

    @Override
    DnsRecord getObject() {
        return new SPFRecord();
    }

    /**
     * Creates a SPF Record from the given data
     *
     * @param strings The text strings
     *
     * @throws IllegalArgumentException One of the strings has invalid escapes
     */
    public
    SPFRecord(Name name, int dclass, long ttl, List strings) {
        super(name, DnsRecordType.SPF, dclass, ttl, strings);
    }

    /**
     * Creates a SPF Record from the given data
     *
     * @param string One text string
     *
     * @throws IllegalArgumentException The string has invalid escapes
     */
    public
    SPFRecord(Name name, int dclass, long ttl, String string) {
        super(name, DnsRecordType.SPF, dclass, ttl, string);
    }

}
