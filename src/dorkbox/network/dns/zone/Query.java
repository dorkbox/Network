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


import java.util.List;

import dorkbox.network.dns.Name;
import dorkbox.network.dns.records.DnsRecord;

/**
 * @author taichi
 */
public
class Query {

    protected Name origin;
    protected Name current;

    protected int dnsClass;

    protected Zone target;

    protected ZoneDatabase database;

    public
    Query(Name origin, Name current, int dnsClass, Zone target, ZoneDatabase database) {
        super();

        this.origin = origin;
        this.current = current;
        this.dnsClass = dnsClass;
        this.target = target;
        this.database = database;
    }

    protected
    boolean contains(List<DnsRecord> rrs) {
        for (DnsRecord rr : rrs) {
            if (this.origin.equals(rr.getName())) {
                return true;
            }
        }
        return false;
    }
}
