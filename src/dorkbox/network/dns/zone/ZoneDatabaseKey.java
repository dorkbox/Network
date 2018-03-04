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

import dorkbox.network.dns.Name;
import dorkbox.network.dns.records.DnsRecord;

/**
 *
 */
class ZoneDatabaseKey implements Comparable<ZoneDatabaseKey> {
    Name name;
    int dnsclass;

    public
    ZoneDatabaseKey(Zone z) {
        this(z.name(), z.dnsClass());
    }

    public
    ZoneDatabaseKey(DnsRecord rr) {
        this(rr.getName(), rr.getDClass());
    }

    public
    ZoneDatabaseKey(Name name, int dnsclass) {
        this.name = name;
        this.dnsclass = dnsclass;
    }

    @Override
    public
    int compareTo(ZoneDatabaseKey o) {
        if (o == null) {
            return 1;
        }
        if (equals(o)) {
            return 0;
        }
        return this.hashCode() - o.hashCode();
    }

    public
    boolean equals(ZoneDatabaseKey other) {
        return (this.dnsclass == other.dnsclass) && this.name.equals(other.name);
    }

    @Override
    public
    boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null) {
            return false;
        }
        if (getClass() != other.getClass()) {
            return false;
        }
        return equals((ZoneDatabaseKey) other);
    }

    @Override
    public
    int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + this.dnsclass;
        result = prime * result + this.name.hashCode();
        return result;
    }

    public
    Name name() {
        return this.name;
    }

    public
    void name(Name name) {
        this.name = name;
    }
}
