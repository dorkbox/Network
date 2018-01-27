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
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Date;

import dorkbox.network.dns.Compression;
import dorkbox.network.dns.DnsInput;
import dorkbox.network.dns.DnsOutput;
import dorkbox.network.dns.Name;
import dorkbox.network.dns.constants.DnsClass;
import dorkbox.network.dns.constants.DnsRecordType;
import dorkbox.network.dns.constants.DnsSection;
import dorkbox.network.dns.exceptions.*;
import dorkbox.network.dns.utils.Options;
import dorkbox.network.dns.utils.Tokenizer;
import junit.framework.TestCase;

public
class RecordTest extends TestCase {
    private static
    class SubRecord extends DnsRecord {
        public
        SubRecord() {}

        public
        SubRecord(Name name, int type, int dclass, long ttl) {
            super(name, type, dclass, ttl);
        }

        @Override
        public
        DnsRecord getObject() {
            return null;
        }

        @Override
        public
        void rrFromWire(DnsInput in) throws IOException {}

        @Override
        public
        void rrToWire(DnsOutput out, Compression c, boolean canonical) {}

        @Override
        public
        void rrToString(StringBuilder sb) {
            sb.append("{SubRecord: rrToString}");
        }

        @Override
        public
        void rdataFromString(Tokenizer t, Name origin) throws IOException {}

        // makes it callable by test code
        public static
        byte[] byteArrayFromString(String in) throws TextParseException {
            return DnsRecord.byteArrayFromString(in);
        }

        // make it callable by test code
        public static
        String byteArrayToString(byte[] in, boolean quote) {
            return DnsRecord.byteArrayToString(in, quote);
        }

        // make it callable by test code
        public static
        String unknownToString(byte[] in) {
            return DnsRecord.unknownToString(in);
        }

        @Override
        public
        Object clone() throws CloneNotSupportedException {
            throw new CloneNotSupportedException();
        }
    }

    public
    void test_ctor_0arg() {
        SubRecord sr = new SubRecord();
        assertNull(sr.getName());
        assertEquals(0, sr.getType());
        assertEquals(0, sr.getTTL());
        assertEquals(0, sr.getDClass());
    }

    public
    void test_ctor_4arg() throws TextParseException {
        Name n = Name.fromString("my.name.");
        int t = DnsRecordType.A;
        int d = DnsClass.IN;
        long ttl = 0xABCDEL;

        SubRecord r = new SubRecord(n, t, d, ttl);
        assertEquals(n, r.getName());
        assertEquals(t, r.getType());
        assertEquals(d, r.getDClass());
        assertEquals(ttl, r.getTTL());
    }

    public
    void test_ctor_4arg_invalid() throws TextParseException {
        Name n = Name.fromString("my.name.");
        Name r = Name.fromString("my.relative.name");
        int t = DnsRecordType.A;
        int d = DnsClass.IN;
        long ttl = 0xABCDEL;

        try {
            new SubRecord(r, t, d, ttl);
            fail("RelativeNameException not thrown");
        } catch (RelativeNameException ignored) {
        }

        try {
            new SubRecord(n, -1, d, ttl);
            fail("InvalidTypeException not thrown");
        } catch (InvalidTypeException ignored) {
        }

        try {
            new SubRecord(n, t, -1, ttl);
            fail("InvalidDClassException not thrown");
        } catch (InvalidDClassException ignored) {
        }

        try {
            new SubRecord(n, t, d, -1);
            fail("InvalidTTLException not thrown");
        } catch (InvalidTTLException ignored) {
        }
    }

    public
    void test_newRecord_3arg() throws TextParseException {
        Name n = Name.fromString("my.name.");
        Name r = Name.fromString("my.relative.name");
        int t = DnsRecordType.A;
        int d = DnsClass.IN;

        DnsRecord rec = DnsRecord.newRecord(n, t, d);
        assertTrue(rec instanceof EmptyRecord);
        assertEquals(n, rec.getName());
        assertEquals(t, rec.getType());
        assertEquals(d, rec.getDClass());
        assertEquals(0, rec.getTTL());

        try {
            DnsRecord.newRecord(r, t, d);
            fail("RelativeNameException not thrown");
        } catch (RelativeNameException ignored) {
        }
    }

