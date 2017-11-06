// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import java.io.IOException;

import dorkbox.network.dns.Compression;
import dorkbox.network.dns.DnsInput;
import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsRecordType;
import dorkbox.network.dns.utils.Tokenizer;

/**
 * Responsible Person Record - lists the mail address of a responsible person
 * and a domain where TXT records are available.
 *
 * @author Tom Scola (tscola@research.att.com)
 * @author Brian Wellington
 */

public
class RPRecord extends DnsRecord {

    private static final long serialVersionUID = 8124584364211337460L;

    private Name mailbox;
    private Name textDomain;

    RPRecord() {}

    @Override
    DnsRecord getObject() {
        return new RPRecord();
    }

    @Override
    void rrFromWire(DnsInput in) throws IOException {
        mailbox = new Name(in);
        textDomain = new Name(in);
    }

    @Override
    void rrToWire(DnsOutput out, Compression c, boolean canonical) {
        mailbox.toWire(out, null, canonical);
        textDomain.toWire(out, null, canonical);
    }

    /**
     * Converts the RP Record to a String
     */
    @Override
    void rrToString(StringBuilder sb) {
        sb.append(mailbox);
        sb.append(" ");
        sb.append(textDomain);
    }

    @Override
    void rdataFromString(Tokenizer st, Name origin) throws IOException {
        mailbox = st.getName(origin);
        textDomain = st.getName(origin);
    }

    /**
     * Creates an RP Record from the given data
     *
     * @param mailbox The responsible person
     * @param textDomain The address where TXT records can be found
     */
    public
    RPRecord(Name name, int dclass, long ttl, Name mailbox, Name textDomain) {
        super(name, DnsRecordType.RP, dclass, ttl);

        this.mailbox = checkName("mailbox", mailbox);
        this.textDomain = checkName("textDomain", textDomain);
    }

    /**
     * Gets the mailbox address of the RP Record
     */
    public
    Name getMailbox() {
        return mailbox;
    }

    /**
     * Gets the text domain info of the RP Record
     */
    public
    Name getTextDomain() {
        return textDomain;
    }

}
