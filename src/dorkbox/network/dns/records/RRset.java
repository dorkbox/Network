// Copyright (c) 1999-2004 Brian Wellington (bwelling@xbill.org)

package dorkbox.network.dns.records;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsClass;
import dorkbox.network.dns.constants.DnsRecordType;

/**
 * A set of Records with the same name, type, and class.  Also included
 * are all RRSIG records signing the data records.
 *
 * @author Brian Wellington
 * @see DnsRecord
 * @see RRSIGRecord
 */

public
class RRset implements Serializable {

    private static final long serialVersionUID = -3270249290171239695L;

    /*
     * rrs contains both normal and RRSIG records, with the RRSIG records
     * at the end.
     */
    private List resourceRecords;
    private short nsigs;
    private short position;

    /**
     * Creates an RRset and sets its contents to the specified record
     */
    public
    RRset(DnsRecord record) {
        this();
        safeAddRR(record);
    }

    /**
     * Creates an empty RRset
     */
    public
    RRset() {
        resourceRecords = new ArrayList(1);
        nsigs = 0;
        position = 0;
    }

    private
    void safeAddRR(DnsRecord r) {
        if (!(r instanceof RRSIGRecord)) {
            if (nsigs == 0) {
                resourceRecords.add(r);
            }
            else {
                resourceRecords.add(resourceRecords.size() - nsigs, r);
            }
        }
        else {
            resourceRecords.add(r);
            nsigs++;
        }
    }

    /**
     * Creates an RRset with the contents of an existing RRset
     */
    public
    RRset(RRset rrset) {
        synchronized (rrset) {
            resourceRecords = (List) ((ArrayList) rrset.resourceRecords).clone();
            nsigs = rrset.nsigs;
            position = rrset.position;
        }
    }

    /**
     * Adds a Record to an RRset
     */
    public synchronized
    void addRR(DnsRecord r) {
        if (resourceRecords.size() == 0) {
            safeAddRR(r);
            return;
        }
        DnsRecord first = first();
        if (!r.sameRRset(first)) {
            throw new IllegalArgumentException("record does not match " + "rrset");
        }

        if (r.getTTL() != first.getTTL()) {
            if (r.getTTL() > first.getTTL()) {
                r = r.cloneRecord();
                r.setTTL(first.getTTL());
            }
            else {
                for (int i = 0; i < resourceRecords.size(); i++) {
                    DnsRecord tmp = (DnsRecord) resourceRecords.get(i);
                    tmp = tmp.cloneRecord();
                    tmp.setTTL(r.getTTL());
                    resourceRecords.set(i, tmp);
                }
            }
        }

        if (!resourceRecords.contains(r)) {
            safeAddRR(r);
        }
    }

    /**
     * Returns the first record
     *
     * @throws IllegalStateException if the rrset is empty
     */
    public synchronized
    DnsRecord first() {
        if (resourceRecords.size() == 0) {
            throw new IllegalStateException("rrset is empty");
        }
        return (DnsRecord) resourceRecords.get(0);
    }

    /**
     * Deletes a Record from an RRset
     */
    public synchronized
    void deleteRR(DnsRecord r) {
        if (resourceRecords.remove(r) && (r instanceof RRSIGRecord)) {
            nsigs--;
        }
    }

    /**
     * Deletes all Records from an RRset
     */
    public synchronized
    void clear() {
        resourceRecords.clear();
        position = 0;
        nsigs = 0;
    }

    /**
     * Returns an Iterator listing all (data) records.
     *
     * @param cycle If true, cycle through the records so that each Iterator will
     *         start with a different record.
     */
    public synchronized
    Iterator rrs(boolean cycle) {
        return iterator(true, cycle);
    }

    private synchronized
    Iterator iterator(boolean data, boolean cycle) {
        int size, start, total;

        total = resourceRecords.size();

        if (data) {
            size = total - nsigs;
        }
        else {
            size = nsigs;
        }
        if (size == 0) {
            return Collections.EMPTY_LIST.iterator();
        }

        if (data) {
            if (!cycle) {
                start = 0;
            }
            else {
                if (position >= size) {
                    position = 0;
                }
                start = position++;
            }
        }
        else {
            start = total - nsigs;
        }

        List list = new ArrayList(size);
        if (data) {
            list.addAll(resourceRecords.subList(start, size));
            if (start != 0) {
                list.addAll(resourceRecords.subList(0, start));
            }
        }
        else {
            list.addAll(resourceRecords.subList(start, total));
        }

        return list.iterator();
    }

    /**
     * Returns an Iterator listing all (data) records.  This cycles through
     * the records, so each Iterator will start with a different record.
     */
    public synchronized
    Iterator rrs() {
        return iterator(true, true);
    }

    /**
     * Returns an Iterator listing all signature records
     */
    public synchronized
    Iterator sigs() {
        return iterator(false, false);
    }

    /**
     * Returns the number of (data) records
     */
    public synchronized
    int size() {
        return resourceRecords.size() - nsigs;
    }

    /**
     * Converts the RRset to a String
     */
    @Override
    public
    String toString() {
        if (resourceRecords.size() == 0) {
            return ("{empty}");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{ ");
        sb.append(getName() + " ");
        sb.append(getTTL() + " ");
        sb.append(DnsClass.string(getDClass()) + " ");
        sb.append(DnsRecordType.string(getType()) + " ");
        sb.append(iteratorToString(iterator(true, false)));
        if (nsigs > 0) {
            sb.append(" sigs: ");
            sb.append(iteratorToString(iterator(false, false)));
        }
        sb.append(" }");
        return sb.toString();
    }

    /**
     * Returns the name of the records
     *
     * @see Name
     */
    public
    Name getName() {
        return first().getName();
    }

    /**
     * Returns the type of the records
     *
     * @see DnsRecordType
     */
    public
    int getType() {
        return first().getRRsetType();
    }

    /**
     * Returns the class of the records
     *
     * @see DnsClass
     */
    public
    int getDClass() {
        return first().getDClass();
    }

    /**
     * Returns the ttl of the records
     */
    public synchronized
    long getTTL() {
        return first().getTTL();
    }

    private
    String iteratorToString(Iterator it) {
        StringBuilder sb = new StringBuilder();
        while (it.hasNext()) {
            DnsRecord rr = (DnsRecord) it.next();
            sb.append("[");
            rr.rdataToString(sb);
            sb.append("]");
            if (it.hasNext()) {
                sb.append(" ");
            }
        }
        return sb.toString();
    }

}