    public
    void test_newRecord_4arg() throws TextParseException {
        Name n = Name.fromString("my.name.");
        Name r = Name.fromString("my.relative.name");
        int t = DnsRecordType.A;
        int d = DnsClass.IN;
        int ttl = 0xDBE8;

        DnsRecord rec = DnsRecord.newRecord(n, t, d, ttl);
        assertTrue(rec instanceof EmptyRecord);
        assertEquals(n, rec.getName());
        assertEquals(t, rec.getType());
        assertEquals(d, rec.getDClass());
        assertEquals(ttl, rec.getTTL());

        try {
            DnsRecord.newRecord(r, t, d, ttl);
            fail("RelativeNameException not thrown");
        } catch (RelativeNameException ignored) {
        }
    }

    public
    void test_newRecord_5arg() throws TextParseException, UnknownHostException {
        Name n = Name.fromString("my.name.");
        int t = DnsRecordType.A;
        int d = DnsClass.IN;
        int ttl = 0xDBE8;
        byte[] data = new byte[] {(byte) 123, (byte) 232, (byte) 0, (byte) 255};
        InetAddress exp = InetAddress.getByName("123.232.0.255");

        DnsRecord rec = DnsRecord.newRecord(n, t, d, ttl, data);
        assertTrue(rec instanceof ARecord);
        assertEquals(n, rec.getName());
        assertEquals(t, rec.getType());
        assertEquals(d, rec.getDClass());
        assertEquals(ttl, rec.getTTL());
        assertEquals(exp, ((ARecord) rec).getAddress());
    }

    public
    void test_newRecord_6arg() throws TextParseException, UnknownHostException {
        Name n = Name.fromString("my.name.");
        int t = DnsRecordType.A;
        int d = DnsClass.IN;
        int ttl = 0xDBE8;
        byte[] data = new byte[] {(byte) 123, (byte) 232, (byte) 0, (byte) 255};
        InetAddress exp = InetAddress.getByName("123.232.0.255");

        DnsRecord rec = DnsRecord.newRecord(n, t, d, ttl, 0, null);
        assertTrue(rec instanceof EmptyRecord);
        assertEquals(n, rec.getName());
        assertEquals(t, rec.getType());
        assertEquals(d, rec.getDClass());
        assertEquals(ttl, rec.getTTL());

        rec = DnsRecord.newRecord(n, t, d, ttl, data.length, data);
        assertTrue(rec instanceof ARecord);
        assertEquals(n, rec.getName());
        assertEquals(t, rec.getType());
        assertEquals(d, rec.getDClass());
        assertEquals(ttl, rec.getTTL());
        assertEquals(exp, ((ARecord) rec).getAddress());

        rec = DnsRecord.newRecord(n, DnsRecordType.NIMLOC, d, ttl, data.length, data);
        assertTrue(rec instanceof UNKRecord);
        assertEquals(n, rec.getName());
        assertEquals(DnsRecordType.NIMLOC, rec.getType());
        assertEquals(d, rec.getDClass());
        assertEquals(ttl, rec.getTTL());
        assertTrue(Arrays.equals(data, ((UNKRecord) rec).getData()));
    }

    public
    void test_newRecord_6arg_invalid() throws TextParseException {
        Name n = Name.fromString("my.name.");
        Name r = Name.fromString("my.relative.name");
        int t = DnsRecordType.A;
        int d = DnsClass.IN;
        int ttl = 0xDBE8;
        byte[] data = new byte[] {(byte) 123, (byte) 232, (byte) 0, (byte) 255};

        assertNull(DnsRecord.newRecord(n, t, d, ttl, 0, new byte[0]));
        assertNull(DnsRecord.newRecord(n, t, d, ttl, 1, new byte[0]));
        assertNull(DnsRecord.newRecord(n, t, d, ttl, data.length + 1, data));
        assertNull(DnsRecord.newRecord(n, t, d, ttl, 5, new byte[] {data[0], data[1], data[2], data[3], 0}));
        try {
            DnsRecord.newRecord(r, t, d, ttl, 0, null);
            fail("RelativeNameException not thrown");
        } catch (RelativeNameException ignored) {
        }

    }

