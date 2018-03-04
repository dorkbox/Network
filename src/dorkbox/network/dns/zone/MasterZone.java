/*
 * Copyright 2018 dorkbox, llc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package dorkbox.network.dns.zone;

import java.util.HashSet;
import java.util.NavigableSet;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;

import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsRecordType;
import dorkbox.network.dns.constants.DnsResponseCode;
import dorkbox.network.dns.records.DnsRecord;
import dorkbox.network.dns.records.SOARecord;
import dorkbox.network.dns.server.CNAMEResponse;
import dorkbox.network.dns.server.DNAMEResponse;
import dorkbox.network.dns.server.NoErrorResponse;
import dorkbox.network.dns.server.NotFoundResponse;
import dorkbox.network.dns.server.ReferralResponse;
import dorkbox.network.dns.server.Response;

public
class MasterZone extends AbstractZone {

    final ConcurrentMap<Name, ConcurrentMap<Integer, NavigableSet<DnsRecord>>> records = new ConcurrentSkipListMap<Name, ConcurrentMap<Integer, NavigableSet<DnsRecord>>>();

    final Response nxDomain;
    final Response nxRRSet;

    public
    MasterZone(Name name, SOARecord soaRecord) {
        super(ZoneType.master, name);

        this.nxDomain = new NotFoundResponse(DnsResponseCode.NXDOMAIN, soaRecord);
        this.nxRRSet = new NotFoundResponse(DnsResponseCode.NXRRSET, soaRecord);
    }

    // add and remove needs queuing?
    // if modify operations works on single thread, not conflict.
    public synchronized
    void add(DnsRecord rr) {

        for (; ; ) {
            ConcurrentMap<Integer, NavigableSet<DnsRecord>> current = this.records.get(rr.getName());
            if (current == null) {
                ConcurrentMap<Integer, NavigableSet<DnsRecord>> newone = new ConcurrentSkipListMap<Integer, NavigableSet<DnsRecord>>();
                NavigableSet<DnsRecord> newset = new ConcurrentSkipListSet<DnsRecord>();
                newset.add(rr);
                newone.put(rr.getType(), newset);

                ConcurrentMap<Integer, NavigableSet<DnsRecord>> prevTypes = this.records.putIfAbsent(rr.getName(), newone);
                if (prevTypes == null) {
                    break;
                }
                synchronized (prevTypes) {
                    Set<DnsRecord> prevRecs = prevTypes.putIfAbsent(rr.getType(), newset);
                    if (prevRecs == null) {
                        break;
                    }
                    prevRecs.add(rr);
                    break;
                }
            }
            else {
                synchronized (current) {
                    Set<DnsRecord> rrs = current.get(rr.getType());
                    if (rrs == null) {
                        NavigableSet<DnsRecord> newset = new ConcurrentSkipListSet<DnsRecord>();
                        newset.add(rr);
                        current.put(rr.getType(), newset);
                        break;
                    }
                    if (!rrs.isEmpty()) {
                        rrs.add(rr);
                        break;
                    }
                }
            }
        }
    }

    @Override
    public
    Response find(Name queryName, int recordType) {
        if (!queryName.equals(this.name)) {
            return this.nxDomain;
        }

        ConcurrentMap<Integer, NavigableSet<DnsRecord>> exactMatch = this.records.get(queryName);

        if (exactMatch != null) {
            NavigableSet<DnsRecord> rrs = exactMatch.get(recordType);

            if (rrs != null) {
                synchronized (rrs) {
                    if (rrs.isEmpty()) {
                        return new NoErrorResponse(rrs);
                    }
                }
            }

            if (DnsRecordType.ANY == recordType) {
                Set<DnsRecord> newset = new HashSet<DnsRecord>();
                for (Integer type : exactMatch.keySet()) {
                    Set<DnsRecord> s = exactMatch.get(type);
                    if (s != null) {
                        synchronized (s) {
                            newset.addAll(s);
                        }
                    }
                }

                if (newset.isEmpty()) {
                    return null;
                }
            }

            if (DnsRecordType.CNAME == recordType) {
                rrs = exactMatch.get(DnsRecordType.CNAME);

                if (rrs != null) {
                    synchronized (rrs) {
                        if (!rrs.isEmpty()) {
                            return new CNAMEResponse(rrs.first(), recordType);
                        }
                    }
                }
            }

            return this.nxRRSet;
        }

        for (Name qn = queryName.parent(1); !this.name()
                                                 .equals(qn); qn = qn.parent(1)) {
            ConcurrentMap<Integer, NavigableSet<DnsRecord>> match = this.records.get(qn);

            if (match != null) {
                synchronized (match) {
                    if (!match.isEmpty()) {
                        NavigableSet<DnsRecord> set = match.get(DnsRecordType.NS);
                        if ((set != null) && (!set.isEmpty())) {
                            return new ReferralResponse(set);
                        }

                        set = match.get(DnsRecordType.DNAME);
                        if ((set != null) && (!set.isEmpty())) {
                            return new DNAMEResponse(set.first(), queryName, recordType);
                        }
                    }
                }
            }
        }

        for (Name qn = queryName; !this.name()
                                       .equals(qn); qn = qn.parent(1)) {
            Name wild = qn.wild(1);

            ConcurrentMap<Integer, NavigableSet<DnsRecord>> match = this.records.get(wild);
            if (match != null) {
                synchronized (match) {
                    if (!match.isEmpty()) {
                        Set<DnsRecord> matchSet = match.get(recordType);

                        if (!matchSet.isEmpty()) {
                            Set<DnsRecord> set = new HashSet<DnsRecord>(matchSet.size());
                            for (DnsRecord rr : matchSet) {
                                set.add(DnsRecord.newRecord(queryName, rr.getType(), rr.getDClass(), rr.getTTL()));
                            }

                            return new NoErrorResponse(set);
                        }
                    }
                }
            }
        }

        return this.nxDomain;
    }

    public synchronized
    void remove(DnsRecord rr, boolean checkSets, boolean checkMap) {
        ConcurrentMap<Integer, NavigableSet<DnsRecord>> current = this.records.get(rr.getName());
        if (current != null) {
            synchronized (current) {
                NavigableSet<DnsRecord> sets = current.get(rr.getType());
                sets.remove(rr);
                if (checkSets && sets.isEmpty()) {
                    current.remove(rr.getType());
                    if (checkMap && current.isEmpty()) {
                        this.records.remove(rr.getName());
                    }
                }
            }
        }
    }
}
