// Copyright (c) 2003-2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import java.io.IOException;
import java.util.Iterator;

import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsClass;
import dorkbox.network.dns.constants.DnsOpCode;
import dorkbox.network.dns.constants.DnsRecordType;
import dorkbox.network.dns.constants.DnsSection;
import dorkbox.network.dns.exceptions.RelativeNameException;
import dorkbox.network.dns.utils.Tokenizer;

/**
 * A helper class for constructing dynamic DNS (DDNS) update messages.
 *
 * @author Brian Wellington
 */

public
class Update extends DnsMessage {

    private Name origin;
    private int dclass;

    /**
     * Creates an update message.  The class is assumed to be IN.
     *
     * @param zone The name of the zone being updated.
     */
    public
    Update(Name zone) {
        this(zone, DnsClass.IN);
    }

    /**
     * Creates an update message.
     *
     * @param zone The name of the zone being updated.
     * @param dclass The class of the zone being updated.
     */
    public
    Update(Name zone, int dclass) {
        super();
        if (!zone.isAbsolute()) {
            throw new RelativeNameException(zone);
        }
        DnsClass.check(dclass);
        getHeader().setOpcode(DnsOpCode.UPDATE);
        DnsRecord soa = DnsRecord.newRecord(zone, DnsRecordType.SOA, DnsClass.IN);
        addRecord(soa, DnsSection.QUESTION);
        this.origin = zone;
        this.dclass = dclass;
    }

    /**
     * Inserts a prerequisite that the specified name exists; that is, there
     * exist records with the given name in the zone.
     */
    public
    void present(Name name) {
        newPrereq(DnsRecord.newRecord(name, DnsRecordType.ANY, DnsClass.ANY, 0));
    }

    private
    void newPrereq(DnsRecord rec) {
        addRecord(rec, DnsSection.PREREQ);
    }

    /**
     * Inserts a prerequisite that the specified rrset exists; that is, there
     * exist records with the given name and type in the zone.
     */
    public
    void present(Name name, int type) {
        newPrereq(DnsRecord.newRecord(name, type, DnsClass.ANY, 0));
    }

    /**
     * Parses a record from the string, and inserts a prerequisite that the
     * record exists.  Due to the way value-dependent prequisites work, the
     * condition that must be met is that the set of all records with the same
     * and type in the update message must be identical to the set of all records
     * with that name and type on the server.
     *
     * @throws IOException The record could not be parsed.
     */
    public
    void present(Name name, int type, String record) throws IOException {
        newPrereq(DnsRecord.fromString(name, type, dclass, 0, record, origin));
    }

    /**
     * Parses a record from the tokenizer, and inserts a prerequisite that the
     * record exists.  Due to the way value-dependent prequisites work, the
     * condition that must be met is that the set of all records with the same
     * and type in the update message must be identical to the set of all records
     * with that name and type on the server.
     *
     * @throws IOException The record could not be parsed.
     */
    public
    void present(Name name, int type, Tokenizer tokenizer) throws IOException {
        newPrereq(DnsRecord.fromString(name, type, dclass, 0, tokenizer, origin));
    }

    /**
     * Inserts a prerequisite that the specified record exists.  Due to the way
     * value-dependent prequisites work, the condition that must be met is that
     * the set of all records with the same and type in the update message must
     * be identical to the set of all records with that name and type on the server.
     */
    public
    void present(DnsRecord record) {
        newPrereq(record);
    }

    /**
     * Inserts a prerequisite that the specified name does not exist; that is,
     * there are no records with the given name in the zone.
     */
    public
    void absent(Name name) {
        newPrereq(DnsRecord.newRecord(name, DnsRecordType.ANY, DnsClass.NONE, 0));
    }

    /**
     * Inserts a prerequisite that the specified rrset does not exist; that is,
     * there are no records with the given name and type in the zone.
     */
    public
    void absent(Name name, int type) {
        newPrereq(DnsRecord.newRecord(name, type, DnsClass.NONE, 0));
    }

    /**
     * Indicates that the records should be inserted into the zone.
     */
    public
    void add(DnsRecord[] records) {
        for (int i = 0; i < records.length; i++) {
            add(records[i]);
        }
    }

    /**
     * Indicates that the record should be inserted into the zone.
     */
    public
    void add(DnsRecord record) {
        newUpdate(record);
    }