    public
    void test_fromWire() throws IOException, TextParseException, UnknownHostException {
        Name n = Name.fromString("my.name.");
        int t = DnsRecordType.A;
        int d = DnsClass.IN;
        int ttl = 0xDBE8;
        byte[] data = new byte[] {(byte) 123, (byte) 232, (byte) 0, (byte) 255};
        InetAddress exp = InetAddress.getByName("123.232.0.255");

        DnsOutput out = new DnsOutput();
        n.toWire(out, null);
        out.writeU16(t);
        out.writeU16(d);
        out.writeU32(ttl);
        out.writeU16(data.length);
        out.writeByteArray(data);

        byte[] bytes = out.toByteArray();
        DnsInput in = new DnsInput(bytes);

        DnsRecord rec = DnsRecord.fromWire(in, DnsSection.ANSWER, false);
        assertTrue(rec instanceof ARecord);
        assertEquals(n, rec.getName());
        assertEquals(t, rec.getType());
        assertEquals(d, rec.getDClass());
        assertEquals(ttl, rec.getTTL());
        assertEquals(exp, ((ARecord) rec).getAddress());

        in = new DnsInput(bytes);
        rec = DnsRecord.fromWire(in, DnsSection.QUESTION, false);
        assertTrue(rec instanceof EmptyRecord);
        assertEquals(n, rec.getName());
        assertEquals(t, rec.getType());
        assertEquals(d, rec.getDClass());
        assertEquals(0, rec.getTTL());

        in = new DnsInput(bytes);
        rec = DnsRecord.fromWire(in, DnsSection.QUESTION);
        assertTrue(rec instanceof EmptyRecord);
        assertEquals(n, rec.getName());
        assertEquals(t, rec.getType());
        assertEquals(d, rec.getDClass());
        assertEquals(0, rec.getTTL());

        rec = DnsRecord.fromWire(bytes, DnsSection.QUESTION);
        assertTrue(rec instanceof EmptyRecord);
        assertEquals(n, rec.getName());
        assertEquals(t, rec.getType());
        assertEquals(d, rec.getDClass());
        assertEquals(0, rec.getTTL());

        out = new DnsOutput();
        n.toWire(out, null);
        out.writeU16(t);
        out.writeU16(d);
        out.writeU32(ttl);
        out.writeU16(0);

        in = new DnsInput(out.toByteArray());

        rec = DnsRecord.fromWire(in, DnsSection.ANSWER, true);
        assertTrue(rec instanceof EmptyRecord);
        assertEquals(n, rec.getName());
        assertEquals(t, rec.getType());
        assertEquals(d, rec.getDClass());
        assertEquals(ttl, rec.getTTL());

    }

    public
    void test_toWire() throws IOException, TextParseException, UnknownHostException

    {
        Name n = Name.fromString("my.name.");
        int t = DnsRecordType.A;
        int d = DnsClass.IN;
        int ttl = 0xDBE8;
        byte[] data = new byte[] {(byte) 123, (byte) 232, (byte) 0, (byte) 255};

        // a non-QUESTION
        DnsOutput out = new DnsOutput();
        n.toWire(out, null);
        out.writeU16(t);
        out.writeU16(d);
        out.writeU32(ttl);
        out.writeU16(data.length);
        out.writeByteArray(data);

        byte[] exp = out.toByteArray();

        DnsRecord rec = DnsRecord.newRecord(n, t, d, ttl, data.length, data);

        out = new DnsOutput();

        rec.toWire(out, DnsSection.ANSWER, null);

        byte[] after = out.toByteArray();

        assertTrue(Arrays.equals(exp, after));

        // an equivalent call
        after = rec.toWire(DnsSection.ANSWER);
        assertTrue(Arrays.equals(exp, after));

        // a QUESTION entry
        out = new DnsOutput();
        n.toWire(out, null);
        out.writeU16(t);
        out.writeU16(d);

        exp = out.toByteArray();
        out = new DnsOutput();
        rec.toWire(out, DnsSection.QUESTION, null);
        after = out.toByteArray();

        assertTrue(Arrays.equals(exp, after));

    }

    public
    void test_toWireCanonical() throws IOException, TextParseException, UnknownHostException

    {
        Name n = Name.fromString("My.Name.");
        int t = DnsRecordType.A;
        int d = DnsClass.IN;
        int ttl = 0xDBE8;
        byte[] data = new byte[] {(byte) 123, (byte) 232, (byte) 0, (byte) 255};

        DnsOutput out = new DnsOutput();
        n.toWireCanonical(out);
        out.writeU16(t);
        out.writeU16(d);
        out.writeU32(ttl);
        out.writeU16(data.length);
        out.writeByteArray(data);

        byte[] exp = out.toByteArray();

        DnsRecord rec = DnsRecord.newRecord(n, t, d, ttl, data.length, data);

        byte[] after = rec.toWireCanonical();
        assertTrue(Arrays.equals(exp, after));
    }

