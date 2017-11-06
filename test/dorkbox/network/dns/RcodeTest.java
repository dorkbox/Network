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
package dorkbox.network.dns;

import dorkbox.network.dns.constants.DnsResponseCode;
import junit.framework.TestCase;

public
class RcodeTest extends TestCase {
    public
    void test_string() {
        // a regular one
        assertEquals("NXDOMAIN", DnsResponseCode.string(DnsResponseCode.NXDOMAIN));

        // one with an alias
        assertEquals("NOTIMP", DnsResponseCode.string(DnsResponseCode.NOTIMP));

        // one that doesn't exist
        assertTrue(DnsResponseCode.string(20)
                                  .startsWith("RESERVED"));

        try {
            DnsResponseCode.string(-1);
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException ignored) {
        }

        //  (max is 0xFFF)
        try {
            DnsResponseCode.string(0x1000);
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException ignored) {
        }
    }

    public
    void test_TSIGstring() {
        // a regular one
        assertEquals("BADSIG", DnsResponseCode.TSIGstring(DnsResponseCode.BADSIG));

        // one that doesn't exist
        assertTrue(DnsResponseCode.TSIGstring(22)
                                  .startsWith("RESERVED"));

        try {
            DnsResponseCode.TSIGstring(-1);
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException ignored) {
        }

        //  (max is 0xFFFF)
        try {
            DnsResponseCode.string(0x10000);
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException ignored) {
        }
    }

    public
    void test_value() {
        // regular one
        assertEquals(DnsResponseCode.FORMERR, DnsResponseCode.value("FORMERR"));

        // one with alias
        assertEquals(DnsResponseCode.NOTIMP, DnsResponseCode.value("NOTIMP"));
        assertEquals(DnsResponseCode.NOTIMP, DnsResponseCode.value("NOTIMPL"));

        // one thats undefined but within range
        assertEquals(35, DnsResponseCode.value("RESERVED35"));

        // one thats undefined but out of range
        assertEquals(-1, DnsResponseCode.value("RESERVED" + 0x1000));

        // something that unknown
        assertEquals(-1, DnsResponseCode.value("THIS IS DEFINITELY UNKNOWN"));

        // empty string
        assertEquals(-1, DnsResponseCode.value(""));
    }
}
