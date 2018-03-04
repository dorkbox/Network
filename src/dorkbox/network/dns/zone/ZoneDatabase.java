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

import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

import dorkbox.network.dns.Name;

public
class ZoneDatabase {

    protected Map<ZoneDatabaseKey, Zone> zones = new ConcurrentSkipListMap<ZoneDatabaseKey, Zone>();

    public
    void add(Zone zone/* TODO ZoneConfig? */) {
        this.zones.put(new ZoneDatabaseKey(zone), zone);
    }

    public
    Query prepare(Name name, int dnsClass) {
        ZoneDatabaseKey zk = new ZoneDatabaseKey(name, dnsClass);
        Zone found = this.zones.get(zk);
        if (found != null) {
            // exact match
            return new Query(name, name, dnsClass, found, this);
        }

        Name child = name;
        // partial match
        for (int i = 0, size = this.zones.size(); i < size; i++) {
            Name p = child.parent(1);
            zk.name(p);
            found = this.zones.get(zk);
            if (found == null) {
                if (p.labels() <= 1) {
                    break;
                }

                child = p;
            }
            else {
                return new Query(name, p, dnsClass, found, this);
            }
        }

        // not found.
        return null;
    }
}