    public
    void test_rdataToWireCanonical() throws IOException, TextParseException, UnknownHostException

    {
        Name n = Name.fromString("My.Name.");
        Name n2 = Name.fromString("My.Second.Name.");
        int t = DnsRecordType.NS;
        int d = DnsClass.IN;
        int ttl = 0xABE99;
        DnsOutput out = new DnsOutput();
        n2.toWire(out, null);
        byte[] data = out.toByteArray();

        out = new DnsOutput();
        n2.toWireCanonical(out);
        byte[] exp = out.toByteArray();

        DnsRecord rec = DnsRecord.newRecord(n, t, d, ttl, data.length, data);
        assertTrue(rec instanceof NSRecord);

        byte[] after = rec.rdataToWireCanonical();

        assertTrue(Arrays.equals(exp, after));
    }

    public
    void test_rdataToString() throws IOException, TextParseException, UnknownHostException

    {
        Name n = Name.fromString("My.Name.");
        Name n2 = Name.fromString("My.Second.Name.");
        int t = DnsRecordType.NS;
        int d = DnsClass.IN;
        int ttl = 0xABE99;
        DnsOutput out = new DnsOutput();
        n2.toWire(out, null);
        byte[] data = out.toByteArray();

        DnsRecord rec = DnsRecord.newRecord(n, t, d, ttl, data.length, data);
        assertTrue(rec instanceof NSRecord);
        StringBuilder sa = new StringBuilder();
        rec.rrToString(sa);

        StringBuilder sb = new StringBuilder();
        rec.rdataToString(sb);

        assertEquals(sa.toString(), sb.toString());
    }

    public
    void test_toString() throws TextParseException {
        Name n = Name.fromString("My.N.");
        Name n2 = Name.fromString("My.Second.Name.");
        int t = DnsRecordType.NS;
        int d = DnsClass.IN;
        int ttl = 0xABE99;
        DnsOutput o = new DnsOutput();
        n2.toWire(o, null);
        byte[] data = o.toByteArray();

        DnsRecord rec = DnsRecord.newRecord(n, t, d, ttl, data.length, data);
        String out = rec.toString();

        assertFalse(out.indexOf(n.toString()) == -1);
        assertFalse(out.indexOf(n2.toString()) == -1);
        assertFalse(out.indexOf("NS") == -1);
        assertFalse(out.indexOf("IN") == -1);
        assertFalse(out.indexOf(ttl + "") == -1);

        Options.set("BINDTTL");

        out = rec.toString();
        assertFalse(out.indexOf(n.toString()) == -1);
        assertFalse(out.indexOf(n2.toString()) == -1);
        assertFalse(out.indexOf("NS") == -1);
        assertFalse(out.indexOf("IN") == -1);
        assertFalse(out.indexOf(TTL.format(ttl)) == -1);

        Options.set("noPrintIN");
        out = rec.toString();
        assertFalse(out.indexOf(n.toString()) == -1);
        assertFalse(out.indexOf(n2.toString()) == -1);
        assertFalse(out.indexOf("NS") == -1);
        assertTrue(out.indexOf("IN") == -1);
        assertFalse(out.indexOf(TTL.format(ttl)) == -1);
    }

    public
    void test_byteArrayFromString() throws TextParseException {
        String in = "the 98 \" \' quick 0xAB brown";
        byte[] out = SubRecord.byteArrayFromString(in);
        assertTrue(Arrays.equals(in.getBytes(), out));

        in = " \\031Aa\\;\\\"\\\\~\\127\\255";
        byte[] exp = new byte[] {' ', 0x1F, 'A', 'a', ';', '"', '\\', 0x7E, 0x7F, (byte) 0xFF};
        out = SubRecord.byteArrayFromString(in);
        assertTrue(Arrays.equals(exp, out));
    }

