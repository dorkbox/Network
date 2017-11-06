// Implemented by Anthony Kirby (anthony@anthony.org)
// based on SRVRecord.java Copyright (c) 1999-2004 Brian Wellington

package dorkbox.network.dns.records;

import java.io.IOException;

import dorkbox.network.dns.Compression;
import dorkbox.network.dns.DnsInput;
import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsRecordType;
import dorkbox.network.dns.exceptions.TextParseException;
import dorkbox.network.dns.utils.Tokenizer;

/**
 * Uniform Resource Identifier (URI) DNS Resource Record
 *
 * @author Anthony Kirby
 * @see <a href="http://tools.ietf.org/html/draft-faltstrom-uri">http://tools.ietf.org/html/draft-faltstrom-uri</a>
 */

public
class URIRecord extends DnsRecord {

    private static final long serialVersionUID = 7955422413971804232L;

    private int priority, weight;
    private byte[] target;

    URIRecord() {
        target = new byte[] {};
    }

    @Override
    DnsRecord getObject() {
        return new URIRecord();
    }

    @Override
    void rrFromWire(DnsInput in) throws IOException {
        priority = in.readU16();
        weight = in.readU16();
        target = in.readByteArray();
    }

    @Override
    void rrToWire(DnsOutput out, Compression c, boolean canonical) {
        out.writeU16(priority);
        out.writeU16(weight);
        out.writeByteArray(target);
    }

    /**
     * Converts rdata to a String
     */
    @Override
    void rrToString(StringBuilder sb) {
        sb.append(priority + " ");
        sb.append(weight + " ");
        sb.append(byteArrayToString(target, true));
    }

    @Override
    void rdataFromString(Tokenizer st, Name origin) throws IOException {
        priority = st.getUInt16();
        weight = st.getUInt16();
        try {
            target = byteArrayFromString(st.getString());
        } catch (TextParseException e) {
            throw st.exception(e.getMessage());
        }
    }

    /**
     * Creates a URI Record from the given data
     *
     * @param priority The priority of this URI.  Records with lower priority
     *         are preferred.
     * @param weight The weight, used to select between records at the same
     *         priority.
     * @param target The host/port running the service
     */
    public
    URIRecord(Name name, int dclass, long ttl, int priority, int weight, String target) {
        super(name, DnsRecordType.URI, dclass, ttl);
        this.priority = checkU16("priority", priority);
        this.weight = checkU16("weight", weight);
        try {
            this.target = byteArrayFromString(target);
        } catch (TextParseException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    /**
     * Returns the priority
     */
    public
    int getPriority() {
        return priority;
    }

    /**
     * Returns the weight
     */
    public
    int getWeight() {
        return weight;
    }

    /**
     * Returns the target URI
     */
    public
    String getTarget() {
        return byteArrayToString(target, false);
    }

}
