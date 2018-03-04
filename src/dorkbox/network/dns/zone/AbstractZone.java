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
import dorkbox.network.dns.constants.DnsClass;

public abstract class AbstractZone implements Zone {

	protected ZoneType type;

	protected int dnsClass;

	protected Name name;

	public AbstractZone(ZoneType type, Name name) {
		this(type, DnsClass.IN, name);
	}

	public AbstractZone(ZoneType type, int dnsClass, Name name) {
		this.type = type;
		this.dnsClass = dnsClass;
		this.name = name;
	}

	@Override
	public ZoneType type() {
		return this.type;
	}

	@Override
	public int dnsClass() {
		return this.dnsClass;
	}

	@Override
	public Name name() {
		return this.name;
	}

}
