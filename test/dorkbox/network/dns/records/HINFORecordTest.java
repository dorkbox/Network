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

import java.io.IOException;
import java.util.Arrays;

import dorkbox.network.dns.DnsInput;
import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsClass;
import dorkbox.network.dns.constants.DnsRecordType;
import dorkbox.network.dns.exceptions.TextParseException;
import dorkbox.network.dns.utils.Tokenizer;
import junit.framework.TestCase;

public
class HINFORecordTest extends TestCase {
    public
    void test_ctor_0arg() {
        HINFORecord dr = new HINFORecord();
        assertNull(dr.getName());
        assertEquals(0, dr.getType());
        assertEquals(0, dr.getDClass());
        assertEquals(0, dr.getTTL());
    }

    public
    void test_getObject() {
        HINFORecord dr = new HINFORecord();
        DnsRecord r = dr.getObject();
        assertTrue(r instanceof HINFORecord);
    }

    public
    void test_ctor_5arg() throws TextParseException {
        Name n = Name.fromString("The.Name.");
        long ttl = 0xABCDL;
        String cpu = "i686 Intel(R) Pentium(R) M processor 1.70GHz GenuineIntel GNU/Linux";
        String os = "Linux troy 2.6.10-gentoo-r6 #8 Wed Apr 6 21:25:04 MDT 2005";

        HINFORecord dr = new HINFORecord(n, DnsClass.IN, ttl, cpu, os);
        assertEquals(n, dr.getName());
        assertEquals(DnsClass.IN, dr.getDClass());
        assertEquals(DnsRecordType.HINFO, dr.getType());
        assertEquals(ttl, dr.getTTL());
        assertEquals(cpu, dr.getCPU());
        assertEquals(os, dr.getOS());
    }

    public
    void test_ctor_5arg_invalid_CPU() throws TextParseException {
        Name n = Name.fromString("The.Name.");
        long ttl = 0xABCDL;
        String cpu = "i686 Intel(R) Pentium(R) M \\256 processor 1.70GHz GenuineIntel GNU/Linux";
        String os = "Linux troy 2.6.10-gentoo-r6 #8 Wed Apr 6 21:25:04 MDT 2005";

        try {
            new HINFORecord(n, DnsClass.IN, ttl, cpu, os);
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException e) {
        }
    }

    public
    void test_ctor_5arg_invalid_OS() throws TextParseException {
        Name n = Name.fromString("The.Name.");
        long ttl = 0xABCDL;
        String cpu = "i686 Intel(R) Pentium(R) M processor 1.70GHz GenuineIntel GNU/Linux";
        String os = "Linux troy 2.6.10-gentoo-r6 \\1 #8 Wed Apr 6 21:25:04 MDT 2005";

        try {
            new HINFORecord(n, DnsClass.IN, ttl, cpu, os);
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException e) {
        }
    }

    public
    void test_rrFromWire() throws IOException {
        String cpu = "Intel(R) Pentium(R) M processor 1.70GHz";
        String os = "Linux troy 2.6.10-gentoo-r6";

        byte[] raw = new byte[] {39, 'I', 'n', 't', 'e', 'l', '(', 'R', ')', ' ', 'P', 'e', 'n', 't', 'i', 'u', 'm', '(', 'R', ')', ' ',
                                 'M', ' ', 'p', 'r', 'o', 'c', 'e', 's', 's', 'o', 'r', ' ', '1', '.', '7', '0', 'G', 'H', 'z', 27, 'L',
                                 'i', 'n', 'u', 'x', ' ', 't', 'r', 'o', 'y', ' ', '2', '.', '6', '.', '1', '0', '-', 'g', 'e', 'n', 't',
                                 'o', 'o', '-', 'r', '6'};

        DnsInput in = new DnsInput(raw);

        HINFORecord dr = new HINFORecord();
        dr.rrFromWire(in);
        assertEquals(cpu, dr.getCPU());
        assertEquals(os, dr.getOS());
    }

    public
    void test_rdataFromString() throws IOException {
        String cpu = "Intel(R) Pentium(R) M processor 1.70GHz";
        String os = "Linux troy 2.6.10-gentoo-r6";

        Tokenizer t = new Tokenizer("\"" + cpu + "\" \"" + os + "\"");

        HINFORecord dr = new HINFORecord();
        dr.rdataFromString(t, null);
        assertEquals(cpu, dr.getCPU());
        assertEquals(os, dr.getOS());
    }

    public
    void test_rdataFromString_invalid_CPU() throws IOException {
        String cpu = "Intel(R) Pentium(R) \\388 M processor 1.70GHz";
        String os = "Linux troy 2.6.10-gentoo-r6";

        Tokenizer t = new Tokenizer("\"" + cpu + "\" \"" + os + "\"");

        HINFORecord dr = new HINFORecord();
        try {
            dr.rdataFromString(t, null);
            fail("TextParseException not thrown");
        } catch (TextParseException e) {
        }
    }

    public
    void test_rdataFromString_invalid_OS() throws IOException {
        String cpu = "Intel(R) Pentium(R) M processor 1.70GHz";

        Tokenizer t = new Tokenizer("\"" + cpu + "\"");

        HINFORecord dr = new HINFORecord();
        try {
            dr.rdataFromString(t, null);
            fail("TextParseException not thrown");
        } catch (TextParseException e) {
        }
    }

    public
    void test_rrToString() throws TextParseException {
        String cpu = "Intel(R) Pentium(R) M processor 1.70GHz";
        String os = "Linux troy 2.6.10-gentoo-r6";

        String exp = "\"" + cpu + "\" \"" + os + "\"";

        HINFORecord dr = new HINFORecord(Name.fromString("The.Name."), DnsClass.IN, 0x123, cpu, os);
        StringBuilder sb = new StringBuilder();
        dr.rrToString(sb);
        assertEquals(exp, sb.toString());
    }

    public
    void test_rrToWire() throws TextParseException {
        String cpu = "Intel(R) Pentium(R) M processor 1.70GHz";
        String os = "Linux troy 2.6.10-gentoo-r6";
        byte[] raw = new byte[] {39, 'I', 'n', 't', 'e', 'l', '(', 'R', ')', ' ', 'P', 'e', 'n', 't', 'i', 'u', 'm', '(', 'R', ')', ' ',
                                 'M', ' ', 'p', 'r', 'o', 'c', 'e', 's', 's', 'o', 'r', ' ', '1', '.', '7', '0', 'G', 'H', 'z', 27, 'L',
                                 'i', 'n', 'u', 'x', ' ', 't', 'r', 'o', 'y', ' ', '2', '.', '6', '.', '1', '0', '-', 'g', 'e', 'n', 't',
                                 'o', 'o', '-', 'r', '6'};

        HINFORecord dr = new HINFORecord(Name.fromString("The.Name."), DnsClass.IN, 0x123, cpu, os);

        DnsOutput out = new DnsOutput();
        dr.rrToWire(out, null, true);

        assertTrue(Arrays.equals(raw, out.toByteArray()));
    }
}
