// -*- Java -*-
//
// Copyright (c) 2005, Matthew J. Rutherford <rutherfo@cs.colorado.edu>
// Copyright (c) 2005, University of Colorado at Boulder
// All rights reserved.
// 
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are
// met:
// 
// * Redistributions of source code must retain the above copyright
//   notice, this list of conditions and the following disclaimer.
// 
// * Redistributions in binary form must reproduce the above copyright
//   notice, this list of conditions and the following disclaimer in the
//   documentation and/or other materials provided with the distribution.
// 
// * Neither the name of the University of Colorado at Boulder nor the
//   names of its contributors may be used to endorse or promote
//   products derived from this software without specific prior written
//   permission.
// 
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
// OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
// SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
// LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
// DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
// THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
//
package dorkbox.network.dns.records;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsClass;
import dorkbox.network.dns.constants.DnsOpCode;
import dorkbox.network.dns.constants.DnsSection;
import dorkbox.network.dns.constants.Flags;
import dorkbox.network.dns.exceptions.TextParseException;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

public
class MessageTest {
    public static
    class Test_init extends TestCase {
        public
        void test_0arg() {
            DnsMessage m = new DnsMessage();
            assertTrue(Arrays.equals(new DnsRecord[0], m.getSectionArray(0)));
            assertTrue(Arrays.equals(new DnsRecord[0], m.getSectionArray(1)));
            assertTrue(Arrays.equals(new DnsRecord[0], m.getSectionArray(2)));
            assertTrue(Arrays.equals(new DnsRecord[0], m.getSectionArray(3)));
            try {
                m.getSectionArray(4);
                fail("IndexOutOfBoundsException not thrown");
            } catch (IndexOutOfBoundsException ignored) {
            }
            Header h = m.getHeader();
            assertEquals(0, h.getCount(0));
            assertEquals(0, h.getCount(1));
            assertEquals(0, h.getCount(2));
            assertEquals(0, h.getCount(3));
        }

        public
        void test_1arg() {
            DnsMessage m = new DnsMessage(10);
            assertEquals(new Header(10).toString(),
                         m.getHeader()
                          .toString());
            assertTrue(Arrays.equals(new DnsRecord[0], m.getSectionArray(0)));
            assertTrue(Arrays.equals(new DnsRecord[0], m.getSectionArray(1)));
            assertTrue(Arrays.equals(new DnsRecord[0], m.getSectionArray(2)));
            assertTrue(Arrays.equals(new DnsRecord[0], m.getSectionArray(3)));
            try {
                m.getSectionArray(4);
                fail("IndexOutOfBoundsException not thrown");
            } catch (IndexOutOfBoundsException ignored) {
            }
            Header h = m.getHeader();
            assertEquals(0, h.getCount(0));
            assertEquals(0, h.getCount(1));
            assertEquals(0, h.getCount(2));
            assertEquals(0, h.getCount(3));
        }

        public
        void test_newQuery() throws TextParseException, UnknownHostException {
            Name n = Name.fromString("The.Name.");
            ARecord ar = new ARecord(n, DnsClass.IN, 1, InetAddress.getByName("192.168.101.110"));

            DnsMessage m = DnsMessage.newQuery(ar);
            assertTrue(Arrays.equals(new DnsRecord[] {ar}, m.getSectionArray(DnsSection.QUESTION)));
            assertTrue(Arrays.equals(new DnsRecord[0], m.getSectionArray(DnsSection.ANSWER)));
            assertTrue(Arrays.equals(new DnsRecord[0], m.getSectionArray(DnsSection.AUTHORITY)));
            assertTrue(Arrays.equals(new DnsRecord[0], m.getSectionArray(DnsSection.ADDITIONAL)));

            Header h = m.getHeader();
            assertEquals(1, h.getCount(DnsSection.QUESTION));
            assertEquals(0, h.getCount(DnsSection.ANSWER));
            assertEquals(0, h.getCount(DnsSection.AUTHORITY));
            assertEquals(0, h.getCount(DnsSection.ADDITIONAL));
            assertEquals(DnsOpCode.QUERY, h.getOpcode());
            assertEquals(true, h.getFlag(Flags.RD));
        }

    }

    public static
    Test suite() {
        TestSuite s = new TestSuite();
        s.addTestSuite(Test_init.class);
        return s;
    }
}
