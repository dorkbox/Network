// Copyright (c) 2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import java.io.IOException;

import dorkbox.network.dns.Compression;
import dorkbox.network.dns.DnsInput;
import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsRecordType;
import dorkbox.network.dns.utils.Tokenizer;

/**
 * Mailbox information Record - lists the address responsible for a mailing
 * list/mailbox and the address to receive error messages relating to the
 * mailing list/mailbox.
 *
 * @author Brian Wellington
 */

public
class MINFORecord extends DnsRecord {

    private static final long serialVersionUID = -3962147172340353796L;

    private Name responsibleAddress;
    private Name errorAddress;

    MINFORecord() {}

    @Override
    DnsRecord getObject() {
        return new MINFORecord();
    }

    @Override
    void rrFromWire(DnsInput in) throws IOException {
        responsibleAddress = new Name(in);
        errorAddress = new Name(in);
    }

    @Override
    void rrToWire(DnsOutput out, Compression c, boolean canonical) {
        responsibleAddress.toWire(out, null, canonical);
        errorAddress.toWire(out, null, canonical);
    }

    /**
     * Converts the MINFO Record to a String
     */
    @Override
    void rrToString(StringBuilder sb) {
        sb.append(responsibleAddress);
        sb.append(" ");
        sb.append(errorAddress);
    }

    @Override
    void rdataFromString(Tokenizer st, Name origin) throws IOException {
        responsibleAddress = st.getName(origin);
        errorAddress = st.getName(origin);
    }

    /**
     * Creates an MINFO Record from the given data
     *
     * @param responsibleAddress The address responsible for the
     *         mailing list/mailbox.
     * @param errorAddress The address to receive error messages relating to the
     *         mailing list/mailbox.
     */
    public
    MINFORecord(Name name, int dclass, long ttl, Name responsibleAddress, Name errorAddress) {
        super(name, DnsRecordType.MINFO, dclass, ttl);

        this.responsibleAddress = checkName("responsibleAddress", responsibleAddress);
        this.errorAddress = checkName("errorAddress", errorAddress);
    }

    /**
     * Gets the address responsible for the mailing list/mailbox.
     */
    public
    Name getResponsibleAddress() {
        return responsibleAddress;
    }

    /**
     * Gets the address to receive error messages relating to the mailing
     * list/mailbox.
     */
    public
    Name getErrorAddress() {
        return errorAddress;
    }

}
