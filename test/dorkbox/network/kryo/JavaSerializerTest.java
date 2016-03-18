/* Copyright (c) 2008, Nathan Sweet
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * - Neither the name of Esoteric Software nor the names of its contributors may be used to endorse or promote products derived
 * from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package dorkbox.network.kryo;

import com.esotericsoftware.kryo.serializers.JavaSerializer;

import java.io.Serializable;

/** @author Nathan Sweet <misc@n4te.com> */
public class JavaSerializerTest extends KryoTestCase {
	public void testJavaSerializer () {
		kryo.register(String.class, new JavaSerializer());
		roundTrip(50, 50,  "abcdefabcdefabcdefabcdefabcdefabcdefabcdef");
		roundTrip(12, 12, "meow");

		kryo.register(TestClass.class, new JavaSerializer());
		TestClass test = new TestClass();
		test.stringField = "fubar";
		test.intField = 54321;
		// HACK offset by ? from original because of changes in package name
		roundTrip(134-packageOffset, 134-packageOffset, test);
		roundTrip(134-packageOffset, 134-packageOffset, test);
		roundTrip(134-packageOffset, 134-packageOffset, test);
	}

	@SuppressWarnings("serial")
    static public class TestClass implements Serializable {
		String stringField;
		int intField;

		@Override
        public boolean equals (Object obj) {
			if (this == obj) {
                return true;
            }
			if (obj == null) {
                return false;
            }
			if (getClass() != obj.getClass()) {
                return false;
            }
			TestClass other = (TestClass)obj;
			if (intField != other.intField) {
                return false;
            }
			if (stringField == null) {
				if (other.stringField != null) {
                    return false;
                }
			} else if (!stringField.equals(other.stringField)) {
                return false;
            }
			return true;
		}

        @Override
        public int hashCode() {
            return super.hashCode();
        }
	}
}