    private
    void newUpdate(DnsRecord rec) {
        addRecord(rec, DnsSection.UPDATE);
    }

    /**
     * Indicates that all of the records in the rrset should be inserted into the
     * zone.
     */
    public
    void add(RRset rrset) {
        for (Iterator it = rrset.rrs(); it.hasNext(); ) {
            add((DnsRecord) it.next());
        }
    }

    /**
     * Indicates that all records with the given name should be deleted from
     * the zone.
     */
    public
    void delete(Name name) {
        newUpdate(DnsRecord.newRecord(name, DnsRecordType.ANY, DnsClass.ANY, 0));
    }

    /**
     * Parses a record from the string, and indicates that the record
     * should be deleted from the zone.
     *
     * @throws IOException The record could not be parsed.
     */
    public
    void delete(Name name, int type, String record) throws IOException {
        newUpdate(DnsRecord.fromString(name, type, DnsClass.NONE, 0, record, origin));
    }

    /**
     * Parses a record from the tokenizer, and indicates that the record
     * should be deleted from the zone.
     *
     * @throws IOException The record could not be parsed.
     */
    public
    void delete(Name name, int type, Tokenizer tokenizer) throws IOException {
        newUpdate(DnsRecord.fromString(name, type, DnsClass.NONE, 0, tokenizer, origin));
    }

    /**
     * Indicates that the records should be deleted from the zone.
     */
    public
    void delete(DnsRecord[] records) {
        for (int i = 0; i < records.length; i++) {
            delete(records[i]);
        }
    }

    /**
     * Indicates that the specified record should be deleted from the zone.
     */
    public
    void delete(DnsRecord record) {
        newUpdate(record.withDClass(DnsClass.NONE, 0));
    }

    /**
     * Indicates that all of the records in the rrset should be deleted from the
     * zone.
     */
    public
    void delete(RRset rrset) {
        for (Iterator it = rrset.rrs(); it.hasNext(); ) {
            delete((DnsRecord) it.next());
        }
    }

    /**
     * Parses a record from the string, and indicates that the record
     * should be inserted into the zone replacing any other records with the
     * same name and type.
     *
     * @throws IOException The record could not be parsed.
     */
    public
    void replace(Name name, int type, long ttl, String record) throws IOException {
        delete(name, type);
        add(name, type, ttl, record);
    }

    /**
     * Parses a record from the string, and indicates that the record
     * should be inserted into the zone.
     *
     * @throws IOException The record could not be parsed.
     */
    public
    void add(Name name, int type, long ttl, String record) throws IOException {
        newUpdate(DnsRecord.fromString(name, type, dclass, ttl, record, origin));
    }

    /**
     * Indicates that all records with the given name and type should be deleted
     * from the zone.
     */
    public
    void delete(Name name, int type) {
        newUpdate(DnsRecord.newRecord(name, type, DnsClass.ANY, 0));
    }

    /**
     * Parses a record from the tokenizer, and indicates that the record
     * should be inserted into the zone replacing any other records with the
     * same name and type.
     *
     * @throws IOException The record could not be parsed.
     */
    public
    void replace(Name name, int type, long ttl, Tokenizer tokenizer) throws IOException {
        delete(name, type);
        add(name, type, ttl, tokenizer);
    }

    /**
     * Parses a record from the tokenizer, and indicates that the record
     * should be inserted into the zone.
     *
     * @throws IOException The record could not be parsed.
     */
    public
    void add(Name name, int type, long ttl, Tokenizer tokenizer) throws IOException {
        newUpdate(DnsRecord.fromString(name, type, dclass, ttl, tokenizer, origin));
    }

    /**
     * Indicates that the records should be inserted into the zone replacing any
     * other records with the same name and type as each one.
     */
    public
    void replace(DnsRecord[] records) {
        for (int i = 0; i < records.length; i++) {
            replace(records[i]);
        }
    }

    /**
     * Indicates that the record should be inserted into the zone replacing any
     * other records with the same name and type.
     */
    public
    void replace(DnsRecord record) {
        delete(record.getName(), record.getType());
        add(record);
    }

    /**
     * Indicates that all of the records in the rrset should be inserted into the
     * zone replacing any other records with the same name and type.
     */
    public
    void replace(RRset rrset) {
        delete(rrset.getName(), rrset.getType());
        for (Iterator it = rrset.rrs(); it.hasNext(); ) {
            add((DnsRecord) it.next());
        }
    }

}
