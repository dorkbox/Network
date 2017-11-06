// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import java.io.IOException;

import dorkbox.network.dns.Compression;
import dorkbox.network.dns.DnsInput;
import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.Name;
import dorkbox.network.dns.utils.Tokenizer;

/**
 * A class implementing Records with no data; that is, records used in
 * the question section of messages and meta-records in dynamic update.
 *
 * @author Brian Wellington
 */

class EmptyRecord extends DnsRecord {

    private static final long serialVersionUID = 3601852050646429582L;

    EmptyRecord() {}

    @Override
    DnsRecord getObject() {
        return new EmptyRecord();
    }

    @Override
    void rrFromWire(DnsInput in) throws IOException {
    }

    @Override
    void rrToWire(DnsOutput out, Compression c, boolean canonical) {
    }

    @Override
    void rrToString(StringBuilder sb) {
    }

    @Override
    void rdataFromString(Tokenizer st, Name origin) throws IOException {
    }

}
