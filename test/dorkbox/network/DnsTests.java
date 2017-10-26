/*
 * Copyright 2014 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.network;

import static org.junit.Assert.fail;

import java.net.UnknownHostException;
import java.util.List;

import org.junit.Test;

import dorkbox.network.dns.record.MailExchangerRecord;
import dorkbox.network.dns.record.ServiceRecord;
import dorkbox.network.dns.record.StartOfAuthorityRecord;

public class DnsTests {

    @Test
    public
    void decode_A_Record() throws UnknownHostException {
        //DnsClient dnsClient = new DnsClient("127.0.1.1");
        DnsClient dnsClient = new DnsClient();
        String answer = dnsClient.resolveA("google.com");
        dnsClient.stop();

        if (answer == null) {
            fail("Error fetching answer for DNS");
        }
    }

    @Test
    public void decode_PTR_Record() {
        // PTR absolutely MUST end in '.in-addr.arpa' in order for the DNS server to understand it.
        // our DNS client will FIX THIS, so that end-users do NOT have to know this!

        DnsClient dnsClient = new DnsClient();
        String answer = dnsClient.resolvePTR("204.228.150.3");
        dnsClient.stop();

        if (answer == null) {
            fail("Error fetching answer for DNS");
        }
    }

    @Test
    public void decode_CNAME_Record() {
        DnsClient dnsClient = new DnsClient();
        String answer = dnsClient.resolveCNAME("www.atmos.org");
        dnsClient.stop();

        if (answer == null) {
            fail("Error fetching answer for DNS");
        }
    }

    @Test
    public void decode_MX_Record() {
        DnsClient dnsClient = new DnsClient();
        MailExchangerRecord answer = dnsClient.resolveMX("bbc.co.uk");
        final String name = answer.name();
        dnsClient.stop();

        if (name == null || name.isEmpty()) {
            fail("Error fetching answer for DNS");
        }
    }

    @Test
    public void decode_SRV_Record() {
        DnsClient dnsClient = new DnsClient();
        ServiceRecord answer = dnsClient.resolveSRV("_pop3._tcp.fudo.org");
        final String name = answer.name();
        dnsClient.stop();

        if (name == null || name.isEmpty()) {
            fail("Error fetching answer for DNS");
        }
    }

    @Test
    public void decode_SOA_Record() {
        DnsClient dnsClient = new DnsClient();
        StartOfAuthorityRecord answer = dnsClient.resolveSOA("google.com");
        final String nameServer = answer.primaryNameServer();
        dnsClient.stop();

        if (nameServer == null || nameServer.isEmpty()) {
            fail("Error fetching answer for DNS");
        }
    }


    @Test
    public void decode_TXT_Record() {
        DnsClient dnsClient = new DnsClient();
        List<String> answer = dnsClient.resolveTXT("real-world-systems.com");
        final String name = answer.get(0);
        dnsClient.stop();

        if (name == null || name.isEmpty()) {
            fail("Error fetching answer for DNS");
        }
    }
}
