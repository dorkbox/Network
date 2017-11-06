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
 * is a single name.
 *
 * @author Brian Wellington
 */

abstract
class SingleNameBase extends DnsRecord {

    private static final long serialVersionUID = -18595042501413L;

    protected Name singleName;

    protected
    SingleNameBase() {}

    protected
    SingleNameBase(Name name, int type, int dclass, long ttl) {
        super(name, type, dclass, ttl);
    }

    protected
    SingleNameBase(Name name, int type, int dclass, long ttl, Name singleName, String description) {
        super(name, type, dclass, ttl);
        this.singleName = checkName(description, singleName);
    }

    @Override
    void rrFromWire(DnsInput in) throws IOException {
        singleName = new Name(in);
    }

    @Override
    void rrToWire(DnsOutput out, Compression c, boolean canonical) {
        singleName.toWire(out, null, canonical);
    }

    @Override
    void rrToString(StringBuilder sb) {
        sb.append(singleName.toString());
    }

    @Override
    void rdataFromString(Tokenizer st, Name origin) throws IOException {
        singleName = st.getName(origin);
    }

    protected
    Name getSingleName() {
        return singleName;
    }

}
