// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dorkbox.network.dns.Compression;
import dorkbox.network.dns.DnsInput;
import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.*;
import dorkbox.network.dns.exceptions.WireParseException;
import dorkbox.util.OS;
import io.netty.buffer.ByteBuf;
import io.netty.util.*;

/**
 * A DNS DnsMessage.  A message is the basic unit of communication between
 * the client and server of a DNS operation.  A message consists of a Header
 * and 4 message sections.
 *
 * @author Brian Wellington
 * @see Header
 * @see DnsSection
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public
class DnsMessage extends AbstractReferenceCounted implements Cloneable, ReferenceCounted {

    private static final ResourceLeakDetector<DnsMessage> leakDetector = ResourceLeakDetectorFactory.instance().newResourceLeakDetector(DnsMessage.class);
    private final ResourceLeakTracker<DnsMessage> leak = leakDetector.track(this);

    /**
     * The maximum length of a message in wire format.
     */
    public static final int MAXLENGTH = 65535;

    private Header header;

    // To reduce the memory footprint of a message,
    // each of the following fields is a single record or a list of records.
    private Object questions;
    private Object answers;
    private Object authorities;
    private Object additionals;

    private int size;


    private TSIG tsigkey;
    private TSIGRecord querytsig;
    private int tsigerror;

    int tsigstart;
    int tsigState;
    int sig0start;

    /* The message was not signed */
    static final int TSIG_UNSIGNED = 0;

    /* The message was signed and verification succeeded */
    static final int TSIG_VERIFIED = 1;

    /* The message was an unsigned message in multiple-message response */
    static final int TSIG_INTERMEDIATE = 2;

    /* The message was signed and no verification was attempted.  */
    static final int TSIG_SIGNED = 3;

    /*
     * The message was signed and verification failed, or was not signed
     * when it should have been.
     */
    static final int TSIG_FAILED = 4;


    private static DnsRecord[] emptyRecordArray = new DnsRecord[0];
    private static RRset[] emptyRRsetArray = new RRset[0];

    /**
     * Creates a new DnsMessage with the specified DnsMessage ID
     */
    public
    DnsMessage(int id) {
        this(new Header(id));
    }

    private
    DnsMessage(Header header) {
        this.header = header;
    }

    /**
     * Creates a new DnsMessage with a random DnsMessage ID
     */
    public
    DnsMessage() {
        this(new Header());
    }

    /**
     * Creates a new DnsMessage with a random DnsMessage ID suitable for sending as a
     * query.
     *
     * @param r A record containing the question
     */
    public static
    DnsMessage newQuery(DnsRecord r) {
        DnsMessage m = new DnsMessage();
        m.header.setOpcode(DnsOpCode.QUERY);
        m.header.setFlag(Flags.RD);
        m.addRecord(r, DnsSection.QUESTION);
        return m;
    }

    /**
     * Creates a new DnsMessage to contain a dynamic update.  A random DnsMessage ID
     * and the zone are filled in.
     *
     * @param zone The zone to be updated
     */
    public static
    DnsMessage newUpdate(Name zone) {
        return new Update(zone);
    }








    /**
     * Creates a new DnsMessage from its DNS wire format representation
     *
     * @param b A byte array containing the DNS DnsMessage.
     */
    public
    DnsMessage(byte[] b) throws IOException {
        this(new DnsInput(b));
    }

    /**
     * Creates a new DnsMessage from its DNS wire format representation
     *
     * @param in A DnsInput containing the DNS DnsMessage.
     */
    public
    DnsMessage(DnsInput in) throws IOException {
        this(new Header(in));
        boolean isUpdate = (header.getOpcode() == DnsOpCode.UPDATE);
        boolean truncated = header.getFlag(Flags.TC);
        try {
            for (int i = 0; i < DnsSection.TOTAL_SECTION_COUNT; i++) {
                int count = header.getCount(i);
                List<DnsRecord> records;

                if (count > 0) {
                    records = newRecordList(count);
                    setSection(i, records);


                    for (int j = 0; j < count; j++) {
                        int pos = in.readIndex();
                        DnsRecord record = DnsRecord.fromWire(in, i, isUpdate);

                        records.add(record);

                        if (i == DnsSection.ADDITIONAL) {
                            if (record.getType() == DnsRecordType.TSIG) {
                                tsigstart = pos;
                            }
                            if (record.getType() == DnsRecordType.SIG) {
                                SIGRecord sig = (SIGRecord) record;
                                if (sig.getTypeCovered() == 0) {
                                    sig0start = pos;
                                }
                            }
                        }
                    }
                }
            }
        } catch (WireParseException e) {
            if (!truncated) {
                throw e;
            }
        }
        size = in.readIndex();
    }

    /**
     * Creates a new DnsMessage from its DNS wire format representation
     *
     * @param byteBuffer A ByteBuf containing the DNS DnsMessage.
     */
    public
    DnsMessage(ByteBuf byteBuffer) throws IOException {
        this(new DnsInput(byteBuffer));
    }

    @SuppressWarnings("unchecked")
    private static
    <T extends DnsRecord> T castRecord(Object record) {
        return (T) record;
    }

    private static
    ArrayList<DnsRecord> newRecordList(int count) {
        return new ArrayList<DnsRecord>(count);
    }

    private static
    ArrayList<DnsRecord> newRecordList() {
        return new ArrayList<DnsRecord>(2);
    }

    private
    Object sectionAt(int section) {
        switch (section) {
            case DnsSection.QUESTION:
                return questions;
            case DnsSection.ANSWER:
                return answers;
            case DnsSection.AUTHORITY:
                return authorities;
            case DnsSection.ADDITIONAL:
                return additionals;
        }

        throw new IndexOutOfBoundsException(); // Should never reach here.
    }

    private
    void setSection(int section, Object value) {
        switch (section) {
            case DnsSection.QUESTION:
                questions = value;
                return;
            case DnsSection.ANSWER:
                answers = value;
                return;
            case DnsSection.AUTHORITY:
                authorities = value;
                return;
            case DnsSection.ADDITIONAL:
                additionals = value;
                return;
        }

        throw new IndexOutOfBoundsException(); // Should never reach here.
    }


    /**
     * Retrieves the Header.
     *
     * @see Header
     */
    public
    Header getHeader() {
        return header;
    }

    /**
     * Replaces the Header with a new one.
     *
     * @see Header
     */
    public
    void setHeader(Header h) {
        header = h;
    }

    /**
     * Adds a record to a section of the DnsMessage, and adjusts the header.
     *
     * @see DnsRecord
     * @see DnsSection
     */
    public
    void addRecord(DnsRecord record, int section) {
        final Object records = sectionAt(section);
        header.incCount(section);

        if (records == null) {
            // it holds no records, so add a single record...
            setSection(section, record);
            return;
        }

        if (records instanceof DnsRecord) {
            // it holds a single record, so convert it to multiple records
            final List<DnsRecord> recordList = newRecordList();
            recordList.add(castRecord(records));
            recordList.add(record);
            setSection(section, recordList);
            return;
        }

        // holds a list of records
        @SuppressWarnings("unchecked")
        final List<DnsRecord> recordList = (List<DnsRecord>) records;
        recordList.add(record);
    }

    /**
     * Removes a record from a section of the DnsMessage, and adjusts the header.
     *
     * @see DnsRecord
     * @see DnsSection
     */
    public
    boolean removeRecord(DnsRecord record, int section) {
        final Object records = sectionAt(section);
        if (records == null) {
            // can't remove a record if there are none
            return false;
        }

        if (records instanceof DnsRecord) {
            setSection(section, null);
            header.decCount(section);
            return true;
        }

        @SuppressWarnings("unchecked")
        final List<DnsRecord> recordList = (List<DnsRecord>) records;
        boolean remove = recordList.remove(record);

        if (remove) {
            header.decCount(section);
            return true;
        }

        return false;
    }

    /**
     * Removes all records from a section of the DnsMessage, and adjusts the header.
     *
     * @see DnsRecord
     * @see DnsSection
     */
    public
    void removeAllRecords(int section) {
        setSection(section, null);
        header.setCount(section, 0);
    }

    /**
     * Determines if the given record is already present in the given section.
     *
     * @see DnsRecord
     * @see DnsSection
     */
    public
    boolean findRecord(DnsRecord record, int section) {
        final Object records = sectionAt(section);
        if (records == null) {
            return false;
        }

        if (records instanceof DnsRecord) {
            return records.equals(record);
        }

        @SuppressWarnings("unchecked")
        final List<DnsRecord> recordList = (List<DnsRecord>) records;
        return recordList.contains(record);
    }

    /**
     * Determines if the given record is already present in any section.
     *
     * @see DnsRecord
     * @see DnsSection
     */
    public
    boolean findRecord(DnsRecord record) {
        for (int i = DnsSection.ANSWER; i <= DnsSection.ADDITIONAL; i++) {
            if (findRecord(record, i)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Determines if an RRset with the given name and type is already
     * present in any section.
     *
     * @see RRset
     * @see DnsSection
     */
    public
    boolean findRRset(Name name, int type) {
        return (findRRset(name, type, DnsSection.ANSWER) || findRRset(name, type, DnsSection.AUTHORITY) || findRRset(name,
                                                                                                                     type,
                                                                                                                     DnsSection.ADDITIONAL));
    }

    /**
     * Determines if an RRset with the given name and type is already
     * present in the given section.
     *
     * @see RRset
     * @see DnsSection
     */
    public
    boolean findRRset(Name name, int type, int section) {
        final Object records = sectionAt(section);
        if (records == null) {
            return false;
        }


        if (records instanceof DnsRecord) {
            DnsRecord record = (DnsRecord) records;
            return record.getType() == type && name.equals(record.getName());
        }


        @SuppressWarnings("unchecked")
        final List<DnsRecord> recordList = (List<DnsRecord>) records;
        for (int i = 0; i < recordList.size(); i++) {
            final DnsRecord record = recordList.get(i);

            if (record.getType() == type && name.equals(record.getName())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns the first record in the QUESTION section.
     *
     * @see DnsRecord
     * @see DnsSection
     */
    public
    DnsRecord getQuestion() {
        final Object records = sectionAt(DnsSection.QUESTION);
        if (records == null) {
            return null;
        }

        if (records instanceof DnsRecord) {
            return (DnsRecord) records;
        }

        @SuppressWarnings("unchecked")
        final List<DnsRecord> recordList = (List<DnsRecord>) records;
        return recordList.get(0);
    }

    /**
     * Returns the TSIG record from the ADDITIONAL section, if one is present.
     *
     * @see TSIGRecord
     * @see TSIG
     * @see DnsSection
     */
    public
    TSIGRecord getTSIG() {
        final Object records = sectionAt(DnsSection.ADDITIONAL);
        if (records == null) {
            return null;
        }

        if (records instanceof DnsRecord) {
            DnsRecord record = (DnsRecord) records;
            if (record.type != DnsRecordType.TSIG) {
                return null;
            } else {
                return (TSIGRecord) record;
            }
        }

        @SuppressWarnings("unchecked")
        final List<DnsRecord> recordList = (List<DnsRecord>) records;

        DnsRecord record = recordList.get(recordList.size() - 1);
        if (record.type != DnsRecordType.TSIG) {
            return null;
        }
        else {
            return (TSIGRecord) record;
        }
    }

    /**
     * Returns an array containing all records in the given section grouped into
     * RRsets.
     *
     * @see RRset
     * @see DnsSection
     */
    public
    RRset[] getSectionRRsets(int section) {
        final Object records = sectionAt(section);
        if (records == null) {
            return emptyRRsetArray;
        }

        List<RRset> sets = new ArrayList<RRset>(header.getCount(section));
        Set<Name> hash = new HashSet<Name>();


        if (records instanceof DnsRecord) {
            DnsRecord record = (DnsRecord) records;

            // only 1, so no need to make it complicated
            return new RRset[] {new RRset(record)};
        }


        // now there are multiple records
        @SuppressWarnings("unchecked")
        final List<DnsRecord> recordList = (List<DnsRecord>) records;

        for (int i = 0; i < recordList.size(); i++) {
            final DnsRecord record = recordList.get(i);

            Name name = record.getName();
            boolean newset = true;

            if (hash.contains(name)) {
                for (int j = sets.size() - 1; j >= 0; j--) {
                    RRset set = sets.get(j);

                    if (set.getType() == record.getRRsetType() &&
                        set.getDClass() == record.getDClass() &&
                        set.getName().equals(name)) {

                        set.addRR(record);
                        newset = false;
                        break;
                    }
                }
            }

            if (newset) {
                RRset set = new RRset(record);
                sets.add(set);
                hash.add(name);
            }
        }

        return sets.toArray(new RRset[sets.size()]);
    }

    /**
     * Returns an array containing all records in the given section, or an
     * empty array if the section is empty.
     *
     * @see DnsRecord
     * @see DnsSection
     */
    public
    DnsRecord[] getSectionArray(int section) {
        final Object records = sectionAt(section);
        if (records == null) {
            return emptyRecordArray;
        }

        if (records instanceof DnsRecord) {
            DnsRecord record = (DnsRecord) records;

            // only 1, so no need to make it complicated
            return new DnsRecord[] {record};
        }

        @SuppressWarnings("unchecked")
        final List<DnsRecord> recordList = (List<DnsRecord>) records;
        return recordList.toArray(new DnsRecord[recordList.size()]);
    }

    /**
     * Returns an array containing the wire format representation of the DnsMessage.
     */
    public
    byte[] toWire() {
        DnsOutput out = new DnsOutput();
        toWire(out);
        size = out.current();
        return out.toByteArray();
    }

    public
    void toWire(DnsOutput out) {
        header.toWire(out);
        Compression c = new Compression();
        for (int i = 0; i < DnsSection.TOTAL_SECTION_COUNT; i++) {
            final Object records = sectionAt(i);
            if (records == null) {
                continue;
            }

            if (records instanceof DnsRecord) {
                DnsRecord record = (DnsRecord) records;
                record.toWire(out, i, c);
                continue;
            }

            @SuppressWarnings("unchecked")
            final List<DnsRecord> recordList = (List<DnsRecord>) records;
            for (int j = 0; j < recordList.size(); j++) {
                DnsRecord record = recordList.get(j);
                record.toWire(out, i, c);
            }
        }
    }

    /**
     * Returns an array containing the wire format representation of the DnsMessage
     * with the specified maximum length.  This will generate a truncated
     * message (with the TC bit) if the message doesn't fit, and will also
     * sign the message with the TSIG key set by a call to setTSIG().  This
     * method may return null if the message could not be rendered at all; this
     * could happen if maxLength is smaller than a DNS header, for example.
     *
     * @param maxLength The maximum length of the message.
     *
     * @return The wire format of the message, or null if the message could not be
     *         rendered into the specified length.
     *
     * @see Flags
     * @see TSIG
     */
    public
    byte[] toWire(int maxLength) {
        DnsOutput out = new DnsOutput();
        // this will also prep the output stream.
        boolean b = toWire(out, maxLength);
        if (!b) {
            System.err.println("ERROR CREATING MESSAGE FROM WIRE!");
        }
        size = out.current();

        // we output from the start.
        out.getByteBuf().readerIndex(0);
        return out.toByteArray();
    }

    /** Returns true if the message could be rendered. */
    private
    boolean toWire(DnsOutput out, int maxLength) {
        if (maxLength < Header.LENGTH) {
            return false;
        }

        Header newheader = null;

        int tempMaxLength = maxLength;
        if (tsigkey != null) {
            tempMaxLength -= tsigkey.recordLength();
        }

        OPTRecord opt = getOPT();
        byte[] optBytes = null;
        if (opt != null) {
            optBytes = opt.toWire(DnsSection.ADDITIONAL);
            tempMaxLength -= optBytes.length;
        }

        int startpos = out.current();
        header.toWire(out);

        Compression c = new Compression();
        int flags = header.getFlagsByte();
        int additionalCount = 0;

        for (int i = 0; i < DnsSection.TOTAL_SECTION_COUNT; i++) {
            int skipped;

            final Object records = sectionAt(i);
            if (records == null) {
                continue;
            }

            skipped = sectionToWire(out, i, c, tempMaxLength);
            if (skipped != 0 && i != DnsSection.ADDITIONAL) {
                flags = Header.setFlag(flags, Flags.TC, true);
                out.writeU16At(header.getCount(i) - skipped, startpos + 4 + 2 * i);
                for (int j = i + 1; j < DnsSection.ADDITIONAL; j++) {
                    out.writeU16At(0, startpos + 4 + 2 * j);
                }
                break;
            }
            if (i == DnsSection.ADDITIONAL) {
                additionalCount = header.getCount(i) - skipped;
            }
        }

        if (optBytes != null) {
            out.writeByteArray(optBytes);
            additionalCount++;
        }

        if (flags != header.getFlagsByte()) {
            out.writeU16At(flags, startpos + 2);
        }

        if (additionalCount != header.getCount(DnsSection.ADDITIONAL)) {
            out.writeU16At(additionalCount, startpos + 10);
        }

        if (tsigkey != null) {
            TSIGRecord tsigrec = tsigkey.generate(this, out.toByteArray(), tsigerror, querytsig);

            tsigrec.toWire(out, DnsSection.ADDITIONAL, c);
            // write size/position info
            out.writeU16At(additionalCount + 1, startpos + 10);
        }

        return true;
    }

    /**
     * Returns the OPT record from the ADDITIONAL section, if one is present.
     *
     * @see OPTRecord
     * @see DnsSection
     */
    public
    OPTRecord getOPT() {
        DnsRecord[] additional = getSectionArray(DnsSection.ADDITIONAL);
        for (int i = 0; i < additional.length; i++) {
            if (additional[i] instanceof OPTRecord) {
                return (OPTRecord) additional[i];
            }
        }
        return null;
    }

    /** Returns the number of records not successfully rendered. */
    private
    int sectionToWire(DnsOutput out, int section, Compression c, int maxLength) {
        final Object records = sectionAt(section);
        // will never be null, we check earlier

        int pos = out.current();
        int rendered = 0;
        int skipped = 0;
        DnsRecord lastRecord = null;



        if (records instanceof DnsRecord) {
            DnsRecord record = (DnsRecord) records;

            if (section == DnsSection.ADDITIONAL && record.type == DnsRecordType.OPT) {
                skipped++;
                return skipped;
            }

            record.toWire(out, section, c);

            if (out.current() > maxLength) {
                out.jump(pos);
                return 1 - rendered + skipped;
            }

            return skipped;
        }

        @SuppressWarnings("unchecked")
        final List<DnsRecord> recordList = (List<DnsRecord>) records;
        int n = recordList.size();

        for (int i = 0; i < n; i++) {
            DnsRecord record = recordList.get(i);
            if (section == DnsSection.ADDITIONAL && record.type == DnsRecordType.OPT) {
                skipped++;
                continue;
            }

            if (lastRecord != null && !sameSet(record, lastRecord)) {
                pos = out.current();
                rendered = i;
            }

            lastRecord = record;
            record.toWire(out, section, c);

            if (out.current() > maxLength) {
                out.jump(pos);
                return n - rendered + skipped;
            }
        }
        return skipped;
    }

    private static
    boolean sameSet(DnsRecord r1, DnsRecord r2) {
        return (r1.getRRsetType() == r2.getRRsetType() && r1.getDClass() == r2.getDClass() && r1.getName()
                                                                                                .equals(r2.getName()));
    }

    /**
     * Sets the TSIG key and other necessary information to sign a message.
     *
     * @param key The TSIG key.
     * @param error The value of the TSIG error field.
     * @param querytsig If this is a response, the TSIG from the request.
     */
    public
    void setTSIG(TSIG key, int error, TSIGRecord querytsig) {
        this.tsigkey = key;
        this.tsigerror = error;
        this.querytsig = querytsig;
    }

    /**
     * Creates a SHALLOW copy of this DnsMessage.  This is done by the Resolver before adding
     * TSIG and OPT records, for example.
     *
     * @see TSIGRecord
     * @see OPTRecord
     */
    @Override
    public
    Object clone() {
        DnsMessage m = new DnsMessage();

        for (int i = 0; i < DnsSection.TOTAL_SECTION_COUNT; i++) {
            final Object records = sectionAt(i);
            if (records == null) {
                continue;
            }

            if (records instanceof DnsRecord) {
                setSection(i, records);
                continue;
            }

            @SuppressWarnings("unchecked")
            final List<DnsRecord> recordList = (List<DnsRecord>) records;
            setSection(i, new ArrayList<DnsRecord>(recordList));
        }

        m.header = (Header) header.clone();
        m.size = size;
        return m;
    }

    /**
     * Converts the DnsMessage to a String.
     */
    @Override
    public
    String toString() {
        String NL = OS.LINE_SEPARATOR;

        StringBuilder sb = new StringBuilder(NL);
        OPTRecord opt = getOPT();

        if (opt != null) {
            sb.append(header.toStringWithRcode(getRcode()))
              .append(NL);
        }
        else {
            sb.append(header)
              .append(NL);
        }

        if (isSigned()) {
            sb.append(";; TSIG ");
            if (isVerified()) {
                sb.append("ok");
            }
            else {
                sb.append("invalid");
            }
            sb.append(NL);
        }

        for (int i = 0; i < 4; i++) {
            if (header.getOpcode() != DnsOpCode.UPDATE) {
                sb.append(";; ")
                  .append(DnsSection.longString(i))
                  .append(":")
                  .append(NL);
            }
            else {
                sb.append(";; ")
                  .append(DnsSection.updString(i))
                  .append(":")
                  .append(NL);
            }
            sb.append(sectionToString(i))
              .append(NL);
        }

        sb.append(";; DnsMessage size: ")
          .append(numBytes())
          .append(" bytes");
        return sb.toString();
    }

    /**
     * Was this message signed by a TSIG?
     *
     * @see TSIG
     */
    public
    boolean isSigned() {
        return (tsigState == TSIG_SIGNED || tsigState == TSIG_VERIFIED || tsigState == TSIG_FAILED);
    }

    /**
     * If this message was signed by a TSIG, was the TSIG verified?
     *
     * @see TSIG
     */
    public
    boolean isVerified() {
        return (tsigState == TSIG_VERIFIED);
    }

    /**
     * Returns the message's rcode (error code).  This incorporates the EDNS
     * extended rcode.
     */
    public
    int getRcode() {
        int rcode = header.getRcode();
        OPTRecord opt = getOPT();
        if (opt != null) {
            rcode += (opt.getExtendedRcode() << 4);
        }
        return rcode;
    }

    /**
     * Returns the size of the message.  Only valid if the message has been converted to or from wire format.
     */
    public
    int numBytes() {
        return size;
    }

    /**
     * Converts the given section of the DnsMessage to a String.
     *
     * @see DnsSection
     */
    public
    String sectionToString(int i) {
        if (i > 3) {
            return null;
        }

        StringBuilder sb = new StringBuilder();

        DnsRecord[] records = getSectionArray(i);
        for (int j = 0; j < records.length; j++) {
            DnsRecord rec = records[j];
            if (i == DnsSection.QUESTION) {
                sb.append(";;\t")
                  .append(rec.name);
                sb.append(", type = ")
                  .append(DnsRecordType.string(rec.type));
                sb.append(", class = ")
                  .append(DnsClass.string(rec.dclass));
            }
            else {
                sb.append(rec);
            }

            sb.append(OS.LINE_SEPARATOR);
        }
        return sb.toString();
    }

    /**
     * Removes all the records in this DNS message.
     */
    @SuppressWarnings("unchecked")
    public
    DnsMessage clear() {
        for (int i = 0; i < DnsSection.TOTAL_SECTION_COUNT; i++) {
            removeAllRecords(i);
        }
        return this;
    }

    @Override
    protected
    void deallocate() {
        clear();

        final ResourceLeakTracker<DnsMessage> leak = this.leak;
        if (leak != null) {
            boolean closed = leak.close(this);
            assert closed;
        }
    }

    @Override
    public
    DnsMessage touch(Object hint) {
        if (leak != null) {
            leak.record(hint);
        }
        return this;
    }
}
