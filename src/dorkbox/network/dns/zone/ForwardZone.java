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

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import dorkbox.network.dns.Name;
import dorkbox.network.dns.server.Response;

public class ForwardZone extends AbstractZone {

	protected List<InetAddress> forwarders = new ArrayList<InetAddress>();

	public ForwardZone(Name name) {
		super(ZoneType.forward, name);
	}

	public ForwardZone(int dnsclass, Name name) {
		super(ZoneType.forward, dnsclass, name);
	}

	public void addForwardHost(InetAddress host) {
		this.forwarders.add(host);
	}

	@Override
	public
    Response find(Name qname, int recordType) {
		// TODO Auto-generated method stub
		return null;
	}
}