    public
    void test_byteArrayFromString_invalid() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 257; ++i) {
            b.append('A');
        }
        try {
            SubRecord.byteArrayFromString(b.toString());
            fail("TextParseException not thrown");
        } catch (TextParseException ignored) {
        }

        try {
            SubRecord.byteArrayFromString("\\256");
            fail("TextParseException not thrown");
        } catch (TextParseException ignored) {
        }
        try {
            SubRecord.byteArrayFromString("\\25a");
            fail("TextParseException not thrown");
        } catch (TextParseException ignored) {
        }
        try {
            SubRecord.byteArrayFromString("\\25");
            fail("TextParseException not thrown");
        } catch (TextParseException ignored) {
        }

        b.append("\\233");
        try {
            SubRecord.byteArrayFromString(b.toString());
            fail("TextParseException not thrown");
        } catch (TextParseException ignored) {
        }

    }

    public
    void test_byteArrayToString() {
        byte[] in = new byte[] {' ', 0x1F, 'A', 'a', ';', '"', '\\', 0x7E, 0x7F, (byte) 0xFF};
        String exp = "\" \\031Aa;\\\"\\\\~\\127\\255\"";
        assertEquals(exp, SubRecord.byteArrayToString(in, true));
    }

    public
    void test_unknownToString() {
        byte[] data = new byte[] {(byte) 0x12, (byte) 0x34, (byte) 0x56, (byte) 0x78, (byte) 0x9A, (byte) 0xBC, (byte) 0xDE, (byte) 0xFF};
        String out = SubRecord.unknownToString(data);

        assertFalse(out.indexOf("" + data.length) == -1);
        assertFalse(out.indexOf("123456789ABCDEFF") == -1);
    }

    public
    void test_fromString() throws IOException, TextParseException {
        Name n = Name.fromString("My.N.");
        Name n2 = Name.fromString("My.Second.Name.");
        int t = DnsRecordType.A;
        int d = DnsClass.IN;
        int ttl = 0xABE99;
        String sa = "191.234.43.10";
        InetAddress addr = InetAddress.getByName(sa);
        byte[] b = new byte[] {(byte) 191, (byte) 234, (byte) 43, (byte) 10};

        Tokenizer st = new Tokenizer(sa);
        DnsRecord rec = DnsRecord.fromString(n, t, d, ttl, st, n2);
        assertTrue(rec instanceof ARecord);
        assertEquals(n, rec.getName());
        assertEquals(t, rec.getType());
        assertEquals(d, rec.getDClass());
        assertEquals(ttl, rec.getTTL());
        assertEquals(addr, ((ARecord) rec).getAddress());

        String unkData = SubRecord.unknownToString(b);
        st = new Tokenizer(unkData);
        rec = DnsRecord.fromString(n, t, d, ttl, st, n2);
        assertTrue(rec instanceof ARecord);
        assertEquals(n, rec.getName());
        assertEquals(t, rec.getType());
        assertEquals(d, rec.getDClass());
        assertEquals(ttl, rec.getTTL());
        assertEquals(addr, ((ARecord) rec).getAddress());
    }

    public
    void test_fromString_invalid() throws IOException, TextParseException {
        Name n = Name.fromString("My.N.");
        Name rel = Name.fromString("My.R");
        Name n2 = Name.fromString("My.Second.Name.");
        int t = DnsRecordType.A;
        int d = DnsClass.IN;
        int ttl = 0xABE99;
        InetAddress addr = InetAddress.getByName("191.234.43.10");

        Tokenizer st = new Tokenizer("191.234.43.10");

        try {
            DnsRecord.fromString(rel, t, d, ttl, st, n2);
            fail("RelativeNameException not thrown");
        } catch (RelativeNameException ignored) {
        }

        st = new Tokenizer("191.234.43.10 another_token");
        try {
            DnsRecord.fromString(n, t, d, ttl, st, n2);
            fail("TextParseException not thrown");
        } catch (TextParseException ignored) {
        }

        st = new Tokenizer("\\# 100 ABCDE");
        try {
            DnsRecord.fromString(n, t, d, ttl, st, n2);
            fail("TextParseException not thrown");
        } catch (TextParseException ignored) {
        }

        try {
            DnsRecord.fromString(n, t, d, ttl, "\\# 100", n2);
            fail("TextParseException not thrown");
        } catch (TextParseException ignored) {
        }
    }

    public
    void test_getRRsetType() throws TextParseException {
        Name n = Name.fromString("My.N.");

        DnsRecord r = DnsRecord.newRecord(n, DnsRecordType.A, DnsClass.IN, 0);
        assertEquals(DnsRecordType.A, r.getRRsetType());

        r = new RRSIGRecord(n, DnsClass.IN, 0, DnsRecordType.A, 1, 0, new Date(), new Date(), 10, n, new byte[0]);
        assertEquals(DnsRecordType.A, r.getRRsetType());
    }

    public
    void test_sameRRset() throws TextParseException {
        Name n = Name.fromString("My.N.");
        Name m = Name.fromString("My.M.");

        DnsRecord r1 = DnsRecord.newRecord(n, DnsRecordType.A, DnsClass.IN, 0);
        DnsRecord r2 = new RRSIGRecord(n, DnsClass.IN, 0, DnsRecordType.A, 1, 0, new Date(), new Date(), 10, n, new byte[0]);
        assertTrue(r1.sameRRset(r2));
        assertTrue(r2.sameRRset(r1));

        r1 = DnsRecord.newRecord(n, DnsRecordType.A, DnsClass.HS, 0);
        r2 = new RRSIGRecord(n, DnsClass.IN, 0, DnsRecordType.A, 1, 0, new Date(), new Date(), 10, n, new byte[0]);
        assertFalse(r1.sameRRset(r2));
        assertFalse(r2.sameRRset(r1));

        r1 = DnsRecord.newRecord(n, DnsRecordType.A, DnsClass.IN, 0);
        r2 = new RRSIGRecord(m, DnsClass.IN, 0, DnsRecordType.A, 1, 0, new Date(), new Date(), 10, n, new byte[0]);
        assertFalse(r1.sameRRset(r2));
        assertFalse(r2.sameRRset(r1));
    }

    public
    void test_equals() throws TextParseException {
        Name n = Name.fromString("My.N.");
        Name n2 = Name.fromString("my.n.");
        Name m = Name.fromString("My.M.");

        DnsRecord r1 = DnsRecord.newRecord(n, DnsRecordType.A, DnsClass.IN, 0);

        assertFalse(r1.equals(null));
        assertFalse(r1.equals(new Object()));

        DnsRecord r2 = DnsRecord.newRecord(n, DnsRecordType.A, DnsClass.IN, 0);
        assertEquals(r1, r2);
        assertEquals(r2, r1);

        r2 = DnsRecord.newRecord(n2, DnsRecordType.A, DnsClass.IN, 0);
        assertEquals(r1, r2);
        assertEquals(r2, r1);

        r2 = DnsRecord.newRecord(n2, DnsRecordType.A, DnsClass.IN, 0xABCDE);
        assertEquals(r1, r2);
        assertEquals(r2, r1);

        r2 = DnsRecord.newRecord(m, DnsRecordType.A, DnsClass.IN, 0xABCDE);
        assertFalse(r1.equals(r2));
        assertFalse(r2.equals(r1));

        r2 = DnsRecord.newRecord(n2, DnsRecordType.MX, DnsClass.IN, 0xABCDE);
        assertFalse(r1.equals(r2));
        assertFalse(r2.equals(r1));

        r2 = DnsRecord.newRecord(n2, DnsRecordType.A, DnsClass.CHAOS, 0xABCDE);
        assertFalse(r1.equals(r2));
        assertFalse(r2.equals(r1));

        byte[] d1 = new byte[] {23, 12, 9, (byte) 129};
        byte[] d2 = new byte[] {(byte) 220, 1, (byte) 131, (byte) 212};

        r1 = DnsRecord.newRecord(n, DnsRecordType.A, DnsClass.IN, 0xABCDE9, d1);
        r2 = DnsRecord.newRecord(n, DnsRecordType.A, DnsClass.IN, 0xABCDE9, d1);

        assertEquals(r1, r2);
        assertEquals(r2, r1);

        r2 = DnsRecord.newRecord(n, DnsRecordType.A, DnsClass.IN, 0xABCDE9, d2);

        assertFalse(r1.equals(r2));
        assertFalse(r2.equals(r1));
    }

    public
    void test_hashCode() throws TextParseException {
        Name n = Name.fromString("My.N.");
        Name n2 = Name.fromString("my.n.");
        Name m = Name.fromString("My.M.");
        byte[] d1 = new byte[] {23, 12, 9, (byte) 129};
        byte[] d2 = new byte[] {(byte) 220, 1, (byte) 131, (byte) 212};

        DnsRecord r1 = DnsRecord.newRecord(n, DnsRecordType.A, DnsClass.IN, 0xABCDE9, d1);

        // same DnsDnsRecord has same hash code
        DnsRecord r2 = DnsRecord.newRecord(n, DnsRecordType.A, DnsClass.IN, 0xABCDE9, d1);
        assertEquals(r1.hashCode(), r2.hashCode());

        // case of names should not matter
        r2 = DnsRecord.newRecord(n2, DnsRecordType.A, DnsClass.IN, 0xABCDE9, d1);
        assertEquals(r1.hashCode(), r2.hashCode());

        // different names
        r2 = DnsRecord.newRecord(m, DnsRecordType.A, DnsClass.IN, 0xABCDE9, d1);
        assertFalse(r1.hashCode() == r2.hashCode());

        // different class
        r2 = DnsRecord.newRecord(n, DnsRecordType.A, DnsClass.CHAOS, 0xABCDE9, d1);
        assertFalse(r1.hashCode() == r2.hashCode());

        // different TTL does not matter
        r2 = DnsRecord.newRecord(n, DnsRecordType.A, DnsClass.IN, 0xABCDE, d1);
        assertEquals(r1.hashCode(), r2.hashCode());

        // different data
        r2 = DnsRecord.newRecord(n, DnsRecordType.A, DnsClass.IN, 0xABCDE9, d2);
        assertFalse(r1.hashCode() == r2.hashCode());
    }

    public
    void test_cloneRecord() throws TextParseException {
        Name n = Name.fromString("My.N.");
        byte[] d = new byte[] {23, 12, 9, (byte) 129};
        DnsRecord r = DnsRecord.newRecord(n, DnsRecordType.A, DnsClass.IN, 0xABCDE9, d);

        DnsRecord r2 = r.cloneRecord();

        assertNotSame(r, r2);
        assertEquals(r, r2);

        r = new SubRecord(n, DnsRecordType.A, DnsClass.IN, 0xABCDE9);

        try {
            r.cloneRecord();
            fail("IllegalStateException not thrown");
        } catch (IllegalStateException ignored) {
        }
    }

    public
    void test_withName() throws TextParseException {
        Name n = Name.fromString("My.N.");
        Name m = Name.fromString("My.M.Name.");
        Name rel = Name.fromString("My.Relative.Name");
        byte[] d = new byte[] {23, 12, 9, (byte) 129};
        DnsRecord r = DnsRecord.newRecord(n, DnsRecordType.A, DnsClass.IN, 0xABCDE9, d);

        DnsRecord r1 = r.withName(m);

        assertEquals(m, r1.getName());
        assertEquals(DnsRecordType.A, r1.getType());
        assertEquals(DnsClass.IN, r1.getDClass());
        assertEquals(0xABCDE9, r1.getTTL());
        assertEquals(((ARecord) r).getAddress(), ((ARecord) r1).getAddress());

        try {
            r.withName(rel);
            fail("RelativeNameException not thrown");
        } catch (RelativeNameException ignored) {
        }
    }

    public
    void test_withDClass() throws TextParseException {
        Name n = Name.fromString("My.N.");
        byte[] d = new byte[] {23, 12, 9, (byte) 129};
        DnsRecord r = DnsRecord.newRecord(n, DnsRecordType.A, DnsClass.IN, 0xABCDE9, d);

        DnsRecord r1 = r.withDClass(DnsClass.HESIOD, 0x9876);

        assertEquals(n, r1.getName());
        assertEquals(DnsRecordType.A, r1.getType());
        assertEquals(DnsClass.HESIOD, r1.getDClass());
        assertEquals(0x9876, r1.getTTL());
        assertEquals(((ARecord) r).getAddress(), ((ARecord) r1).getAddress());
    }

    public
    void test_setTTL() throws TextParseException, UnknownHostException {
        Name n = Name.fromString("My.N.");
        byte[] d = new byte[] {23, 12, 9, (byte) 129};
        InetAddress exp = InetAddress.getByName("23.12.9.129");
        DnsRecord r = DnsRecord.newRecord(n, DnsRecordType.A, DnsClass.IN, 0xABCDE9, d);

        assertEquals(0xABCDE9, r.getTTL());

        r.setTTL(0x9876);

        assertEquals(n, r.getName());
        assertEquals(DnsRecordType.A, r.getType());
        assertEquals(DnsClass.IN, r.getDClass());
        assertEquals(0x9876, r.getTTL());
        assertEquals(exp, ((ARecord) r).getAddress());
    }

    public
    void test_compareTo() throws TextParseException {
        Name n = Name.fromString("My.N.");
        Name n2 = Name.fromString("my.n.");
        Name m = Name.fromString("My.M.");
        byte[] d = new byte[] {23, 12, 9, (byte) 129};
        byte[] d2 = new byte[] {23, 12, 9, (byte) 128};
        DnsRecord r1 = DnsRecord.newRecord(n, DnsRecordType.A, DnsClass.IN, 0xABCDE9, d);
        DnsRecord r2 = DnsRecord.newRecord(n, DnsRecordType.A, DnsClass.IN, 0xABCDE9, d);

        assertEquals(0, r1.compareTo(r1));

        assertEquals(0, r1.compareTo(r2));
        assertEquals(0, r2.compareTo(r1));

        // name comparison should be canonical
        r2 = DnsRecord.newRecord(n2, DnsRecordType.A, DnsClass.IN, 0xABCDE9, d);
        assertEquals(0, r1.compareTo(r2));
        assertEquals(0, r2.compareTo(r1));

        // different name
        r2 = DnsRecord.newRecord(m, DnsRecordType.A, DnsClass.IN, 0xABCDE9, d);
        assertEquals(n.compareTo(m), r1.compareTo(r2));
        assertEquals(m.compareTo(n), r2.compareTo(r1));

        // different DnsClass
        r2 = DnsRecord.newRecord(n, DnsRecordType.A, DnsClass.CHAOS, 0xABCDE9, d);
        assertEquals(DnsClass.IN - DnsClass.CHAOS, r1.compareTo(r2));
        assertEquals(DnsClass.CHAOS - DnsClass.IN, r2.compareTo(r1));

        // different DnsRecordType
        r2 = DnsRecord.newRecord(n, DnsRecordType.NS, DnsClass.IN, 0xABCDE9, m.toWire());
        assertEquals(DnsRecordType.A - DnsRecordType.NS, r1.compareTo(r2));
        assertEquals(DnsRecordType.NS - DnsRecordType.A, r2.compareTo(r1));

        // different data (same length)
        r2 = DnsRecord.newRecord(n, DnsRecordType.A, DnsClass.IN, 0xABCDE9, d2);
        assertEquals(1, r1.compareTo(r2));
        assertEquals(-1, r2.compareTo(r1));

        // different data (one a prefix of the other)
        m = Name.fromString("My.N.L.");
        r1 = DnsRecord.newRecord(n, DnsRecordType.NS, DnsClass.IN, 0xABCDE9, n.toWire());
        r2 = DnsRecord.newRecord(n, DnsRecordType.NS, DnsClass.IN, 0xABCDE9, m.toWire());
        assertEquals(-1, r1.compareTo(r2));
        assertEquals(1, r2.compareTo(r1));
    }

    public
    void test_getAdditionalName() throws TextParseException {
        Name n = Name.fromString("My.N.");
        DnsRecord r = new SubRecord(n, DnsRecordType.A, DnsClass.IN, 0xABCDE9);

        assertNull(r.getAdditionalName());
    }

    public
    void test_checkU8() {
        try {
            DnsRecord.checkU8("field", -1);
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException ignored) {
        }
        assertEquals(0, DnsRecord.checkU8("field", 0));
        assertEquals(0x9D, DnsRecord.checkU8("field", 0x9D));
        assertEquals(0xFF, DnsRecord.checkU8("field", 0xFF));
        try {
            DnsRecord.checkU8("field", 0x100);
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException ignored) {
        }
    }

    public
    void test_checkU16() {
        try {
            DnsRecord.checkU16("field", -1);
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException ignored) {
        }
        assertEquals(0, DnsRecord.checkU16("field", 0));
        assertEquals(0x9DA1, DnsRecord.checkU16("field", 0x9DA1));
        assertEquals(0xFFFF, DnsRecord.checkU16("field", 0xFFFF));
        try {
            DnsRecord.checkU16("field", 0x10000);
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException ignored) {
        }
    }

    public
    void test_checkU32() {
        try {
            DnsRecord.checkU32("field", -1);
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException ignored) {
        }
        assertEquals(0, DnsRecord.checkU32("field", 0));
        assertEquals(0x9DA1F02DL, DnsRecord.checkU32("field", 0x9DA1F02DL));
        assertEquals(0xFFFFFFFFL, DnsRecord.checkU32("field", 0xFFFFFFFFL));
        try {
            DnsRecord.checkU32("field", 0x100000000L);
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException ignored) {
        }
    }

    public
    void test_checkName() throws TextParseException {
        Name n = Name.fromString("My.N.");
        Name m = Name.fromString("My.m");

        assertEquals(n, DnsRecord.checkName("field", n));

        try {
            DnsRecord.checkName("field", m);
            fail("RelativeNameException not thrown");
        } catch (RelativeNameException ignored) {
        }
    }
}
