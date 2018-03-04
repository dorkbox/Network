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
package dorkbox.network.dns.server;

import java.util.Set;

import dorkbox.network.dns.constants.DnsResponseCode;
import dorkbox.network.dns.constants.DnsSection;
import dorkbox.network.dns.records.DnsMessage;
import dorkbox.network.dns.records.DnsRecord;

public class ReferralResponse extends DefaultResponse {
	final Set<DnsRecord> nsRecords;

	public ReferralResponse(Set<DnsRecord> records) {
		super(DnsResponseCode.NOERROR);
		this.nsRecords = records;
	}

	@Override
	public void postProcess(DnsMessage message) {
	    message.getHeader().setRcode(this.responseCode());

        for (DnsRecord nsRecord : nsRecords) {
            message.addRecord(nsRecord, DnsSection.AUTHORITY);
        }
	}
}
