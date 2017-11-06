// Copyright (c) 2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import dorkbox.network.dns.Compression;
import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.Name;

/**
 * Implements common functionality for the many record types whose format
 * is a single compressed name.
 *
 * @author Brian Wellington
 */

abstract
class SingleCompressedNameBase extends SingleNameBase {

    private static final long serialVersionUID = -236435396815460677L;

    protected
    SingleCompressedNameBase() {}

    protected
    SingleCompressedNameBase(Name name, int type, int dclass, long ttl, Name singleName, String description) {
        super(name, type, dclass, ttl, singleName, description);
    }

    @Override
    void rrToWire(DnsOutput out, Compression c, boolean canonical) {
        singleName.toWire(out, c, canonical);
    }

}
